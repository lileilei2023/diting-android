package com.diting.protocol.mr20

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream

/**
 * End-to-end tests of the session layer against [FakeMr20Device].
 *
 * `UnconfinedTestDispatcher` is used throughout so the client's notification
 * pumps run eagerly; on the default dispatcher the fake's replies would sit in
 * the queue while the client waits for them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Mr20ClientTest {

    /**
     * Runs [body] against a started client talking to [device].
     *
     * The client's notification pumps are long-lived collectors, so they get a
     * scope of their own that is cancelled when the test ends. Handing them the
     * `runTest` scope instead would make `runTest` wait forever for children that
     * never complete — which is also the contract real callers must honour:
     * whoever owns the scope passed to [Mr20Client] owns the pumps' lifetime.
     */
    private fun clientTest(
        device: FakeMr20Device = FakeMr20Device(),
        timeouts: Mr20Timeouts = Mr20Timeouts(),
        body: suspend TestScope.(FakeMr20Device, Mr20Client) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val pumpScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        val client = Mr20Client(device.commandTransport, device.dataTransport, pumpScope, timeouts)
        client.start()
        try {
            body(device, client)
        } finally {
            pumpScope.cancel()
        }
    }

    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private val sampleFiles = mapOf(
        "2025-08-13" to listOf(
            Mr20File("2025-08-13", "REC0001.MP3", 325, 1_310_720),
            Mr20File("2025-08-13", "REC0002.MP3", 60, 240_000),
        ),
        "2025-08-14" to listOf(
            Mr20File("2025-08-14", "REC0003.MP3", 12, 48_000),
        ),
    )

    // -- pairing --------------------------------------------------------------

    @Test
    fun `authenticate sends the key and reports the truncated form`() =
        clientTest(FakeMr20Device(expectedKey = "0123456789ABCDEF")) { device, client ->
            val stored = client.authenticate("0123456789ABCDEFGHIJ")

            assertEquals("0123456789ABCDEF", stored)
            assertTrue(client.isAuthenticated)
            assertEquals("AA_BLE&SK&0123456789ABCDEF", device.written.single())
        }

    @Test
    fun `a wrong key is surfaced as PairingRejected`() =
        clientTest(FakeMr20Device(expectedKey = "correctkey000000")) { _, client ->
            assertThrows<Mr20Exception.PairingRejected> { client.authenticate("wrongkey00000000") }
            assertFalse(client.isAuthenticated)
        }

    @Test
    fun `commands before pairing report NotAuthenticated, not a bare timeout`() =
        // The real device stays silent until it has the key. A generic timeout
        // would send the user hunting for a BLE fault that does not exist.
        clientTest(
            FakeMr20Device(expectedKey = "k"),
            Mr20Timeouts(simpleCommand = 50),
        ) { _, client ->
            assertThrows<Mr20Exception.NotAuthenticated> { client.queryBattery() }
        }

    // -- simple queries -------------------------------------------------------

    @Test
    fun `reads battery, space, versions, time and mode`() = clientTest { _, client ->
        client.authenticate("key")

        assertEquals(87, client.queryBattery())
        val space = client.querySpace()
        assertEquals(1024L, space.freeMb)
        assertEquals(8192L, space.totalMb)
        assertEquals(7168L, space.usedMb)
        assertEquals("1.0", client.queryFirmwareVersion())
        assertEquals("V0001", client.queryWifiFirmwareVersion())
        assertEquals("50c0f04b790c", client.queryMacAddress())
        assertEquals(2025, client.queryDeviceTime().year)
        assertEquals(Mr20RecordMode.CONVERSATION, client.queryRecordMode())
    }

    @Test
    fun `readDeviceInfo gathers everything in one pass`() = clientTest { _, client ->
        client.authenticate("key")

        val info = client.readDeviceInfo()

        assertEquals("1.0", info.firmwareVersion)
        assertEquals("50c0f04b790c", info.macAddress)
        assertEquals(87, info.batteryPercent)
        assertEquals(1024L, info.freeMb)
        assertEquals(Mr20RecordMode.CONVERSATION, info.recordMode)
        assertFalse(info.isRecording)
    }

    @Test
    fun `start and stop recording`() = clientTest { _, client ->
        client.authenticate("key")

        assertFalse(client.queryRecordingState())
        assertEquals("REC0042.MP3", client.startRecording())
        assertTrue(client.queryRecordingState())
        client.stopRecording()
        assertFalse(client.queryRecordingState())
    }

    // -- enumerations ---------------------------------------------------------

    @Test
    fun `lists directories and files across the device`() =
        clientTest(FakeMr20Device(files = sampleFiles)) { _, client ->
            client.authenticate("key")

            assertEquals(listOf("2025-08-13", "2025-08-14"), client.listDirectories())

            val files = client.listFiles("2025-08-13")
            assertEquals(2, files.size)
            assertEquals("REC0001.MP3", files[0].name)
            assertEquals(325L, files[0].durationSeconds)
            assertEquals(1_310_720L, files[0].sizeBytes)
            assertEquals("2025-08-13/REC0001.MP3", files[0].path)

            assertEquals(3, client.listAllFiles().size)
        }

    @Test
    fun `a directory enumeration whose count does not match is rejected`() =
        // Dropped notifications must not look like "the device has fewer files",
        // or a sync-then-delete flow would destroy recordings.
        clientTest(
            FakeMr20Device(files = sampleFiles, directoryCountOverride = 5)
        ) { _, client ->
            client.authenticate("key")

            val error = assertThrows<Mr20Exception.DeviceError> { client.listDirectories() }
            assertTrue(error.message!!.contains("got 2 of 5"))
        }

    @Test
    fun `a file enumeration whose count does not match is rejected`() =
        clientTest(
            FakeMr20Device(files = sampleFiles, fileCountOverride = 9)
        ) { _, client ->
            client.authenticate("key")

            val error = assertThrows<Mr20Exception.DeviceError> { client.listFiles("2025-08-13") }
            assertTrue(error.message!!.contains("got 2 of 9"))
        }

    // -- file sync ------------------------------------------------------------

    @Test
    fun `pulls a file over BLE byte for byte`() {
        val body = payload(4000)
        clientTest(
            FakeMr20Device(
                files = sampleFiles,
                fileBytes = mapOf("2025-08-13/REC0001.MP3" to body),
            )
        ) { device, client ->
            client.authenticate("key")

            val out = ByteArrayOutputStream()
            val written = client.pullFileOverBle("2025-08-13", "REC0001.MP3") { b, o, l ->
                out.write(b, o, l)
            }

            assertEquals(4000L, written)
            assertArrayEquals(body, out.toByteArray())
            assertTrue(device.written.contains("AA_BLE&U&2025-08-13&REC0001.MP3"))
        }
    }

    @Test
    fun `resuming a partial file asks only for the remainder`() {
        val body = payload(4000)
        clientTest(
            FakeMr20Device(fileBytes = mapOf("2025-08-13/REC0001.MP3" to body))
        ) { device, client ->
            client.authenticate("key")

            val out = ByteArrayOutputStream()
            val written = client.pullFileOverBle(
                "2025-08-13", "REC0001.MP3", haveBytes = 1500,
            ) { b, o, l -> out.write(b, o, l) }

            assertEquals(2500L, written)
            assertArrayEquals(body.copyOfRange(1500, 4000), out.toByteArray())
            assertTrue(device.written.contains("AA_BLE&U&2025-08-13&REC0001.MP3&1500"))
        }
    }

    @Test
    fun `a missing file fails instead of hanging`() = clientTest { _, client ->
        client.authenticate("key")

        assertThrows<Mr20Exception.TransferFailed> {
            client.pullFileOverBle("2025-08-13", "NOPE.MP3") { _, _, _ -> }
        }
    }

    @Test
    fun `progress is reported while pulling`() =
        clientTest(FakeMr20Device(fileBytes = mapOf("d/f.mp3" to payload(1000)))) { _, client ->
            client.authenticate("key")

            val seen = mutableListOf<Float?>()
            client.pullFileOverBle("d", "f.mp3", onProgress = { seen += it }) { _, _, _ -> }

            assertTrue(seen.isNotEmpty())
            assertEquals(1.0f, seen.last())
        }

    @Test
    fun `live audio is separated from file data`() = clientTest { device, client ->
        // The device multiplexes both onto characteristic …a1. Getting the demux
        // wrong means MP3 frames land inside a synced file.
        client.authenticate("key")

        val heard = mutableListOf<ByteArray>()
        val job = launch { client.liveAudio.collect { heard += it } }

        device.emitAudio(byteArrayOf(1, 2, 3))
        device.emitAudio(byteArrayOf(4, 5, 6))

        assertEquals(2, heard.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), heard[0])
        assertArrayEquals(byteArrayOf(4, 5, 6), heard[1])
        job.cancel()
    }

    @Test
    fun `audio frames during a file sync go to the file, not the live stream`() {
        val body = payload(600)
        clientTest(FakeMr20Device(fileBytes = mapOf("d/f.mp3" to body))) { _, client ->
            client.authenticate("key")

            val heard = mutableListOf<ByteArray>()
            val job = launch { client.liveAudio.collect { heard += it } }

            val out = ByteArrayOutputStream()
            client.pullFileOverBle("d", "f.mp3") { b, o, l -> out.write(b, o, l) }

            assertArrayEquals(body, out.toByteArray())
            assertTrue(heard.isEmpty(), "file bytes leaked into the live audio stream")
            job.cancel()
        }
    }

    // -- unsolicited events ---------------------------------------------------

    @Test
    fun `unsolicited events reach subscribers`() = clientTest { device, client ->
        val seen = mutableListOf<Mr20Event>()
        val job = launch { client.events.collect { seen += it } }

        device.emit("AA_DEV&RT&REC0012.MP3&95")
        device.emit("AA_EV&REC&ERR")
        device.emit("AA_DEV&DISK&ERR")

        assertEquals(3, seen.size)
        val inProgress = seen[0] as Mr20Event.RecordingInProgress
        assertEquals("REC0012.MP3", inProgress.fileName)
        assertEquals(95L, inProgress.elapsedSeconds)
        assertTrue(seen[1] is Mr20Event.RecordingError)
        assertTrue(seen[2] is Mr20Event.DiskFull)
        job.cancel()
    }

    // -- Wi-Fi ----------------------------------------------------------------

    @Test
    fun `openWifiAndAwaitAp polls until the AP accepts clients`() {
        val device = FakeMr20Device().apply {
            wifiStates = mutableListOf(
                Mr20WifiState.WAITING_TO_OPEN,
                Mr20WifiState.WAITING_TO_OPEN,
                Mr20WifiState.NOT_CONNECTED,
            )
        }
        clientTest(device) { d, client ->
            client.authenticate("key")

            val credentials = client.openWifiAndAwaitAp()

            assertEquals("MR20_A1B2", credentials.ssid)
            assertEquals("12345678", credentials.password)
            // Polled rather than assumed: WIFIO once, then WIFIS until state 2.
            assertEquals(1, d.written.count { it == "AA_BLE&WIFIO" })
            assertTrue(d.written.count { it == "AA_BLE&WIFIS" } >= 3)
        }
    }

    @Test
    fun `closeWifi refuses while the device is changing its password`() {
        val device = FakeMr20Device().apply {
            wifiStates = mutableListOf(Mr20WifiState.CHANGING_PASSWORD)
        }
        clientTest(device) { d, client ->
            client.authenticate("key")

            val error = assertThrows<Mr20Exception.DeviceError> { client.closeWifi() }

            assertTrue(error.message!!.contains("CHANGING_PASSWORD"))
            assertTrue(d.written.none { it == "AA_BLE&WIFIC" })
        }
    }

    @Test
    fun `closeWifi is a no-op when Wi-Fi is already off`() {
        val device = FakeMr20Device().apply { wifiStates = mutableListOf(Mr20WifiState.OFF) }
        clientTest(device) { d, client ->
            client.authenticate("key")
            client.closeWifi()
            assertTrue(d.written.none { it == "AA_BLE&WIFIC" })
        }
    }

    @Test
    fun `requesting a file over Wi-Fi returns the announced length`() =
        clientTest(FakeMr20Device(fileBytes = mapOf("d/f.mp3" to payload(2048)))) { _, client ->
            client.authenticate("key")

            val begin = client.requestFileOverWifi("d", "f.mp3")

            assertEquals(2048L, begin.lengthBytes)
            assertEquals(Mr20TransferChannel.WIFI, begin.channel)
        }

    // -- OTA ------------------------------------------------------------------

    @Test
    fun `OTA sends 244-byte frames between the begin and finish commands`() =
        clientTest { device, client ->
            client.authenticate("key")

            val session = Mr20OtaSession(payload(600), Mr20OtaSession.Target.MCU)
            assertEquals(3, session.frameCount)

            client.performOta(session, frameDelayMs = 8)

            assertTrue(device.written.contains("AA_BLE&OTA&000600"))
            assertTrue(device.written.contains("AA_BLE&OT&OVER"))

            val frames = device.written
                .dropWhile { it != "AA_BLE&OTA&000600" }.drop(1)
                .takeWhile { it != "AA_BLE&OT&OVER" }
            assertEquals(3, frames.size)
            assertEquals(244, frames[0].length)
            assertEquals(244, frames[1].length)
            assertEquals(112, frames[2].length)
        }

    @Test
    fun `OTA reports progress per frame`() = clientTest { _, client ->
        client.authenticate("key")

        val seen = mutableListOf<Pair<Int, Int>>()
        client.performOta(
            Mr20OtaSession(payload(600), Mr20OtaSession.Target.MCU),
            frameDelayMs = 8,
        ) { sent, total -> seen += sent to total }

        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
    }

    @Test
    fun `OTA rejects a frame delay below the spec floor`() = clientTest { _, client ->
        client.authenticate("key")

        assertThrows<IllegalArgumentException> {
            client.performOta(Mr20OtaSession(payload(10), Mr20OtaSession.Target.MCU), 1)
        }
    }

    @Test
    fun `the Wi-Fi OTA command carries the WIFI keyword`() = clientTest { device, client ->
        client.authenticate("key")

        client.performOta(
            Mr20OtaSession(payload(300), Mr20OtaSession.Target.WIFI),
            frameDelayMs = 8,
        )

        assertTrue(device.written.contains("AA_BLE&OTA&WIFI&000300"))
    }
}
