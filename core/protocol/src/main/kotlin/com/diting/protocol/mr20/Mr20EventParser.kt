package com.diting.protocol.mr20

import com.diting.protocol.mr20.Mr20Protocol.DEVICE_PREFIX
import com.diting.protocol.mr20.Mr20Protocol.EVENT_PREFIX

/**
 * Turns one BLE command-channel notification into an [Mr20Event].
 *
 * Design notes:
 *
 *  * **One frame == one message.** The firmware writes a whole command per
 *    notification, so we never try to reassemble across frames. Trailing NULs and
 *    CR/LF (some firmware builds pad) are stripped.
 *
 *  * **Ambiguous separators.** `&` delimits fields but is also legal inside a
 *    Wi-Fi password and (in principle) a file name. For every message whose
 *    trailing fields have fixed arity and a numeric type — `AA_DEV&F`,
 *    `AA_DEV&RT` — we anchor on the end of the message and let the *middle*
 *    absorb the extra separators. That is the only interpretation that round-trips
 *    a name containing `&`.
 *
 *  * **Nothing is discarded.** Anything unrecognised comes back as
 *    [Mr20Event.Unknown] carrying the raw text.
 */
object Mr20EventParser {

    /** Parses a raw notification payload. */
    fun parse(frame: ByteArray): Mr20Event = parse(frame.toString(Charsets.US_ASCII))

    /** Parses a raw notification already decoded to text. */
    fun parse(text: String): Mr20Event {
        // Strips NUL padding, CR/LF and spaces that some firmware builds append.
        val raw = text.trim { it <= ' ' }
        if (raw.isEmpty()) return Mr20Event.Unknown(text)

        val f = raw.split(Mr20Protocol.SEP)
        val prefix = f.getOrNull(0)
        if (prefix != DEVICE_PREFIX && prefix != EVENT_PREFIX) return Mr20Event.Unknown(raw)

        val tag = f.getOrNull(1) ?: return Mr20Event.Unknown(raw)
        val a1 = f.getOrNull(2)

        // AA_EV&REC&ERR is the only documented AA_EV message.
        if (prefix == EVENT_PREFIX) {
            return if (tag == "REC" && a1 == "ERR") Mr20Event.RecordingError(raw)
            else Mr20Event.Unknown(raw)
        }

        return when (tag) {
            // -- pairing ----------------------------------------------------
            "SK" -> when (a1) {
                "OK" -> Mr20Event.SecretKeyAccepted(raw)
                "ERR" -> Mr20Event.SecretKeyRejected(raw)
                else -> Mr20Event.Unknown(raw)
            }

            // -- recording --------------------------------------------------
            "STE" -> when (a1) {
                "1" -> Mr20Event.RecordingState(isRecording = true, raw = raw)
                "0" -> Mr20Event.RecordingState(isRecording = false, raw = raw)
                else -> Mr20Event.Unknown(raw)
            }

            // The file name may itself contain '&', so take everything after
            // "AA_DEV&STA&" verbatim rather than f[2].
            "STA" -> rest(f, 2)?.let { Mr20Event.RecordingStarted(it, raw) }
                ?: Mr20Event.Unknown(raw)

            "STO" -> Mr20Event.RecordingStopped(raw)

            // AA_DEV&RT&<name>&<seconds> — anchor on the trailing integer.
            "RT" -> {
                val seconds = f.lastOrNull()?.toLongOrNull()
                val name = if (f.size >= 4) f.subList(2, f.size - 1).joinToString(Mr20Protocol.SEP) else null
                if (seconds != null && !name.isNullOrEmpty()) {
                    Mr20Event.RecordingInProgress(name, seconds, raw)
                } else {
                    Mr20Event.Unknown(raw)
                }
            }

            "DISK" -> if (a1 == "ERR") Mr20Event.DiskFull(raw) else Mr20Event.Unknown(raw)

            "REC" -> when (a1) {
                "CALL" -> Mr20Event.RecordMode(Mr20RecordMode.CALL, raw)
                "CON" -> Mr20Event.RecordMode(Mr20RecordMode.CONVERSATION, raw)
                else -> Mr20Event.Unknown(raw)
            }

            "KBPS" -> when (a1) {
                "OK" -> Mr20Event.BitrateResult(success = true, raw = raw)
                "ERR" -> Mr20Event.BitrateResult(success = false, raw = raw)
                else -> Mr20Event.Unknown(raw)
            }

            // -- device info ------------------------------------------------
            "SPA" -> {
                val free = a1?.toLongOrNull()
                val total = f.getOrNull(3)?.toLongOrNull()
                if (free != null && total != null) Mr20Event.Space(free, total, raw)
                else Mr20Event.Unknown(raw)
            }

            "BAT" -> a1?.toIntOrNull()
                ?.let { Mr20Event.Battery(it.coerceIn(0, 100), raw) }
                ?: Mr20Event.Unknown(raw)

            "FW" -> rest(f, 2)?.let { Mr20Event.FirmwareVersion(it, raw) }
                ?: Mr20Event.Unknown(raw)

            "WF" -> rest(f, 2)?.let { Mr20Event.WifiFirmwareVersion(it, raw) }
                ?: Mr20Event.Unknown(raw)

            "MAC" -> rest(f, 2)?.let { Mr20Event.MacAddress(it, raw) }
                ?: Mr20Event.Unknown(raw)

            "T" -> if (a1 == "OK") Mr20Event.TimeSet(raw) else Mr20Event.Unknown(raw)

            "CT" -> a1?.takeIf { it.length == 14 && it.all(Char::isDigit) }
                ?.let { Mr20Event.DeviceTime(it, raw) }
                ?: Mr20Event.Unknown(raw)

            "USB" -> when (a1) {
                "1" -> Mr20Event.UsbMassStorage(enabled = true, raw = raw)
                "0" -> Mr20Event.UsbMassStorage(enabled = false, raw = raw)
                else -> Mr20Event.Unknown(raw)
            }

            // -- file system ------------------------------------------------
            "DIRS" -> rest(f, 2)?.let { Mr20Event.DirectoryEntry(it, raw) }
                ?: Mr20Event.Unknown(raw)

            "DIRS_SUM" -> a1?.toIntOrNull()?.let { Mr20Event.DirectoryCount(it, raw) }
                ?: Mr20Event.Unknown(raw)

            // AA_DEV&F&<dir>&<name>&<seconds>&<bytes> — the last two fields are
            // numeric and fixed, the directory is f[2], and the name is whatever
            // is in between (so a name containing '&' survives).
            "F" -> {
                if (f.size < 6) return Mr20Event.Unknown(raw)
                val size = f[f.size - 1].toLongOrNull()
                val duration = f[f.size - 2].toLongOrNull()
                val dir = f[2]
                val name = f.subList(3, f.size - 2).joinToString(Mr20Protocol.SEP)
                if (size != null && duration != null && name.isNotEmpty()) {
                    Mr20Event.FileEntry(dir, name, duration, size, raw)
                } else {
                    Mr20Event.Unknown(raw)
                }
            }

            "LIST" -> a1?.toIntOrNull()?.let { Mr20Event.FileCount(it, raw) }
                ?: Mr20Event.Unknown(raw)

            // AA_DEV&U&<len> vs AA_DEV&U&ERR share a tag.
            "U" -> when {
                a1 == "ERR" -> Mr20Event.TransferFailed(raw)
                a1?.toLongOrNull() != null ->
                    Mr20Event.TransferBeginning(a1.toLong(), Mr20TransferChannel.BLE, raw)
                else -> Mr20Event.Unknown(raw)
            }

            "W" -> when {
                a1 == "ERR" -> Mr20Event.TransferFailed(raw)
                a1?.toLongOrNull() != null ->
                    Mr20Event.TransferBeginning(a1.toLong(), Mr20TransferChannel.WIFI, raw)
                else -> Mr20Event.Unknown(raw)
            }

            "OFF" -> Mr20Event.TransferComplete(raw)
            "SHUT" -> Mr20Event.TransferAborted(raw)

            "D" -> when (a1) {
                null -> Mr20Event.FileDeleted(raw)
                "ERR" -> Mr20Event.FileDeleteFailed(raw)
                else -> Mr20Event.Unknown(raw)
            }

            // -- Wi-Fi ------------------------------------------------------
            "WIFIO" -> Mr20Event.WifiOpened(raw)
            "WIFIC" -> Mr20Event.WifiClosed(raw)

            "WIFIS" -> a1?.singleOrNull()
                ?.let { Mr20WifiState.fromCode(it) }
                ?.let { Mr20Event.WifiStateChanged(it, raw) }
                ?: Mr20Event.Unknown(raw)

            // AA_DEV&WIFI&<ssid>&<password> — a password may contain '&', an SSID
            // realistically may not, so the first field wins and the rest is the
            // password.
            "WIFI" -> {
                if (f.size < 4) return Mr20Event.Unknown(raw)
                Mr20Event.WifiCredentials(
                    ssid = f[2],
                    password = f.subList(3, f.size).joinToString(Mr20Protocol.SEP),
                    raw = raw,
                )
            }

            // -- OTA --------------------------------------------------------
            "OTA" -> Mr20Event.OtaReady(raw)

            "OT" -> when (a1) {
                "OVER" -> Mr20Event.OtaAccepted(raw)
                "ERR" -> Mr20Event.OtaFailed(raw)
                else -> Mr20Event.Unknown(raw)
            }

            "OW" -> if (a1 == "ERR") Mr20Event.WifiOtaFailed(raw) else Mr20Event.Unknown(raw)

            else -> Mr20Event.Unknown(raw)
        }
    }

    /** Everything from field [from] onward, re-joined; null when absent/empty. */
    private fun rest(fields: List<String>, from: Int): String? =
        if (fields.size <= from) null
        else fields.subList(from, fields.size)
            .joinToString(Mr20Protocol.SEP)
            .takeIf { it.isNotEmpty() }
}
