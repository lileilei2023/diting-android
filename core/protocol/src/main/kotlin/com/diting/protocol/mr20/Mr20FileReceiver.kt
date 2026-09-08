package com.diting.protocol.mr20

/**
 * Reassembles one recording file out of the raw packets the MR20 pushes after an
 * `AA_BLE&U&…` (BLE) or `AA_BLE&W&…` (Wi-Fi) request.
 *
 * The two channels differ in exactly one respect, and it is the one the protocol
 * document is emphatic about:
 *
 *  * **BLE** — "每帧数据都是有效数据，APP端需全部保存". Every byte belongs to the
 *    file; completion is signalled out-of-band by `AA_DEV&OFF` on the command
 *    characteristic.
 *  * **Wi-Fi** — same, "除了最后五个字节数据：0xba 0x5a 0x02 0x8f 0x04". Those five
 *    bytes are a trailer, must not be written to the file, and are the app's cue
 *    that the transfer finished.
 *
 * Both channels deliver arbitrary chunk boundaries, so the trailer can and does
 * straddle two reads. This class is the single place that gets that right.
 *
 * Not thread-safe: feed it from one coroutine.
 */
class Mr20FileReceiver(
    /**
     * Byte count announced by `AA_DEV&U&LEN` / `AA_DEV&W&LEN`. When this is
     * positive it is authoritative and the receiver completes on the exact byte.
     * Pass 0 if the device did not announce one yet; the Wi-Fi channel then falls
     * back to scanning for the trailer, and the BLE channel to waiting for
     * `AA_DEV&OFF`. A length that arrives later is supplied via [announceLength].
     */
    expectedBytes: Long,
    val channel: Mr20TransferChannel,
    /** Receives file bytes in order. Called with a slice of an internal buffer. */
    private val sink: (buffer: ByteArray, offset: Int, length: Int) -> Unit,
) {

    enum class State {
        /** Still expecting more file bytes. */
        RECEIVING,

        /** All file bytes delivered; for Wi-Fi the trailer was seen and verified. */
        COMPLETE,

        /** [abort] was called, or the trailer did not match. */
        FAILED,
    }

    /**
     * Authoritative byte count once known. Mutable because on BLE the receiver has
     * to be armed *before* the request is written — the data characteristic can
     * deliver its first packet before the app has processed the `AA_DEV&U&LEN`
     * notification on the command characteristic — so the length arrives after the
     * first bytes do. See [announceLength].
     */
    var expectedBytes: Long = expectedBytes
        private set

    var state: State = State.RECEIVING
        private set

    /** File bytes handed to [sink] so far — excludes the Wi-Fi trailer. */
    var receivedBytes: Long = 0L
        private set

    /** Non-null once the transfer fails; describes why. */
    var failure: String? = null
        private set

    /**
     * BLE only: bytes that arrived on the data characteristic after the announced
     * length had been reached. On a real MR20 that is the live MP3 stream — a
     * recording card keeps pushing audio on the same characteristic — so it is
     * counted, never written, and not treated as a failure.
     */
    var surplusBytes: Long = 0L
        private set

    private val marker = Mr20Protocol.WIFI_EOF_MARKER
    private val expectsMarker = channel == Mr20TransferChannel.WIFI

    /**
     * Bytes withheld from [sink] because they might be the start of the trailer.
     * Only used when [expectedBytes] is unknown; at most `marker.size` long.
     */
    private val holdback = ByteArray(marker.size)
    private var holdbackLen = 0

    /** Trailer bytes collected after the announced length was reached. */
    private val trailer = ByteArray(marker.size)
    private var trailerLen = 0

    /** Fraction complete, or null when the device never announced a length. */
    val progress: Float?
        get() = if (expectedBytes > 0) (receivedBytes.toDouble() / expectedBytes).toFloat() else null

    /**
     * Feeds one packet.
     *
     * @return the state after consuming [chunk]. Once it is not [State.RECEIVING],
     *   further calls are ignored.
     */
    fun onChunk(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset): State {
        if (state != State.RECEIVING) return state
        if (length <= 0) return state

        if (expectedBytes > 0) consumeWithKnownLength(chunk, offset, length)
        else consumeWithUnknownLength(chunk, offset, length)

        return state
    }

    /**
     * Supplies the length from a late `AA_DEV&U&LEN`.
     *
     * Only meaningful on [Mr20TransferChannel.BLE]: there, consumption is identical
     * whether or not the length is known (every byte is file content), so learning
     * it late only changes *when* the transfer completes. On Wi-Fi the caller opens
     * the socket after the announcement, so the length is always known up front and
     * a late announcement would invalidate the trailer hold-back accounting.
     */
    fun announceLength(length: Long): State {
        if (state != State.RECEIVING) return state
        require(channel == Mr20TransferChannel.BLE || receivedBytes == 0L) {
            "announceLength is only supported before any Wi-Fi bytes are consumed"
        }
        if (expectedBytes > 0 && expectedBytes != length) {
            failure = "device announced $length bytes after already announcing $expectedBytes"
            state = State.FAILED
            return state
        }
        expectedBytes = length
        if (length in 1..receivedBytes) {
            state = if (receivedBytes == length) State.COMPLETE else {
                failure = "received $receivedBytes bytes before the device announced only $length"
                State.FAILED
            }
        }
        return state
    }

    /**
     * Called when the command channel reports `AA_DEV&OFF`.
     *
     * On BLE with no announced length this is the only completion signal. When a
     * length *was* announced, an `AA_DEV&OFF` that arrives early means the device
     * gave up mid-file, and we surface that as a failure rather than silently
     * writing a truncated recording.
     */
    fun onDeviceFinished(): State {
        if (state != State.RECEIVING) return state
        state = if (expectedBytes > 0 && receivedBytes < expectedBytes) {
            failure = "device sent AA_DEV&OFF after $receivedBytes of $expectedBytes bytes"
            State.FAILED
        } else {
            flushHoldback()
            State.COMPLETE
        }
        return state
    }

    /** Called on `AA_DEV&SHUT`, `AA_DEV&U&ERR`, or a link drop. */
    fun abort(reason: String): State {
        if (state == State.RECEIVING) {
            failure = reason
            state = State.FAILED
        }
        return state
    }

    // -- length known: count bytes, then verify the trailer --------------------

    private fun consumeWithKnownLength(chunk: ByteArray, offset: Int, length: Int) {
        val remaining = expectedBytes - receivedBytes
        val fileBytes = minOf(remaining, length.toLong()).toInt()

        if (fileBytes > 0) {
            sink(chunk, offset, fileBytes)
            receivedBytes += fileBytes
        }

        val extraOffset = offset + fileBytes
        val extraLen = length - fileBytes
        if (extraLen > 0) {
            if (expectsMarker) {
                appendTrailer(chunk, extraOffset, extraLen)
            } else {
                // BLE announced a length and then kept sending. Trust the length:
                // the file is exactly [expectedBytes] long and the surplus is
                // the card's live audio stream, which shares this characteristic.
                surplusBytes += extraLen
            }
        }

        if (receivedBytes >= expectedBytes) {
            if (!expectsMarker) {
                state = State.COMPLETE
            } else if (trailerLen >= marker.size) {
                state = if (trailerMatches()) State.COMPLETE else {
                    failure = "Wi-Fi trailer mismatch: expected ${marker.toHex()}, " +
                        "got ${trailer.copyOf(trailerLen).toHex()}"
                    State.FAILED
                }
            }
            // else: all file bytes in, still waiting for the 5-byte trailer.
        }
    }

    private fun appendTrailer(chunk: ByteArray, offset: Int, length: Int) {
        var i = 0
        while (i < length && trailerLen < trailer.size) {
            trailer[trailerLen++] = chunk[offset + i]
            i++
        }
        if (i < length) {
            failure = "device sent ${length - i} byte(s) past the Wi-Fi trailer"
            state = State.FAILED
        }
    }

    private fun trailerMatches(): Boolean =
        trailerLen == marker.size && marker.indices.all { trailer[it] == marker[it] }

    // -- length unknown: hold back a trailer-sized tail ------------------------

    /**
     * Without an announced length we cannot know whether the last five bytes seen
     * are file content or the trailer, so we never emit the final `marker.size`
     * bytes until either more data arrives (proving they were content) or the
     * stream ends (proving they were the trailer).
     */
    private fun consumeWithUnknownLength(chunk: ByteArray, offset: Int, length: Int) {
        if (!expectsMarker) {
            // BLE: no trailer to worry about, completion comes from AA_DEV&OFF.
            sink(chunk, offset, length)
            receivedBytes += length
            return
        }

        // Concatenate holdback + chunk, emit everything but the last marker.size.
        val total = holdbackLen + length
        if (total <= marker.size) {
            System.arraycopy(chunk, offset, holdback, holdbackLen, length)
            holdbackLen = total
            return
        }

        val emitCount = total - marker.size
        val combined = ByteArray(total)
        System.arraycopy(holdback, 0, combined, 0, holdbackLen)
        System.arraycopy(chunk, offset, combined, holdbackLen, length)

        sink(combined, 0, emitCount)
        receivedBytes += emitCount

        System.arraycopy(combined, emitCount, holdback, 0, marker.size)
        holdbackLen = marker.size

        // Eagerly finish if the tail is the trailer: on Wi-Fi the socket may stay
        // open after the last file, so we cannot wait for EOF.
        if (holdbackMatchesMarker()) {
            holdbackLen = 0
            state = State.COMPLETE
        }
    }

    private fun holdbackMatchesMarker(): Boolean =
        holdbackLen == marker.size && marker.indices.all { holdback[it] == marker[it] }

    /** On BLE completion, the withheld tail is real file content. */
    private fun flushHoldback() {
        if (holdbackLen > 0 && !holdbackMatchesMarker()) {
            sink(holdback, 0, holdbackLen)
            receivedBytes += holdbackLen
        }
        holdbackLen = 0
    }
}

internal fun ByteArray.toHex(): String =
    joinToString(" ") { "%02X".format(it) }
