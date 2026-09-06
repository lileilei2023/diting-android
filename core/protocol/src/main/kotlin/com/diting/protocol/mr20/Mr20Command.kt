package com.diting.protocol.mr20

import com.diting.protocol.mr20.Mr20Protocol.APP_PREFIX
import com.diting.protocol.mr20.Mr20Protocol.SEP

/**
 * Every app -> device command in the MR20 protocol.
 *
 * [wire] is the exact ASCII the firmware expects. The sealed hierarchy exists so
 * that callers cannot invent a command the device does not understand, and so
 * that argument validation (key truncation, OTA length padding, bitrate) happens
 * in one place instead of at every call site.
 */
sealed interface Mr20Command {

    /** The literal ASCII payload written to the write characteristic. */
    val wire: String

    /**
     * Commands that get no reply at all. The doc calls these out explicitly:
     * `AA_BLE&WIFI&CH` ("MCU不回复指令"), the two BLE-disconnect commands, and
     * `AA_BLE&SHAKE`. [Mr20Client] must not wait on a response for these.
     */
    val expectsReply: Boolean
        get() = true

    fun encode(): ByteArray = wire.toByteArray(Charsets.US_ASCII)

    // -- pairing ------------------------------------------------------------

    /**
     * `AA_BLE&SK&PWD` — sets (first connection) or presents (later connections)
     * the 16-character bind key. Until this succeeds the device ignores every
     * other command.
     *
     * The firmware takes the first 16 characters of anything longer; we truncate
     * here so the value we persist is the value the device actually stored.
     */
    data class SetSecretKey(val key: String) : Mr20Command {
        /** The key as the device will store it. Persist *this*, not the input. */
        val effectiveKey: String = key.take(Mr20Protocol.SECRET_KEY_LENGTH)

        init {
            require(effectiveKey.isNotEmpty()) { "MR20 bind key must not be empty" }
            require(effectiveKey.none { it == '&' }) {
                "MR20 bind key must not contain '&' — it is the field separator"
            }
        }

        override val wire: String get() = "$APP_PREFIX${SEP}SK$SEP$effectiveKey"
    }

    /** `AA_BLE&SK&RESET` — clears the key and drops the link. */
    data object ResetSecretKey : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}SK${SEP}RESET"
        override val expectsReply: Boolean get() = false
    }

    // -- recording ----------------------------------------------------------

    /** `AA_BLE&STE` -> `AA_DEV&STE&VAL`. */
    data object QueryRecordingState : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}STE"
    }

    /** `AA_BLE&STA` -> `AA_DEV&STA&FNAME`. */
    data object StartRecording : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}STA"
    }

    /** `AA_BLE&STO` -> `AA_DEV&STO`. */
    data object StopRecording : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}STO"
    }

    /** `AA_BLE&REC&SECEN` -> `AA_DEV&REC&CALL` | `AA_DEV&REC&CON`. */
    data object QueryRecordMode : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}REC${SEP}SECEN"
    }

    /** `AA_BLE&KBPS&VAL` -> `AA_DEV&KBPS&OK` | `AA_DEV&KBPS&ERR`. */
    data class SetBitrate(val bitrate: Mr20Bitrate) : Mr20Command {
        override val wire: String get() = "$APP_PREFIX${SEP}KBPS$SEP${bitrate.kbps}"
    }

    // -- device info --------------------------------------------------------

    /** `AA_BLE&SPACE` -> `AA_DEV&SPA&F&T` (megabytes). */
    data object QuerySpace : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}SPACE"
    }

    /** `AA_BLE&BAT` -> `AA_DEV&BAT&RATE`. */
    data object QueryBattery : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}BAT"
    }

    /** `AA_BLE&FW` -> `AA_DEV&FW&1.0`. */
    data object QueryFirmware : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}FW"
    }

    /** `AA_BLE&WF` -> `AA_DEV&WF&V0001`. */
    data object QueryWifiFirmware : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}WF"
    }

    /** `AA_BLE&MAC` -> `AA_DEV&MAC&50c0f04b790c`. */
    data object QueryMacAddress : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}MAC"
    }

    /** `AA_BLE&GT` -> `AA_DEV&CT&20250909105012`. */
    data object QueryDeviceTime : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}GT"
    }

    /**
     * `AA_BLE&T&time` -> `AA_DEV&T&OK`. [time] must already be formatted as
     * `yyyyMMddHHmmss`; use [Mr20Commands.setTime] to build it from an instant.
     */
    data class SetDeviceTime(val time: String) : Mr20Command {
        init {
            require(time.length == 14 && time.all { it.isDigit() }) {
                "MR20 time must be 14 digits (${Mr20Protocol.TIME_PATTERN}), got '$time'"
            }
        }

        override val wire: String get() = "$APP_PREFIX${SEP}T$SEP$time"
    }

    /** `AA_BLE&SHAKE` — haptic ping, no reply. */
    data object Vibrate : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}SHAKE"
        override val expectsReply: Boolean get() = false
    }

    // -- file system --------------------------------------------------------

    /**
     * `AA_BLE&LIST_DIRS` -> N x `AA_DEV&DIRS&<name>` then `AA_DEV&DIRS_SUM&LEN`.
     * Directory names are dates, e.g. `2025-08-13`.
     */
    data object ListDirectories : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}LIST_DIRS"
    }

    /**
     * `AA_BLE&LIST&DIR_NAME` -> N x `AA_DEV&F&DIR&FNAME&TIME&SIZE` then
     * `AA_DEV&LIST&LEN`.
     */
    data class ListFiles(val directory: String) : Mr20Command {
        override val wire: String get() = "$APP_PREFIX${SEP}LIST$SEP$directory"
    }

    /**
     * `AA_BLE&U&DIR&FNAME` (form 1, no local copy) or
     * `AA_BLE&U&DIR&FNAME&SIZE` (form 2, resume from [haveBytes]).
     *
     * The device replies `AA_DEV&U&LEN` with the number of bytes it is about to
     * push over the audio/data characteristic, then streams them, then sends
     * `AA_DEV&OFF`.
     */
    data class PullFileOverBle(
        val directory: String,
        val fileName: String,
        val haveBytes: Long? = null,
    ) : Mr20Command {
        init {
            haveBytes?.let { require(it >= 0) { "haveBytes must be >= 0, got $it" } }
        }

        override val wire: String
            get() = buildString {
                append(APP_PREFIX).append(SEP).append("U")
                append(SEP).append(directory)
                append(SEP).append(fileName)
                haveBytes?.let { append(SEP).append(it) }
            }
    }

    /**
     * `AA_BLE&W&DIR&FNAME[&SIZE]` — same as [PullFileOverBle] but the payload
     * arrives on the Wi-Fi AP socket ([Mr20Protocol.WIFI_HOST]) instead of BLE.
     * The device acknowledges with `AA_DEV&W&LEN` on the BLE command channel.
     */
    data class PullFileOverWifi(
        val directory: String,
        val fileName: String,
        val haveBytes: Long? = null,
    ) : Mr20Command {
        init {
            haveBytes?.let { require(it >= 0) { "haveBytes must be >= 0, got $it" } }
        }

        override val wire: String
            get() = buildString {
                append(APP_PREFIX).append(SEP).append("W")
                append(SEP).append(directory)
                append(SEP).append(fileName)
                haveBytes?.let { append(SEP).append(it) }
            }
    }

    /** `AA_BLE&SHUT` -> `AA_DEV&SHUT`. Aborts an in-flight transfer. */
    data object AbortTransfer : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}SHUT"
    }

    /** `AA_BLE&D&DIR&FNAME` -> `AA_DEV&D` | `AA_DEV&D&ERR`. */
    data class DeleteFile(val directory: String, val fileName: String) : Mr20Command {
        override val wire: String get() = "$APP_PREFIX${SEP}D$SEP$directory$SEP$fileName"
    }

    // -- Wi-Fi --------------------------------------------------------------

    /** `AA_BLE&WIFIO` -> `AA_DEV&WIFIO`. Always brings the AP up. */
    data object OpenWifi : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}WIFIO"
    }

    /**
     * `AA_BLE&WIFIC` -> `AA_DEV&WIFIC`. Ignored by the device while the Wi-Fi
     * state is 4/5/6 — see [Mr20WifiState.isCloseInhibited].
     */
    data object CloseWifi : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}WIFIC"
    }

    /** `AA_BLE&WIFI` -> `AA_DEV&WIFI&SSID&PWD`. */
    data object QueryWifiCredentials : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}WIFI"
    }

    /** `AA_BLE&WIFIS` -> `AA_DEV&WIFIS&STA`. */
    data object QueryWifiState : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}WIFIS"
    }

    /**
     * `AA_BLE&WIFI&CH` — re-derives the AP credentials from the bind key. The MCU
     * sends no reply; poll [QueryWifiState] instead. Takes ~10s.
     */
    data object ChangeWifiCredentials : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}WIFI${SEP}CH"
        override val expectsReply: Boolean get() = false
    }

    // -- USB / link ---------------------------------------------------------

    /** `AA_BLE&USB&STA` -> `AA_DEV&USB&STA`. Toggles Type-C mass storage. */
    data class SetUsbMassStorage(val enabled: Boolean) : Mr20Command {
        override val wire: String get() = "$APP_PREFIX${SEP}USB$SEP${if (enabled) 1 else 0}"
    }

    /** `AA_BLE&GET&USB` -> `AA_DEV&USB&STA`. */
    data object QueryUsbMassStorage : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}GET${SEP}USB"
    }

    /** `AA_BLE&BLE&OFF` — device drops the link. No reply. */
    data object Disconnect : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}BLE${SEP}OFF"
        override val expectsReply: Boolean get() = false
    }

    /** `AA_BLE&BLE&RESET` — device drops the link **and formats storage**. No reply. */
    data object DisconnectAndFormat : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}BLE${SEP}RESET"
        override val expectsReply: Boolean get() = false
    }

    // -- OTA ----------------------------------------------------------------

    /**
     * `AA_BLE&OTA&LEN` -> `AA_DEV&OTA`. Starts an MCU firmware update.
     * `LEN` is zero-padded to exactly 6 digits.
     */
    data class BeginMcuOta(val firmwareSize: Int) : Mr20Command {
        init { requireOtaSize(firmwareSize) }
        override val wire: String
            get() = "$APP_PREFIX${SEP}OTA$SEP${padOtaLength(firmwareSize)}"
    }

    /** `AA_BLE&OTA&WIFI&LEN` -> `AA_DEV&OTA`. Starts a Wi-Fi module update. */
    data class BeginWifiOta(val firmwareSize: Int) : Mr20Command {
        init { requireOtaSize(firmwareSize) }
        override val wire: String
            get() = "$APP_PREFIX${SEP}OTA${SEP}WIFI$SEP${padOtaLength(firmwareSize)}"
    }

    /** `AA_BLE&OT&OVER` -> `AA_DEV&OT&OVER` | `AA_DEV&OT&ERR` | `AA_DEV&OW&ERR`. */
    data object FinishOta : Mr20Command {
        override val wire: String = "$APP_PREFIX${SEP}OT${SEP}OVER"
    }

    private companion object {
        const val MAX_OTA_SIZE = 999_999

        fun requireOtaSize(size: Int) {
            require(size in 1..MAX_OTA_SIZE) {
                "OTA firmware size must fit in ${Mr20Protocol.OTA_LENGTH_DIGITS} digits " +
                    "(1..$MAX_OTA_SIZE bytes), got $size"
            }
        }

        fun padOtaLength(size: Int): String =
            size.toString().padStart(Mr20Protocol.OTA_LENGTH_DIGITS, '0')
    }
}

/** Convenience builders for the commands that need formatting. */
object Mr20Commands {

    private val timeFormatter = java.time.format.DateTimeFormatter
        .ofPattern(Mr20Protocol.TIME_PATTERN)

    /** Builds `AA_BLE&T&yyyyMMddHHmmss` from a local date-time. */
    fun setTime(dateTime: java.time.LocalDateTime): Mr20Command.SetDeviceTime =
        Mr20Command.SetDeviceTime(dateTime.format(timeFormatter))

    /** Builds `AA_BLE&T&…` for "now" in [zone]. */
    fun setTimeNow(
        clock: java.time.Clock = java.time.Clock.systemDefaultZone(),
        zone: java.time.ZoneId = clock.zone,
    ): Mr20Command.SetDeviceTime =
        setTime(java.time.LocalDateTime.now(clock.withZone(zone)))

    /** Parses the `AA_DEV&CT&…` payload back into a local date-time. */
    fun parseDeviceTime(raw: String): java.time.LocalDateTime =
        java.time.LocalDateTime.parse(raw, timeFormatter)
}
