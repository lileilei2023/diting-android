package com.diting.protocol.mr20

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * The Wi-Fi trailer straddling a packet boundary is the single most likely way to
 * corrupt a synced recording, so it gets the most attention here.
 */
class Mr20FileReceiverTest {

    private val out = ByteArrayOutputStream()
    private fun sink(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)

    private val marker = Mr20Protocol.WIFI_EOF_MARKER

    private fun payload(size: Int): ByteArray =
        ByteArray(size) { (it % 251).toByte() }

    // -- BLE ------------------------------------------------------------------

    @Test
    @DisplayName("BLE: keeps every byte and completes on the announced length")
    fun bleKeepsEveryByte() {
        val data = payload(1000)
        val r = Mr20FileReceiver(1000, Mr20TransferChannel.BLE, ::sink)

        data.toList().chunked(244).forEach { chunk ->
            r.onChunk(chunk.toByteArray())
        }

        assertEquals(Mr20FileReceiver.State.COMPLETE, r.state)
        assertEquals(1000L, r.receivedBytes)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    @DisplayName("BLE: AA_DEV&OFF arriving early is a failure, not a truncated file")
    fun bleEarlyOffFails() {
        val r = Mr20FileReceiver(1000, Mr20TransferChannel.BLE, ::sink)
        r.onChunk(payload(400))

        assertEquals(Mr20FileReceiver.State.FAILED, r.onDeviceFinished())
        assertNotNull(r.failure)
        assertTrue(r.failure!!.contains("400 of 1000"))
    }

    @Test
    @DisplayName("BLE: with no announced length, AA_DEV&OFF completes the file")
    fun bleUnknownLengthCompletesOnOff() {
        val data = payload(300)
        val r = Mr20FileReceiver(0, Mr20TransferChannel.BLE, ::sink)

        r.onChunk(data)
        assertEquals(Mr20FileReceiver.State.RECEIVING, r.state)
        assertEquals(Mr20FileReceiver.State.COMPLETE, r.onDeviceFinished())
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    @DisplayName("BLE: bytes past the announced length are a protocol error")
    fun bleOverrunFails() {
        val r = Mr20FileReceiver(10, Mr20TransferChannel.BLE, ::sink)
        assertEquals(Mr20FileReceiver.State.FAILED, r.onChunk(payload(20)))
        assertEquals(10, out.toByteArray().size)
    }

    // -- Wi-Fi, length known --------------------------------------------------

    @Test
    @DisplayName("Wi-Fi: the 5-byte trailer is stripped, not written to the file")
    fun wifiStripsTrailer() {
        val data = payload(512)
        val r = Mr20FileReceiver(512, Mr20TransferChannel.WIFI, ::sink)

        r.onChunk(data + marker)

        assertEquals(Mr20FileReceiver.State.COMPLETE, r.state)
        assertEquals(512L, r.receivedBytes)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    @DisplayName("Wi-Fi: the trailer split across two packets is still stripped")
    fun wifiTrailerSplitAcrossPackets() {
        val data = payload(512)
        val stream = data + marker
        val r = Mr20FileReceiver(512, Mr20TransferChannel.WIFI, ::sink)

        // Cut two bytes into the trailer: [.. data .. BA 5A] [02 8F 04]
        val cut = 512 + 2
        r.onChunk(stream.copyOfRange(0, cut))
        assertEquals(Mr20FileReceiver.State.RECEIVING, r.state)
        r.onChunk(stream.copyOfRange(cut, stream.size))

        assertEquals(Mr20FileReceiver.State.COMPLETE, r.state)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    @DisplayName("Wi-Fi: a packet boundary inside the file body changes nothing")
    fun wifiArbitraryChunking() {
        val data = payload(4096)
        val stream = data + marker
        val r = Mr20FileReceiver(4096, Mr20TransferChannel.WIFI, ::sink)

        var i = 0
        var size = 1
        while (i < stream.size) {
            val end = minOf(i + size, stream.size)
            r.onChunk(stream.copyOfRange(i, end))
            i = end
            size = (size * 3 % 1500) + 1 // irregular, prime-ish chunk sizes
        }

        assertEquals(Mr20FileReceiver.State.COMPLETE, r.state)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    @DisplayName("Wi-Fi: a wrong trailer fails instead of silently truncating")
    fun wifiBadTrailerFails() {
        val data = payload(64)
        val r = Mr20FileReceiver(64, Mr20TransferChannel.WIFI, ::sink)

        r.onChunk(data + byteArrayOf(0xBA.toByte(), 0x5A, 0x02, 0x8F.toByte(), 0x05))

        assertEquals(Mr20FileReceiver.State.FAILED, r.state)
        assertTrue(r.failure!!.contains("trailer mismatch"))
    }

    @Test
    @DisplayName("Wi-Fi: file bytes that happen to equal the trailer are kept")
    fun wifiMarkerBytesInsideFileAreKept() {
        // The marker is only meaningful at the very end. A file whose body contains
        // the same five bytes must survive intact.
        val data = payload(100) + marker + payload(100)
        val r = Mr20FileReceiver(data.size.toLong(), Mr20TransferChannel.WIFI, ::sink)

        r.onChunk(data + marker)

        assertEquals(Mr20FileReceiver.State.COMPLETE, r.state)
        assertArrayEquals(data, out.toByteArray())
    }

    // -- Wi-Fi, length unknown ------------------------------------------------

    @Test
    @DisplayName("Wi-Fi with no announced length: completes when the trailer appears")
    fun wifiUnknownLengthCompletesOnMarker() {
        val data = payload(700)
        val stream = data + marker
        val r = Mr20FileReceiver(0, Mr20TransferChannel.WIFI, ::sink)

        stream.toList().chunked(64).forEach { r.onChunk(it.toByteArray()) }

        assertEquals(Mr20FileReceiver.State.COMPLETE, r.state)
        assertEquals(700L, r.receivedBytes)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    @DisplayName("Wi-Fi with no announced length: holds back a possible trailer")
    fun wifiUnknownLengthHoldsBackTail() {
        val r = Mr20FileReceiver(0, Mr20TransferChannel.WIFI, ::sink)

        r.onChunk(payload(20))

        // The last five bytes are withheld until we know they are not the trailer.
        assertEquals(15, out.toByteArray().size)
        assertEquals(15L, r.receivedBytes)
    }

    @Test
    @DisplayName("Wi-Fi with no announced length: a whole file shorter than the trailer")
    fun wifiUnknownLengthTinyStream() {
        val r = Mr20FileReceiver(0, Mr20TransferChannel.WIFI, ::sink)
        r.onChunk(marker)
        // Nothing but the trailer: an empty file, and no bytes emitted.
        assertEquals(0, out.toByteArray().size)
    }

    // -- lifecycle ------------------------------------------------------------

    @Test
    fun `abort is sticky and reports the reason`() {
        val r = Mr20FileReceiver(100, Mr20TransferChannel.BLE, ::sink)
        r.onChunk(payload(10))
        r.abort("device sent AA_DEV&SHUT")

        assertEquals(Mr20FileReceiver.State.FAILED, r.state)
        assertEquals("device sent AA_DEV&SHUT", r.failure)

        // Later packets are ignored rather than resurrecting the transfer.
        r.onChunk(payload(10))
        assertEquals(Mr20FileReceiver.State.FAILED, r.state)
        assertEquals(10, out.toByteArray().size)
    }

    @Test
    fun `progress is null when the device announced no length`() {
        assertEquals(null, Mr20FileReceiver(0, Mr20TransferChannel.BLE, ::sink).progress)

        val r = Mr20FileReceiver(200, Mr20TransferChannel.BLE, ::sink)
        r.onChunk(payload(50))
        assertEquals(0.25f, r.progress)
    }
}
