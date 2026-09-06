package com.diting.protocol.mr20

import com.diting.protocol.common.ByteStreamTransport
import com.diting.protocol.common.PacketTransport
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An in-memory MR20 that speaks the real wire protocol.
 *
 * It exists so the session layer can be exercised end to end without hardware:
 * the commands it accepts and the replies it produces are transcribed from
 * 《MR20通信协议_20260721.xlsx》, so a test that passes against this fake is a test
 * against the documented contract.
 *
 * Deliberately faithful about the awkward parts:
 *  * every command except `AA_BLE&SK&…` is ignored until the key is accepted;
 *  * enumerations stream N entries and then a count;
 *  * BLE file data arrives on the *audio* characteristic, not the command one;
 *  * `AA_BLE&WIFI&CH` is answered with silence.
 */
class FakeMr20Device(
    private val expectedKey: String? = null,
    private val files: Map<String, List<Mr20File>> = emptyMap(),
    private val fileBytes: Map<String, ByteArray> = emptyMap(),
    /**
     * Overrides the `AA_DEV&DIRS_SUM&LEN` terminator so a test can simulate the
     * device claiming more directories than it streamed — which is what a dropped
     * notification looks like from the app's side.
     */
    private val directoryCountOverride: Int? = null,
    /** Same, for the `AA_DEV&LIST&LEN` terminator. */
    private val fileCountOverride: Int? = null,
) {

    private val commandOut = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val dataOut = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** Every frame the app wrote, in order — lets tests assert on the wire. */
    val written = mutableListOf<String>()

    var authenticated = false
        private set

    var batteryPercent = 87
    var recording = false
    var wifiStates: MutableList<Mr20WifiState> = mutableListOf(Mr20WifiState.NOT_CONNECTED)

    /** Frame size used when streaming file data; mirrors a 247-byte ATT MTU. */
    var dataFrameSize = 244

    val commandTransport: PacketTransport = object : PacketTransport {
        override val incoming = commandOut.asSharedFlow()
        override val isConnected = true
        override suspend fun write(frame: ByteArray) = handle(frame)
    }

    val dataTransport: ByteStreamTransport = object : ByteStreamTransport {
        override val incoming = dataOut.asSharedFlow()
        override val isConnected = true
    }

    /** Pushes an unsolicited event, e.g. `AA_DEV&DISK&ERR`. */
    suspend fun emit(message: String) {
        commandOut.emit(message.toByteArray(Charsets.US_ASCII))
    }

    /** Pushes a live MP3 frame on the audio characteristic. */
    suspend fun emitAudio(bytes: ByteArray) = dataOut.emit(bytes)

    private suspend fun reply(message: String) = emit(message)

    private suspend fun handle(frame: ByteArray) {
        val text = frame.toString(Charsets.US_ASCII)
        written += text

        val f = text.split("&")
        if (f.firstOrNull() != Mr20Protocol.APP_PREFIX) return
        val tag = f.getOrNull(1) ?: return

        // Pairing is the only thing that works before pairing.
        if (tag == "SK") {
            val arg = f.getOrNull(2)
            if (arg == "RESET") {
                authenticated = false
                return
            }
            authenticated = expectedKey == null || arg == expectedKey
            reply(if (authenticated) "AA_DEV&SK&OK" else "AA_DEV&SK&ERR")
            return
        }
        if (!authenticated) return // the real device stays silent, and so do we

        when (tag) {
            "STE" -> reply("AA_DEV&STE&${if (recording) 1 else 0}")
            "STA" -> { recording = true; reply("AA_DEV&STA&REC0042.MP3") }
            "STO" -> { recording = false; reply("AA_DEV&STO") }
            "BAT" -> reply("AA_DEV&BAT&$batteryPercent")
            "SPACE" -> reply("AA_DEV&SPA&1024&8192")
            "FW" -> reply("AA_DEV&FW&1.0")
            "WF" -> reply("AA_DEV&WF&V0001")
            "MAC" -> reply("AA_DEV&MAC&50c0f04b790c")
            "GT" -> reply("AA_DEV&CT&20250909105012")
            "T" -> reply("AA_DEV&T&OK")
            "REC" -> reply("AA_DEV&REC&CON")
            "KBPS" -> reply("AA_DEV&KBPS&OK")
            "GET" -> reply("AA_DEV&USB&0")
            "USB" -> reply("AA_DEV&USB&${f.getOrNull(2)}")
            "SHAKE" -> Unit

            "LIST_DIRS" -> {
                files.keys.forEach { reply("AA_DEV&DIRS&$it") }
                reply("AA_DEV&DIRS_SUM&${directoryCountOverride ?: files.size}")
            }

            "LIST" -> {
                val dir = f.getOrNull(2).orEmpty()
                val entries = files[dir].orEmpty()
                entries.forEach {
                    reply("AA_DEV&F&${it.directory}&${it.name}&${it.durationSeconds}&${it.sizeBytes}")
                }
                reply("AA_DEV&LIST&${fileCountOverride ?: entries.size}")
            }

            "U" -> streamFileOverBle(f)
            "W" -> {
                val body = lookupFile(f) ?: return reply("AA_DEV&U&ERR")
                reply("AA_DEV&W&${body.size}")
            }

            "D" -> reply("AA_DEV&D")
            "SHUT" -> reply("AA_DEV&SHUT")

            "WIFIO" -> reply("AA_DEV&WIFIO")
            "WIFIC" -> reply("AA_DEV&WIFIC")
            "WIFI" -> when (f.getOrNull(2)) {
                // AA_BLE&WIFI&CH gets no reply at all, by design.
                "CH" -> Unit
                null -> reply("AA_DEV&WIFI&MR20_A1B2&12345678")
                else -> Unit
            }

            "WIFIS" -> {
                val state = if (wifiStates.size > 1) wifiStates.removeAt(0) else wifiStates.first()
                reply("AA_DEV&WIFIS&${state.code}")
            }

            "OTA" -> reply("AA_DEV&OTA")
            "OT" -> reply("AA_DEV&OT&OVER")

            else -> Unit
        }
    }

    private fun lookupFile(f: List<String>): ByteArray? {
        val dir = f.getOrNull(2) ?: return null
        val name = f.getOrNull(3) ?: return null
        return fileBytes["$dir/$name"]
    }

    private suspend fun streamFileOverBle(f: List<String>) {
        val body = lookupFile(f) ?: return reply("AA_DEV&U&ERR")

        // Resume form: AA_BLE&U&DIR&NAME&SIZE means the app already has SIZE bytes.
        val alreadyHave = f.getOrNull(4)?.toIntOrNull() ?: 0
        val remaining = body.copyOfRange(alreadyHave.coerceAtMost(body.size), body.size)

        reply("AA_DEV&U&${remaining.size}")
        var offset = 0
        while (offset < remaining.size) {
            val end = minOf(offset + dataFrameSize, remaining.size)
            dataOut.emit(remaining.copyOfRange(offset, end))
            offset = end
        }
        reply("AA_DEV&OFF")
    }
}
