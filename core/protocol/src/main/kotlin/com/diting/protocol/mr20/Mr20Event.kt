package com.diting.protocol.mr20

/**
 * Every device -> app message in the MR20 protocol.
 *
 * The wire format is `AA_DEV&<TAG>[&<arg>…]` (or `AA_EV&…` for the single
 * documented asynchronous error). Because `&` is also a legal character in
 * user-visible strings such as Wi-Fi passwords, the parser resolves ambiguity by
 * anchoring on the *trailing* fixed-arity fields rather than by naive splitting —
 * see [Mr20EventParser].
 */
sealed interface Mr20Event {

    /** The raw ASCII the device sent, kept for logging and for [Unknown]. */
    val raw: String

    // -- pairing ------------------------------------------------------------

    /** `AA_DEV&SK&OK` */
    data class SecretKeyAccepted(override val raw: String) : Mr20Event

    /** `AA_DEV&SK&ERR` */
    data class SecretKeyRejected(override val raw: String) : Mr20Event

    // -- recording ----------------------------------------------------------

    /** `AA_DEV&STE&VAL` — `1` = recording, `0` = idle. */
    data class RecordingState(val isRecording: Boolean, override val raw: String) : Mr20Event

    /** `AA_DEV&STA&FNAME` — recording started, into [fileName]. */
    data class RecordingStarted(val fileName: String, override val raw: String) : Mr20Event

    /** `AA_DEV&STO` — recording stopped and saved. */
    data class RecordingStopped(override val raw: String) : Mr20Event

    /**
     * `AA_DEV&RT&REC_NAME&TIME` — pushed unsolicited when the app connects while
     * the device is already recording. [elapsedSeconds] is the length so far.
     */
    data class RecordingInProgress(
        val fileName: String,
        val elapsedSeconds: Long,
        override val raw: String,
    ) : Mr20Event

    /** `AA_EV&REC&ERR` — recording failed on the device. */
    data class RecordingError(override val raw: String) : Mr20Event

    /** `AA_DEV&DISK&ERR` — storage full. */
    data class DiskFull(override val raw: String) : Mr20Event

    /** `AA_DEV&REC&CALL` | `AA_DEV&REC&CON` */
    data class RecordMode(val mode: Mr20RecordMode, override val raw: String) : Mr20Event

    /** `AA_DEV&KBPS&OK` | `AA_DEV&KBPS&ERR` */
    data class BitrateResult(val success: Boolean, override val raw: String) : Mr20Event

    // -- device info --------------------------------------------------------

    /** `AA_DEV&SPA&F&T` — free / total, in megabytes. */
    data class Space(
        val freeMb: Long,
        val totalMb: Long,
        override val raw: String,
    ) : Mr20Event {
        val usedMb: Long get() = (totalMb - freeMb).coerceAtLeast(0)
    }

    /** `AA_DEV&BAT&RATE` — remaining charge, 0..100. */
    data class Battery(val percent: Int, override val raw: String) : Mr20Event

    /** `AA_DEV&FW&1.0` */
    data class FirmwareVersion(val version: String, override val raw: String) : Mr20Event

    /** `AA_DEV&WF&V0001` */
    data class WifiFirmwareVersion(val version: String, override val raw: String) : Mr20Event

    /** `AA_DEV&MAC&50c0f04b790c` */
    data class MacAddress(val mac: String, override val raw: String) : Mr20Event

    /** `AA_DEV&T&OK` */
    data class TimeSet(override val raw: String) : Mr20Event

    /** `AA_DEV&CT&20250909105012` */
    data class DeviceTime(val time: String, override val raw: String) : Mr20Event {
        fun toLocalDateTime(): java.time.LocalDateTime = Mr20Commands.parseDeviceTime(time)
    }

    /** `AA_DEV&USB&STA` — Type-C mass storage on/off. */
    data class UsbMassStorage(val enabled: Boolean, override val raw: String) : Mr20Event

    // -- file system --------------------------------------------------------

    /** `AA_DEV&DIRS&<name>` — one directory of a `LIST_DIRS` enumeration. */
    data class DirectoryEntry(val name: String, override val raw: String) : Mr20Event

    /** `AA_DEV&DIRS_SUM&LEN` — terminates a `LIST_DIRS` enumeration. */
    data class DirectoryCount(val count: Int, override val raw: String) : Mr20Event

    /** `AA_DEV&F&DIR&FNAME&TIME&SIZE` — one file of a `LIST` enumeration. */
    data class FileEntry(
        val directory: String,
        val fileName: String,
        val durationSeconds: Long,
        val sizeBytes: Long,
        override val raw: String,
    ) : Mr20Event

    /** `AA_DEV&LIST&LEN` — terminates a `LIST` enumeration. */
    data class FileCount(val count: Int, override val raw: String) : Mr20Event

    /**
     * `AA_DEV&U&LEN` (BLE) or `AA_DEV&W&LEN` (Wi-Fi) — the device is about to
     * push [lengthBytes] bytes on the corresponding data channel.
     */
    data class TransferBeginning(
        val lengthBytes: Long,
        val channel: Mr20TransferChannel,
        override val raw: String,
    ) : Mr20Event

    /** `AA_DEV&OFF` — the device finished pushing file data. */
    data class TransferComplete(override val raw: String) : Mr20Event

    /** `AA_DEV&U&ERR` — the device could not open the file. */
    data class TransferFailed(override val raw: String) : Mr20Event

    /** `AA_DEV&SHUT` — transfer aborted (ack of `AA_BLE&SHUT`). */
    data class TransferAborted(override val raw: String) : Mr20Event

    /** `AA_DEV&D` */
    data class FileDeleted(override val raw: String) : Mr20Event

    /** `AA_DEV&D&ERR` */
    data class FileDeleteFailed(override val raw: String) : Mr20Event

    // -- Wi-Fi --------------------------------------------------------------

    /** `AA_DEV&WIFIO` */
    data class WifiOpened(override val raw: String) : Mr20Event

    /** `AA_DEV&WIFIC` */
    data class WifiClosed(override val raw: String) : Mr20Event

    /** `AA_DEV&WIFI&SSID&PWD` */
    data class WifiCredentials(
        val ssid: String,
        val password: String,
        override val raw: String,
    ) : Mr20Event

    /** `AA_DEV&WIFIS&STA` */
    data class WifiStateChanged(val state: Mr20WifiState, override val raw: String) : Mr20Event

    // -- OTA ----------------------------------------------------------------

    /** `AA_DEV&OTA` — device is in OTA mode and ready for firmware frames. */
    data class OtaReady(override val raw: String) : Mr20Event

    /** `AA_DEV&OT&OVER` — firmware payload accepted. */
    data class OtaAccepted(override val raw: String) : Mr20Event

    /** `AA_DEV&OT&ERR` — firmware payload rejected. */
    data class OtaFailed(override val raw: String) : Mr20Event

    /** `AA_DEV&OW&ERR` — the Wi-Fi module failed to flash the image. */
    data class WifiOtaFailed(override val raw: String) : Mr20Event

    // -- fallback -----------------------------------------------------------

    /**
     * A well-formed but unrecognised message. Never thrown away: firmware
     * revisions add commands, and swallowing them silently makes field bugs
     * impossible to diagnose.
     */
    data class Unknown(override val raw: String) : Mr20Event
}

/** Which data channel a file transfer uses. */
enum class Mr20TransferChannel {
    /** `AA_BLE&U&…` — bytes arrive on the audio/data notify characteristic. */
    BLE,

    /** `AA_BLE&W&…` — bytes arrive on the AP socket at 192.168.200.1:8475. */
    WIFI,
}
