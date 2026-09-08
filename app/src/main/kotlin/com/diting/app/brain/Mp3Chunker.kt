package com.diting.app.brain

import java.io.File
import java.io.RandomAccessFile

/**
 * Cuts an MP3 into pieces the brain can transcribe in one request.
 *
 * Two limits, both measured against the deployed brain on 2026-09-07:
 *  * the ASR model (qwen3-asr-flash) rejects clips past about five minutes with
 *    "The audio is too long" — 300 s passed, 600 s failed;
 *  * the brain relays audio to the provider over a ~190 kbit/s link with a
 *    two-minute write timeout, so a whole 10 MB recording times out.
 * 1.15 MB is 4.8 min at the MR20's 32 kbps (2.4 min at 64 kbps) and a ~50 s relay.
 *
 * Cuts land on the next MPEG frame sync after the target size, so every piece
 * starts on a frame boundary and decodes cleanly. Pieces are written next to
 * the source and are the caller's to delete.
 */
object Mp3Chunker {

    const val DEFAULT_CHUNK_BYTES = 1_150_000L

    data class Piece(val index: Int, val total: Int, val file: File, val startByte: Long, val endByte: Long)

    /** Byte offsets [start, end) of each piece, cut on frame syncs. */
    fun plan(source: File, chunkBytes: Long = DEFAULT_CHUNK_BYTES): List<LongRange> {
        val size = source.length()
        if (size <= chunkBytes) return listOf(0L until size)
        val cuts = mutableListOf(0L)
        RandomAccessFile(source, "r").use { raf ->
            var next = chunkBytes
            val window = ByteArray(64 * 1024)
            while (next < size) {
                raf.seek(next)
                val read = raf.read(window)
                val sync = if (read > 1) findSync(window, read) else -1
                val cut = if (sync >= 0) next + sync else next
                if (cut >= size) break
                cuts += cut
                next = cut + chunkBytes
            }
        }
        return cuts.mapIndexed { i, start ->
            val end = if (i + 1 < cuts.size) cuts[i + 1] else size
            start until end
        }
    }

    /** Writes each planned range to `<name>.partNN.mp3` beside the source. */
    fun split(source: File, chunkBytes: Long = DEFAULT_CHUNK_BYTES): List<Piece> {
        val ranges = plan(source, chunkBytes)
        if (ranges.size == 1) return listOf(Piece(0, 1, source, 0L, source.length()))
        val buffer = ByteArray(256 * 1024)
        return RandomAccessFile(source, "r").use { raf ->
            ranges.mapIndexed { i, range ->
                val out = File(source.parentFile, "${source.nameWithoutExtension}.part%02d.mp3".format(i + 1))
                raf.seek(range.first)
                var remaining = range.last - range.first + 1
                out.outputStream().use { os ->
                    while (remaining > 0) {
                        val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        os.write(buffer, 0, n)
                        remaining -= n
                    }
                }
                Piece(i, ranges.size, out, range.first, range.last + 1)
            }
        }
    }

    /**
     * First MPEG audio frame sync (11 set bits) that also carries a sane header:
     * MPEG version and layer fields not reserved, bitrate index not 0/15.
     */
    private fun findSync(buf: ByteArray, len: Int): Int {
        var i = 0
        while (i + 3 < len) {
            val b0 = buf[i].toInt() and 0xFF
            val b1 = buf[i + 1].toInt() and 0xFF
            val b2 = buf[i + 2].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                val version = (b1 shr 3) and 0x03
                val layer = (b1 shr 1) and 0x03
                val bitrateIndex = (b2 shr 4) and 0x0F
                val sampleIndex = (b2 shr 2) and 0x03
                if (version != 1 && layer != 0 && bitrateIndex != 0 && bitrateIndex != 15 && sampleIndex != 3) {
                    return i
                }
            }
            i++
        }
        return -1
    }
}
