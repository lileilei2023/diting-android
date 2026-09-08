package com.diting.protocol.mr20

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Every string in this file is copied from 《MR20通信协议_20260721.xlsx》. If a test
 * here disagrees with the spreadsheet, the spreadsheet wins.
 */
class Mr20EventParserTest {

    private fun parse(s: String) = Mr20EventParser.parse(s)

    @Nested
    @DisplayName("pairing")
    inner class Pairing {
        @Test
        fun `accepts AA_DEV SK OK`() {
            assertInstanceOf(Mr20Event.SecretKeyAccepted::class.java, parse("AA_DEV&SK&OK"))
        }

        @Test
        fun `accepts AA_DEV SK ERR`() {
            assertInstanceOf(Mr20Event.SecretKeyRejected::class.java, parse("AA_DEV&SK&ERR"))
        }
    }

    @Nested
    @DisplayName("recording")
    inner class Recording {
        @ParameterizedTest
        @CsvSource("AA_DEV&STE&1,true", "AA_DEV&STE&0,false")
        fun `reads recording state`(wire: String, recording: Boolean) {
            val event = parse(wire) as Mr20Event.RecordingState
            assertEquals(recording, event.isRecording)
        }

        @Test
        fun `reads started file name`() {
            val event = parse("AA_DEV&STA&REC0012.MP3") as Mr20Event.RecordingStarted
            assertEquals("REC0012.MP3", event.fileName)
        }

        @Test
        fun `reads stopped`() {
            assertInstanceOf(Mr20Event.RecordingStopped::class.java, parse("AA_DEV&STO"))
        }

        @Test
        fun `reads in-progress name and elapsed seconds`() {
            val event = parse("AA_DEV&RT&REC0012.MP3&95") as Mr20Event.RecordingInProgress
            assertEquals("REC0012.MP3", event.fileName)
            assertEquals(95L, event.elapsedSeconds)
        }

        @Test
        fun `AA_EV REC ERR is a recording error`() {
            assertInstanceOf(Mr20Event.RecordingError::class.java, parse("AA_EV&REC&ERR"))
        }

        @Test
        fun `AA_DEV DISK ERR is disk full`() {
            assertInstanceOf(Mr20Event.DiskFull::class.java, parse("AA_DEV&DISK&ERR"))
        }

        @ParameterizedTest
        @CsvSource("AA_DEV&REC&CALL,CALL", "AA_DEV&REC&CON,CONVERSATION")
        fun `reads record mode`(wire: String, mode: Mr20RecordMode) {
            assertEquals(mode, (parse(wire) as Mr20Event.RecordMode).mode)
        }

        @ParameterizedTest
        @CsvSource("AA_DEV&KBPS&OK,true", "AA_DEV&KBPS&ERR,false")
        fun `reads bitrate result`(wire: String, ok: Boolean) {
            assertEquals(ok, (parse(wire) as Mr20Event.BitrateResult).success)
        }
    }

    @Nested
    @DisplayName("device info")
    inner class DeviceInfo {
        @Test
        fun `reads free and total space in MB`() {
            val event = parse("AA_DEV&SPA&1024&8192") as Mr20Event.Space
            assertEquals(1024L, event.freeMb)
            assertEquals(8192L, event.totalMb)
            assertEquals(7168L, event.usedMb)
        }

        @Test
        fun `reads battery percent`() {
            assertEquals(87, (parse("AA_DEV&BAT&87") as Mr20Event.Battery).percent)
        }

        @Test
        fun `clamps an out-of-range battery percent`() {
            assertEquals(100, (parse("AA_DEV&BAT&255") as Mr20Event.Battery).percent)
        }

        @Test
        fun `reads firmware version verbatim`() {
            assertEquals("1.0", (parse("AA_DEV&FW&1.0") as Mr20Event.FirmwareVersion).version)
        }

        @Test
        fun `reads wifi firmware version`() {
            assertEquals(
                "V0001",
                (parse("AA_DEV&WF&V0001") as Mr20Event.WifiFirmwareVersion).version,
            )
        }

        @Test
        fun `reads mac address`() {
            assertEquals(
                "50c0f04b790c",
                (parse("AA_DEV&MAC&50c0f04b790c") as Mr20Event.MacAddress).mac,
            )
        }

        @Test
        fun `reads device time and converts it`() {
            val event = parse("AA_DEV&CT&20250909105012") as Mr20Event.DeviceTime
            val t = event.toLocalDateTime()
            assertEquals(2025, t.year)
            assertEquals(9, t.monthValue)
            assertEquals(9, t.dayOfMonth)
            assertEquals(10, t.hour)
            assertEquals(50, t.minute)
            assertEquals(12, t.second)
        }

        @Test
        fun `rejects a malformed device time`() {
            assertInstanceOf(Mr20Event.Unknown::class.java, parse("AA_DEV&CT&2025090910"))
        }

        @Test
        fun `AA_DEV T OK is a time ack`() {
            assertInstanceOf(Mr20Event.TimeSet::class.java, parse("AA_DEV&T&OK"))
        }

        @ParameterizedTest
        @CsvSource("AA_DEV&USB&1,true", "AA_DEV&USB&0,false")
        fun `reads usb mass storage state`(wire: String, on: Boolean) {
            assertEquals(on, (parse(wire) as Mr20Event.UsbMassStorage).enabled)
        }
    }

    @Nested
    @DisplayName("enumeration")
    inner class Enumeration {
        @Test
        fun `reads a directory entry`() {
            assertEquals(
                "2025-08-13",
                (parse("AA_DEV&DIRS&2025-08-13") as Mr20Event.DirectoryEntry).name,
            )
        }

        @Test
        fun `DIRS_SUM is a count, not a directory named SUM`() {
            val event = parse("AA_DEV&DIRS_SUM&7")
            assertInstanceOf(Mr20Event.DirectoryCount::class.java, event)
            assertEquals(7, (event as Mr20Event.DirectoryCount).count)
        }

        @Test
        fun `reads a file entry`() {
            val event = parse("AA_DEV&F&2025-08-13&REC0001.MP3&325&1310720") as Mr20Event.FileEntry
            assertEquals("2025-08-13", event.directory)
            assertEquals("REC0001.MP3", event.fileName)
            assertEquals(325L, event.durationSeconds)
            assertEquals(1_310_720L, event.sizeBytes)
        }

        @Test
        fun `a file name containing the separator survives`() {
            // The trailing duration/size are fixed-arity and numeric, so the middle
            // fields can absorb an embedded '&'.
            val event = parse("AA_DEV&F&2025-08-13&A&B.MP3&12&34") as Mr20Event.FileEntry
            assertEquals("A&B.MP3", event.fileName)
            assertEquals(12L, event.durationSeconds)
            assertEquals(34L, event.sizeBytes)
        }

        @Test
        fun `reads a file count`() {
            assertEquals(12, (parse("AA_DEV&LIST&12") as Mr20Event.FileCount).count)
        }
    }

    @Nested
    @DisplayName("transfer")
    inner class Transfer {
        @Test
        fun `AA_DEV U LEN announces a BLE transfer`() {
            val event = parse("AA_DEV&U&1310720") as Mr20Event.TransferBeginning
            assertEquals(1_310_720L, event.lengthBytes)
            assertEquals(Mr20TransferChannel.BLE, event.channel)
        }

        @Test
        fun `AA_DEV W LEN announces a Wi-Fi transfer`() {
            val event = parse("AA_DEV&W&2048") as Mr20Event.TransferBeginning
            assertEquals(2048L, event.lengthBytes)
            assertEquals(Mr20TransferChannel.WIFI, event.channel)
        }

        @Test
        fun `AA_DEV U ERR is a failure, not a zero-length transfer`() {
            assertInstanceOf(Mr20Event.TransferFailed::class.java, parse("AA_DEV&U&ERR"))
        }

        @Test
        fun `AA_DEV OFF completes a transfer`() {
            assertInstanceOf(Mr20Event.TransferComplete::class.java, parse("AA_DEV&OFF"))
        }

        @Test
        fun `AA_DEV SHUT aborts a transfer`() {
            assertInstanceOf(Mr20Event.TransferAborted::class.java, parse("AA_DEV&SHUT"))
        }

        @Test
        fun `AA_DEV D is a delete ack and AA_DEV D ERR is a failure`() {
            assertInstanceOf(Mr20Event.FileDeleted::class.java, parse("AA_DEV&D"))
            assertInstanceOf(Mr20Event.FileDeleteFailed::class.java, parse("AA_DEV&D&ERR"))
        }
    }

    @Nested
    @DisplayName("Wi-Fi")
    inner class Wifi {
        @Test
        fun `reads credentials`() {
            val event = parse("AA_DEV&WIFI&MR20_A1B2&12345678") as Mr20Event.WifiCredentials
            assertEquals("MR20_A1B2", event.ssid)
            assertEquals("12345678", event.password)
        }

        @Test
        fun `a password containing the separator survives`() {
            val event = parse("AA_DEV&WIFI&MR20_A1B2&pa&ss") as Mr20Event.WifiCredentials
            assertEquals("MR20_A1B2", event.ssid)
            assertEquals("pa&ss", event.password)
        }

        @ParameterizedTest
        @CsvSource(
            "AA_DEV&WIFIS&0,OFF",
            "AA_DEV&WIFIS&1,CONNECTED",
            "AA_DEV&WIFIS&2,NOT_CONNECTED",
            "AA_DEV&WIFIS&3,WAITING_TO_OPEN",
            "AA_DEV&WIFIS&4,CHANGING_PASSWORD",
            "AA_DEV&WIFIS&5,OTA",
            "AA_DEV&WIFIS&6,PASSWORD_CHANGED_AWAITING_RESET",
            "AA_DEV&WIFIS&7,AUTO_CLOSED",
        )
        fun `reads every documented Wi-Fi state`(wire: String, state: Mr20WifiState) {
            assertEquals(state, (parse(wire) as Mr20Event.WifiStateChanged).state)
        }

        @Test
        fun `states 4 5 and 6 inhibit closing`() {
            assertTrue(Mr20WifiState.CHANGING_PASSWORD.isCloseInhibited)
            assertTrue(Mr20WifiState.OTA.isCloseInhibited)
            assertTrue(Mr20WifiState.PASSWORD_CHANGED_AWAITING_RESET.isCloseInhibited)
            assertFalse(Mr20WifiState.NOT_CONNECTED.isCloseInhibited)
        }

        @Test
        fun `states 1 and 2 mean the AP is up - 2 waiting for us, 1 already joined by hand`() {
            assertTrue(Mr20WifiState.NOT_CONNECTED.isApReadyForClient)
            assertTrue(Mr20WifiState.CONNECTED.isApReadyForClient)
            assertFalse(Mr20WifiState.WAITING_TO_OPEN.isApReadyForClient)
            assertFalse(Mr20WifiState.OFF.isApReadyForClient)
        }

        @Test
        fun `reads open and close acks`() {
            assertInstanceOf(Mr20Event.WifiOpened::class.java, parse("AA_DEV&WIFIO"))
            assertInstanceOf(Mr20Event.WifiClosed::class.java, parse("AA_DEV&WIFIC"))
        }
    }

    @Nested
    @DisplayName("OTA")
    inner class Ota {
        @Test
        fun `reads the whole OTA vocabulary`() {
            assertInstanceOf(Mr20Event.OtaReady::class.java, parse("AA_DEV&OTA"))
            assertInstanceOf(Mr20Event.OtaAccepted::class.java, parse("AA_DEV&OT&OVER"))
            assertInstanceOf(Mr20Event.OtaFailed::class.java, parse("AA_DEV&OT&ERR"))
            assertInstanceOf(Mr20Event.WifiOtaFailed::class.java, parse("AA_DEV&OW&ERR"))
        }
    }

    @Nested
    @DisplayName("robustness")
    inner class Robustness {
        @Test
        fun `tolerates NUL padding and CRLF`() {
            val event = parse("AA_DEV&BAT&42\r\n  ")
            assertEquals(42, (event as Mr20Event.Battery).percent)
        }

        @Test
        fun `parses from raw ASCII bytes`() {
            val event = Mr20EventParser.parse("AA_DEV&STO".toByteArray(Charsets.US_ASCII))
            assertInstanceOf(Mr20Event.RecordingStopped::class.java, event)
        }

        @Test
        fun `an unknown message is preserved, not dropped`() {
            val event = parse("AA_DEV&FUTURE&1&2") as Mr20Event.Unknown
            assertEquals("AA_DEV&FUTURE&1&2", event.raw)
        }

        @Test
        fun `a foreign prefix is unknown`() {
            assertInstanceOf(Mr20Event.Unknown::class.java, parse("HELLO&WORLD"))
            assertInstanceOf(Mr20Event.Unknown::class.java, parse(""))
        }

        @Test
        fun `a non-numeric count is unknown rather than zero`() {
            assertInstanceOf(Mr20Event.Unknown::class.java, parse("AA_DEV&LIST&many"))
            assertInstanceOf(Mr20Event.Unknown::class.java, parse("AA_DEV&SPA&x&y"))
        }
    }
}
