package com.diting.protocol.mr20

/**
 * Splits a firmware image into the frames the MR20 bootloader expects.
 *
 * From the spec: "APP发送一帧OTA数据长度为244 byte,每帧数据至少间隔8MS以上,IOS建议20MS.
 * OTA过程中,禁止APP发送其他指令,否则会OTA失败."
 *
 * Two constraints follow, and both are enforced here rather than left to the
 * caller: frames are exactly [Mr20Protocol.OTA_FRAME_SIZE] bytes (the last one
 * short), and no other command may be interleaved — [Mr20Client] holds its
 * command lock for the whole session.
 */
class Mr20OtaSession(
    val firmware: ByteArray,
    val target: Target,
) {
    enum class Target {
        /** `AA_BLE&OTA&LEN` — the main MCU image. */
        MCU,

        /** `AA_BLE&OTA&WIFI&LEN` — the Wi-Fi module image. */
        WIFI,
    }

    init {
        require(firmware.isNotEmpty()) { "OTA firmware image is empty" }
    }

    /** The `AA_BLE&OTA…` command that opens the session. */
    val beginCommand: Mr20Command = when (target) {
        Target.MCU -> Mr20Command.BeginMcuOta(firmware.size)
        Target.WIFI -> Mr20Command.BeginWifiOta(firmware.size)
    }

    /** Total number of frames [frames] will yield. */
    val frameCount: Int =
        (firmware.size + Mr20Protocol.OTA_FRAME_SIZE - 1) / Mr20Protocol.OTA_FRAME_SIZE

    /**
     * The firmware image as 244-byte frames, in order. The final frame is short
     * whenever the image is not a multiple of the frame size.
     */
    fun frames(): Sequence<ByteArray> = sequence {
        var offset = 0
        while (offset < firmware.size) {
            val len = minOf(Mr20Protocol.OTA_FRAME_SIZE, firmware.size - offset)
            yield(firmware.copyOfRange(offset, offset + len))
            offset += len
        }
    }

    /**
     * Whether a `AA_DEV&OW&ERR` (Wi-Fi flash failure) is a plausible outcome for
     * this session. Used to give the user an accurate error rather than a generic
     * "OTA failed".
     */
    val canFailDuringWifiFlash: Boolean get() = target == Target.WIFI
}
