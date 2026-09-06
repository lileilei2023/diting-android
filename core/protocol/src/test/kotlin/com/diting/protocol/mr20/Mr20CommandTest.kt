package com.diting.protocol.mr20

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime

class Mr20CommandTest {

    @Test
    fun `simple commands match the spec verbatim`() {
        assertEquals("AA_BLE&STE", Mr20Command.QueryRecordingState.wire)
        assertEquals("AA_BLE&STA", Mr20Command.StartRecording.wire)
        assertEquals("AA_BLE&STO", Mr20Command.StopRecording.wire)
        assertEquals("AA_BLE&SPACE", Mr20Command.QuerySpace.wire)
        assertEquals("AA_BLE&BAT", Mr20Command.QueryBattery.wire)
        assertEquals("AA_BLE&FW", Mr20Command.QueryFirmware.wire)
        assertEquals("AA_BLE&WF", Mr20Command.QueryWifiFirmware.wire)
        assertEquals("AA_BLE&GT", Mr20Command.QueryDeviceTime.wire)
        assertEquals("AA_BLE&MAC", Mr20Command.QueryMacAddress.wire)
        assertEquals("AA_BLE&LIST_DIRS", Mr20Command.ListDirectories.wire)
        assertEquals("AA_BLE&SHUT", Mr20Command.AbortTransfer.wire)
        assertEquals("AA_BLE&WIFIO", Mr20Command.OpenWifi.wire)
        assertEquals("AA_BLE&WIFIC", Mr20Command.CloseWifi.wire)
        assertEquals("AA_BLE&WIFI", Mr20Command.QueryWifiCredentials.wire)
        assertEquals("AA_BLE&WIFIS", Mr20Command.QueryWifiState.wire)
        assertEquals("AA_BLE&WIFI&CH", Mr20Command.ChangeWifiCredentials.wire)
        assertEquals("AA_BLE&REC&SECEN", Mr20Command.QueryRecordMode.wire)
        assertEquals("AA_BLE&BLE&OFF", Mr20Command.Disconnect.wire)
        assertEquals("AA_BLE&BLE&RESET", Mr20Command.DisconnectAndFormat.wire)
        assertEquals("AA_BLE&GET&USB", Mr20Command.QueryUsbMassStorage.wire)
        assertEquals("AA_BLE&OT&OVER", Mr20Command.FinishOta.wire)
        assertEquals("AA_BLE&SHAKE", Mr20Command.Vibrate.wire)
        assertEquals("AA_BLE&SK&RESET", Mr20Command.ResetSecretKey.wire)
    }

    @Test
    fun `commands the device never answers are marked as such`() {
        // Getting this wrong means the app blocks for a full timeout on every one.
        assertFalse(Mr20Command.ChangeWifiCredentials.expectsReply)
        assertFalse(Mr20Command.Vibrate.expectsReply)
        assertFalse(Mr20Command.Disconnect.expectsReply)
        assertFalse(Mr20Command.DisconnectAndFormat.expectsReply)
        assertFalse(Mr20Command.ResetSecretKey.expectsReply)
        assertTrue(Mr20Command.QueryBattery.expectsReply)
    }

    @Test
    fun `encodes as plain ASCII`() {
        assertArrayEquals(
            "AA_BLE&BAT".toByteArray(Charsets.US_ASCII),
            Mr20Command.QueryBattery.encode(),
        )
    }

    // -- bind key ------------------------------------------------------------

    @Test
    fun `bind key longer than 16 chars is truncated the way the firmware does`() {
        val command = Mr20Command.SetSecretKey("0123456789ABCDEFGHIJ")
        assertEquals("0123456789ABCDEF", command.effectiveKey)
        assertEquals("AA_BLE&SK&0123456789ABCDEF", command.wire)
    }

    @Test
    fun `bind key shorter than 16 chars is sent as-is`() {
        assertEquals("AA_BLE&SK&abc123", Mr20Command.SetSecretKey("abc123").wire)
    }

    @Test
    fun `bind key may not contain the field separator`() {
        assertThrows<IllegalArgumentException> { Mr20Command.SetSecretKey("ab&cd") }
    }

    @Test
    fun `bind key may not be empty`() {
        assertThrows<IllegalArgumentException> { Mr20Command.SetSecretKey("") }
    }

    // -- time ----------------------------------------------------------------

    @Test
    fun `set time uses yyyyMMddHHmmss`() {
        val command = Mr20Commands.setTime(LocalDateTime.of(2025, 6, 1, 10, 30, 0))
        assertEquals("AA_BLE&T&20250601103000", command.wire)
    }

    @Test
    fun `set time rejects a malformed literal`() {
        assertThrows<IllegalArgumentException> { Mr20Command.SetDeviceTime("2025-06-01") }
    }

    @Test
    fun `device time round-trips`() {
        val t = LocalDateTime.of(2025, 9, 9, 10, 50, 12)
        assertEquals(t, Mr20Commands.parseDeviceTime("20250909105012"))
    }

    // -- file transfer -------------------------------------------------------

    @Test
    fun `pull without a local copy uses the four-field form`() {
        assertEquals(
            "AA_BLE&U&2025-08-13&REC0001.MP3",
            Mr20Command.PullFileOverBle("2025-08-13", "REC0001.MP3").wire,
        )
    }

    @Test
    fun `pull with a local copy appends the size`() {
        assertEquals(
            "AA_BLE&U&2025-08-13&REC0001.MP3&4096",
            Mr20Command.PullFileOverBle("2025-08-13", "REC0001.MP3", 4096).wire,
        )
    }

    @Test
    fun `the Wi-Fi form uses W instead of U`() {
        assertEquals(
            "AA_BLE&W&2025-08-13&REC0001.MP3",
            Mr20Command.PullFileOverWifi("2025-08-13", "REC0001.MP3").wire,
        )
        assertEquals(
            "AA_BLE&W&2025-08-13&REC0001.MP3&4096",
            Mr20Command.PullFileOverWifi("2025-08-13", "REC0001.MP3", 4096).wire,
        )
    }

    @Test
    fun `delete names the directory and the file`() {
        assertEquals(
            "AA_BLE&D&2025-08-13&REC0001.MP3",
            Mr20Command.DeleteFile("2025-08-13", "REC0001.MP3").wire,
        )
    }

    @Test
    fun `list names the directory`() {
        assertEquals("AA_BLE&LIST&2025-08-13", Mr20Command.ListFiles("2025-08-13").wire)
    }

    // -- bitrate / usb -------------------------------------------------------

    @Test
    fun `bitrate accepts only 32 and 64`() {
        assertEquals("AA_BLE&KBPS&32", Mr20Command.SetBitrate(Mr20Bitrate.KBPS_32).wire)
        assertEquals("AA_BLE&KBPS&64", Mr20Command.SetBitrate(Mr20Bitrate.KBPS_64).wire)
        assertThrows<IllegalArgumentException> { Mr20Bitrate.of(128) }
    }

    @Test
    fun `usb toggle uses 1 and 0`() {
        assertEquals("AA_BLE&USB&1", Mr20Command.SetUsbMassStorage(true).wire)
        assertEquals("AA_BLE&USB&0", Mr20Command.SetUsbMassStorage(false).wire)
    }

    // -- OTA -----------------------------------------------------------------

    @Test
    fun `OTA length is zero-padded to exactly six digits`() {
        assertEquals("AA_BLE&OTA&000512", Mr20Command.BeginMcuOta(512).wire)
        assertEquals("AA_BLE&OTA&131072", Mr20Command.BeginMcuOta(131_072).wire)
        assertEquals("AA_BLE&OTA&WIFI&004096", Mr20Command.BeginWifiOta(4096).wire)
    }

    @Test
    fun `OTA rejects a size that does not fit in six digits`() {
        assertThrows<IllegalArgumentException> { Mr20Command.BeginMcuOta(1_000_000) }
        assertThrows<IllegalArgumentException> { Mr20Command.BeginMcuOta(0) }
    }
}
