package com.diting.protocol.mr20

import java.util.UUID

/**
 * Wire-level constants for the MR20 recorder.
 *
 * Source: 《MR20通信协议_20260721.xlsx》 (Sheet1). Everything in this file is a
 * direct transcription of that document — do not "tidy" the strings.
 */
object Mr20Protocol {

    // ---- GATT ---------------------------------------------------------------

    /** Primary service. */
    val SERVICE_UUID: UUID = UUID.fromString("001120a0-2233-4455-6677-88995a5b5c5d")

    /**
     * Audio / bulk-data notifications.
     *
     * Carries two different things depending on device state:
     *  * while the device is recording and the app is merely connected, a live
     *    **MP3** stream;
     *  * during an `AA_BLE&U&…` file sync, the raw bytes of the file being pulled.
     *
     * [Mr20Client] demultiplexes the two by tracking whether a transfer is open.
     */
    val AUDIO_NOTIFY_UUID: UUID = UUID.fromString("001120a1-2233-4455-6677-88995a5b5c5d")

    /** App -> device command writes. */
    val WRITE_UUID: UUID = UUID.fromString("001120a2-2233-4455-6677-88995a5b5c5d")

    /** Device -> app command notifications. */
    val COMMAND_NOTIFY_UUID: UUID = UUID.fromString("001120a3-2233-4455-6677-88995a5b5c5d")

    /** Standard Client Characteristic Configuration descriptor. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ---- Grammar ------------------------------------------------------------

    /** Every app->device command starts with this. */
    const val APP_PREFIX = "AA_BLE"

    /** Ordinary device->app replies. */
    const val DEVICE_PREFIX = "AA_DEV"

    /** Asynchronous device->app events (only `AA_EV&REC&ERR` is documented). */
    const val EVENT_PREFIX = "AA_EV"

    const val SEP = "&"

    // ---- Wi-Fi AP transfer --------------------------------------------------

    /** The device's own AP address once Wi-Fi is up. */
    const val WIFI_HOST = "192.168.200.1"
    const val WIFI_PORT = 8475

    /**
     * Trailer appended by the device after the last byte of a Wi-Fi file
     * transfer. It is *not* part of the file and must be stripped.
     */
    val WIFI_EOF_MARKER: ByteArray = byteArrayOf(
        0xBA.toByte(), 0x5A.toByte(), 0x02.toByte(), 0x8F.toByte(), 0x04.toByte()
    )

    // ---- OTA ----------------------------------------------------------------

    /** Exact OTA payload frame size mandated by the spec. */
    const val OTA_FRAME_SIZE = 244

    /** Minimum inter-frame delay for OTA. The doc says >=8ms, 20ms on iOS. */
    const val OTA_MIN_FRAME_INTERVAL_MS = 8L

    /** The `LEN` argument of `AA_BLE&OTA&LEN` is a fixed-width 6-digit decimal. */
    const val OTA_LENGTH_DIGITS = 6

    // ---- Pairing ------------------------------------------------------------

    /**
     * `AA_BLE&SK&PWD` — "PWD：16 位（超过16位取前16位）". The firmware silently
     * truncates; we do it explicitly so the app and device agree on the key that
     * was actually stored.
     */
    const val SECRET_KEY_LENGTH = 16

    /** `AA_BLE&T&time` uses this pattern, e.g. 20250601103000. */
    const val TIME_PATTERN = "yyyyMMddHHmmss"
}

/** Recording mode reported by `AA_BLE&REC&SECEN`. */
enum class Mr20RecordMode {
    /** `AA_DEV&REC&CALL` */
    CALL,

    /** `AA_DEV&REC&CON` */
    CONVERSATION,
}

/** Encoding bitrate accepted by `AA_BLE&KBPS&VAL`. */
enum class Mr20Bitrate(val kbps: Int) {
    KBPS_32(32),
    KBPS_64(64);

    companion object {
        fun of(kbps: Int): Mr20Bitrate = entries.firstOrNull { it.kbps == kbps }
            ?: throw IllegalArgumentException("MR20 only accepts 32 or 64 kbps, got $kbps")
    }
}

/**
 * Values of `STA` in `AA_DEV&WIFIS&STA`.
 *
 * Note [CHANGING_PASSWORD], [OTA] and [PASSWORD_CHANGED] are "sticky": the doc
 * states Wi-Fi cannot be turned off by command while in those states.
 */
enum class Mr20WifiState(val code: Char) {
    OFF('0'),
    CONNECTED('1'),
    NOT_CONNECTED('2'),
    WAITING_TO_OPEN('3'),
    CHANGING_PASSWORD('4'),
    OTA('5'),
    PASSWORD_CHANGED_AWAITING_RESET('6'),
    AUTO_CLOSED('7');

    /** The device ignores `AA_BLE&WIFIC` in these states. */
    val isCloseInhibited: Boolean
        get() = this == CHANGING_PASSWORD || this == OTA || this == PASSWORD_CHANGED_AWAITING_RESET

    /**
     * The AP is up: either waiting for a client, or the phone is already on it
     * (a manual join from system settings). Both mean "open the socket now".
     */
    val isApReadyForClient: Boolean
        get() = this == NOT_CONNECTED || this == CONNECTED

    companion object {
        fun fromCode(code: Char): Mr20WifiState? = entries.firstOrNull { it.code == code }
    }
}
