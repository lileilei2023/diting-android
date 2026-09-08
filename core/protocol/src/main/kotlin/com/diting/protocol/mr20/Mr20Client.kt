package com.diting.protocol.mr20

import com.diting.protocol.common.ByteStreamTransport
import com.diting.protocol.common.PacketTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Timeouts, all in milliseconds. Generous by default; BLE is slow and jittery. */
data class Mr20Timeouts(
    val simpleCommand: Long = 5_000,
    val pairing: Long = 8_000,
    /** Enumerations grow with the number of files on the device. */
    val enumeration: Long = 30_000,
    /** Waiting for `AA_DEV&U&LEN` / `AA_DEV&W&LEN` after asking for a file. */
    val transferStart: Long = 15_000,
    /** Longest gap tolerated between two data packets of one file. */
    val transferIdle: Long = 20_000,
    /** `AA_BLE&WIFI&CH` needs ~10s, and a Wi-Fi reset ~6s on top. */
    val wifiCredentialChange: Long = 25_000,
    val otaReady: Long = 10_000,
    val otaFinish: Long = 60_000,
)

/** Everything that can go wrong at the protocol level. */
sealed class Mr20Exception(message: String) : Exception(message) {
    class NotAuthenticated : Mr20Exception(
        "MR20 rejected the command: send AA_BLE&SK&<key> first"
    )

    class PairingRejected : Mr20Exception("MR20 rejected the bind key (AA_DEV&SK&ERR)")

    class Timeout(command: String, ms: Long) :
        Mr20Exception("MR20 did not answer '$command' within ${ms}ms")

    class DeviceError(message: String) : Mr20Exception(message)

    class TransferFailed(message: String) : Mr20Exception(message)
}

/** One recording as listed by `AA_BLE&LIST&<dir>`. */
data class Mr20File(
    val directory: String,
    val name: String,
    val durationSeconds: Long,
    val sizeBytes: Long,
) {
    /** Stable identity for local bookkeeping — the device has no file IDs. */
    val path: String get() = "$directory/$name"
}

/** Snapshot of the device, refreshed on connect and after each sync. */
data class Mr20DeviceInfo(
    val firmwareVersion: String? = null,
    val wifiFirmwareVersion: String? = null,
    val macAddress: String? = null,
    val batteryPercent: Int? = null,
    val freeMb: Long? = null,
    val totalMb: Long? = null,
    val recordMode: Mr20RecordMode? = null,
    val isRecording: Boolean = false,
)

/**
 * Request/response session layer over the MR20's BLE command channel.
 *
 * The protocol carries no sequence numbers or correlation IDs, so replies can
 * only be matched to requests positionally. That forces exactly one outstanding
 * command at a time, which [commandLock] enforces — the alternative (fire and
 * hope) is what makes ad-hoc implementations of this protocol flaky.
 *
 * Unsolicited events (`AA_DEV&RT&…`, `AA_EV&REC&ERR`, `AA_DEV&DISK&ERR`,
 * `AA_DEV&WIFIS&…`) are broadcast on [events] regardless of what request is in
 * flight.
 *
 * @param commandTransport write characteristic `…a2` + notify characteristic `…a3`
 * @param dataTransport notify characteristic `…a1` (live MP3 *and* BLE file sync)
 */
class Mr20Client(
    private val commandTransport: PacketTransport,
    private val dataTransport: ByteStreamTransport,
    private val scope: CoroutineScope,
    private val timeouts: Mr20Timeouts = Mr20Timeouts(),
) {

    private val _events = MutableSharedFlow<Mr20Event>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /** Every parsed device message, solicited or not. */
    val events: SharedFlow<Mr20Event> = _events.asSharedFlow()

    private val _liveAudio = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Live MP3 frames pushed on `…a1` while the device is recording.
     *
     * Dropping is deliberate: a slow consumer must not stall the BLE stack or
     * corrupt a concurrent file sync. Anything that must not be lost belongs in a
     * file sync, not in this stream.
     */
    val liveAudio: SharedFlow<ByteArray> = _liveAudio.asSharedFlow()

    /** Serialises command/response exchanges — see the class doc. */
    private val commandLock = Mutex()

    /**
     * Non-null exactly while a BLE file sync is in flight.
     *
     * [ActiveTransfer.finished] is what the puller awaits: it is completed either
     * by the data pump (when the announced byte count is reached) or by the
     * command pump (`AA_DEV&OFF` / `AA_DEV&SHUT` / `AA_DEV&U&ERR`), whichever
     * happens first. Waiting only on command events would stall a transfer whose
     * terminator notification is dropped.
     */
    private class ActiveTransfer(
        val receiver: Mr20FileReceiver,
        val onProgress: ((Float?) -> Unit)? = null,
        val finished: CompletableDeferred<Unit> = CompletableDeferred(),
    ) {
        private var lastReportedPercent = -1

        fun settleIfDone() {
            if (receiver.state != Mr20FileReceiver.State.RECEIVING) finished.complete(Unit)
        }

        /**
         * Progress comes from the *data* channel — hundreds of packets a second —
         * so it is reported only when the whole-percent changes. Reporting on the
         * command channel alone (as before) left the bar at 0 for the entire file.
         */
        fun reportProgress() {
            val fraction = receiver.progress ?: return
            val percent = (fraction * 100).toInt()
            if (percent != lastReportedPercent) {
                lastReportedPercent = percent
                onProgress?.invoke(fraction)
            }
        }
    }

    @Volatile
    private var activeTransfer: ActiveTransfer? = null

    @Volatile
    var isAuthenticated: Boolean = false
        private set

    /** Starts pumping both notify channels. Call once, after GATT discovery. */
    fun start() {
        commandTransport.incoming
            .map(Mr20EventParser::parse)
            .onEach { event ->
                when (event) {
                    is Mr20Event.SecretKeyAccepted -> isAuthenticated = true
                    is Mr20Event.SecretKeyRejected -> isAuthenticated = false
                    else -> Unit
                }
                _events.emit(event)
            }
            .launchIn(scope)

        dataTransport.incoming
            .onEach { chunk ->
                // Demultiplex: while a file sync is open the bytes on …a1 are file
                // content, otherwise they are the live MP3 stream.
                val transfer = activeTransfer
                if (transfer != null) {
                    transfer.receiver.onChunk(chunk)
                    transfer.reportProgress()
                    transfer.settleIfDone()
                } else {
                    _liveAudio.emit(chunk)
                }
            }
            .launchIn(scope)
    }

    // ---- pairing -----------------------------------------------------------

    /**
     * Sends `AA_BLE&SK&<key>`. On the first connection this *sets* the key; later
     * it presents it. Until this returns true no other command takes effect.
     *
     * @return the key the device actually stored (input truncated to 16 chars).
     */
    suspend fun authenticate(key: String): String {
        val command = Mr20Command.SetSecretKey(key)
        val reply = exchangeSingle(command, timeouts.pairing) { event ->
            when (event) {
                is Mr20Event.SecretKeyAccepted, is Mr20Event.SecretKeyRejected -> event
                else -> null
            }
        }
        if (reply is Mr20Event.SecretKeyRejected) throw Mr20Exception.PairingRejected()
        isAuthenticated = true
        return command.effectiveKey
    }

    /**
     * Sends `AA_BLE&WIFI&CH` and waits for the AP credentials to settle.
     *
     * The MCU never acknowledges this command, so the only way to know it worked
     * is to poll `AA_BLE&WIFIS`: the device passes through state `4` (changing)
     * and lands on `6` (changed, resetting) before eventually going to `0`.
     */
    suspend fun syncWifiCredentialsToKey() {
        send(Mr20Command.ChangeWifiCredentials)
        withTimeout(timeouts.wifiCredentialChange) {
            var sawChanging = false
            while (true) {
                val state = queryWifiState()
                if (state == Mr20WifiState.CHANGING_PASSWORD) sawChanging = true
                if (sawChanging &&
                    (state == Mr20WifiState.PASSWORD_CHANGED_AWAITING_RESET ||
                        state == Mr20WifiState.OFF)
                ) return@withTimeout
                delay(WIFI_POLL_INTERVAL_MS)
            }
        }
    }

    // ---- simple queries ----------------------------------------------------

    suspend fun queryBattery(): Int =
        exchangeSingle(Mr20Command.QueryBattery, timeouts.simpleCommand) {
            (it as? Mr20Event.Battery)?.percent
        }

    suspend fun querySpace(): Mr20Event.Space =
        exchangeSingle(Mr20Command.QuerySpace, timeouts.simpleCommand) {
            it as? Mr20Event.Space
        }

    suspend fun queryFirmwareVersion(): String =
        exchangeSingle(Mr20Command.QueryFirmware, timeouts.simpleCommand) {
            (it as? Mr20Event.FirmwareVersion)?.version
        }

    suspend fun queryWifiFirmwareVersion(): String =
        exchangeSingle(Mr20Command.QueryWifiFirmware, timeouts.simpleCommand) {
            (it as? Mr20Event.WifiFirmwareVersion)?.version
        }

    suspend fun queryMacAddress(): String =
        exchangeSingle(Mr20Command.QueryMacAddress, timeouts.simpleCommand) {
            (it as? Mr20Event.MacAddress)?.mac
        }

    suspend fun queryRecordingState(): Boolean =
        exchangeSingle(Mr20Command.QueryRecordingState, timeouts.simpleCommand) {
            (it as? Mr20Event.RecordingState)?.isRecording
        }

    suspend fun queryRecordMode(): Mr20RecordMode =
        exchangeSingle(Mr20Command.QueryRecordMode, timeouts.simpleCommand) {
            (it as? Mr20Event.RecordMode)?.mode
        }

    suspend fun queryDeviceTime(): java.time.LocalDateTime =
        exchangeSingle(Mr20Command.QueryDeviceTime, timeouts.simpleCommand) {
            (it as? Mr20Event.DeviceTime)?.toLocalDateTime()
        }

    suspend fun queryUsbMassStorage(): Boolean =
        exchangeSingle(Mr20Command.QueryUsbMassStorage, timeouts.simpleCommand) {
            (it as? Mr20Event.UsbMassStorage)?.enabled
        }

    suspend fun queryWifiState(): Mr20WifiState =
        exchangeSingle(Mr20Command.QueryWifiState, timeouts.simpleCommand) {
            (it as? Mr20Event.WifiStateChanged)?.state
        }

    suspend fun queryWifiCredentials(): Mr20Event.WifiCredentials =
        exchangeSingle(Mr20Command.QueryWifiCredentials, timeouts.simpleCommand) {
            it as? Mr20Event.WifiCredentials
        }

    /** Collects everything [Mr20DeviceInfo] holds in one pass. */
    suspend fun readDeviceInfo(): Mr20DeviceInfo {
        val space = runCatching { querySpace() }.getOrNull()
        return Mr20DeviceInfo(
            firmwareVersion = runCatching { queryFirmwareVersion() }.getOrNull(),
            wifiFirmwareVersion = runCatching { queryWifiFirmwareVersion() }.getOrNull(),
            macAddress = runCatching { queryMacAddress() }.getOrNull(),
            batteryPercent = runCatching { queryBattery() }.getOrNull(),
            freeMb = space?.freeMb,
            totalMb = space?.totalMb,
            recordMode = runCatching { queryRecordMode() }.getOrNull(),
            isRecording = runCatching { queryRecordingState() }.getOrDefault(false),
        )
    }

    // ---- commands with a simple ack ----------------------------------------

    suspend fun startRecording(): String =
        exchangeSingle(Mr20Command.StartRecording, timeouts.simpleCommand) {
            (it as? Mr20Event.RecordingStarted)?.fileName
        }

    suspend fun stopRecording() {
        exchangeSingle(Mr20Command.StopRecording, timeouts.simpleCommand) {
            it as? Mr20Event.RecordingStopped
        }
    }

    suspend fun setDeviceTime(dateTime: java.time.LocalDateTime) {
        exchangeSingle(Mr20Commands.setTime(dateTime), timeouts.simpleCommand) {
            it as? Mr20Event.TimeSet
        }
    }

    suspend fun setBitrate(bitrate: Mr20Bitrate) {
        val result = exchangeSingle(Mr20Command.SetBitrate(bitrate), timeouts.simpleCommand) {
            it as? Mr20Event.BitrateResult
        }
        if (!result.success) throw Mr20Exception.DeviceError("device rejected ${bitrate.kbps}kbps")
    }

    suspend fun setUsbMassStorage(enabled: Boolean): Boolean =
        exchangeSingle(Mr20Command.SetUsbMassStorage(enabled), timeouts.simpleCommand) {
            (it as? Mr20Event.UsbMassStorage)?.enabled
        }

    suspend fun deleteFile(directory: String, fileName: String) {
        val result = exchangeSingle(
            Mr20Command.DeleteFile(directory, fileName),
            timeouts.simpleCommand,
        ) { event ->
            when (event) {
                is Mr20Event.FileDeleted, is Mr20Event.FileDeleteFailed -> event
                else -> null
            }
        }
        if (result is Mr20Event.FileDeleteFailed) {
            throw Mr20Exception.DeviceError("device refused to delete $directory/$fileName")
        }
    }

    suspend fun openWifi() {
        exchangeSingle(Mr20Command.OpenWifi, timeouts.simpleCommand) {
            it as? Mr20Event.WifiOpened
        }
    }

    /**
     * Sends `AA_BLE&WIFIC`. The device ignores it in states 4/5/6, so we check
     * first and report that rather than hanging until the timeout.
     */
    suspend fun closeWifi() {
        val state = queryWifiState()
        if (state.isCloseInhibited) {
            throw Mr20Exception.DeviceError(
                "cannot close Wi-Fi while device is in state ${state.name} (${state.code})"
            )
        }
        if (state == Mr20WifiState.OFF || state == Mr20WifiState.AUTO_CLOSED) return
        exchangeSingle(Mr20Command.CloseWifi, timeouts.simpleCommand) {
            it as? Mr20Event.WifiClosed
        }
    }

    /**
     * Brings the AP up and waits until the phone may associate to it.
     *
     * Mirrors the documented flow: send `AA_BLE&WIFIO`, then poll `AA_BLE&WIFIS`
     * once a second until the state is `2` (up, no client yet).
     */
    suspend fun openWifiAndAwaitAp(timeoutMs: Long = 30_000): Mr20Event.WifiCredentials {
        openWifi()
        withTimeout(timeoutMs) {
            while (true) {
                if (queryWifiState().isApReadyForClient) return@withTimeout
                delay(WIFI_POLL_INTERVAL_MS)
            }
        }
        return queryWifiCredentials()
    }

    /** Fire-and-forget commands the device never answers. */
    suspend fun send(command: Mr20Command) {
        commandLock.withLock { commandTransport.write(command.encode()) }
    }

    suspend fun vibrate() = send(Mr20Command.Vibrate)

    // ---- enumerations ------------------------------------------------------

    /**
     * `AA_BLE&LIST_DIRS` — collects `AA_DEV&DIRS&<name>` until
     * `AA_DEV&DIRS_SUM&<count>` arrives.
     *
     * The trailing count is verified: a short enumeration means notifications were
     * dropped, and silently returning a partial list would make the app delete
     * recordings it merely failed to see.
     */
    suspend fun listDirectories(): List<String> {
        val names = mutableListOf<String>()
        val count = exchangeSingle(
            Mr20Command.ListDirectories,
            timeouts.enumeration,
        ) { event ->
            when (event) {
                is Mr20Event.DirectoryEntry -> { names += event.name; null }
                is Mr20Event.DirectoryCount -> event.count
                else -> null
            }
        }
        if (names.size != count) {
            throw Mr20Exception.DeviceError(
                "directory enumeration incomplete: got ${names.size} of $count"
            )
        }
        return names
    }

    /** `AA_BLE&LIST&<dir>` — same contract as [listDirectories]. */
    suspend fun listFiles(directory: String): List<Mr20File> {
        val files = mutableListOf<Mr20File>()
        val count = exchangeSingle(
            Mr20Command.ListFiles(directory),
            timeouts.enumeration,
        ) { event ->
            when (event) {
                is Mr20Event.FileEntry -> {
                    files += Mr20File(
                        directory = event.directory,
                        name = event.fileName,
                        durationSeconds = event.durationSeconds,
                        sizeBytes = event.sizeBytes,
                    )
                    null
                }

                is Mr20Event.FileCount -> event.count
                else -> null
            }
        }
        if (files.size != count) {
            throw Mr20Exception.DeviceError(
                "file enumeration for '$directory' incomplete: got ${files.size} of $count"
            )
        }
        return files
    }

    /** Every recording on the device, across all date folders. */
    suspend fun listAllFiles(): List<Mr20File> =
        listDirectories().flatMap { listFiles(it) }

    // ---- file sync ---------------------------------------------------------

    /**
     * Pulls one recording over BLE.
     *
     * @param haveBytes bytes already stored locally; pass null for a fresh pull.
     *   This selects between the spec's "指令1" and "指令2" forms.
     * @param sink receives file bytes in order. Must not suspend for long — it
     *   runs on the BLE callback path.
     * @param onProgress optional, called after each packet with 0..1 (or null when
     *   the device announced no length).
     * @return the number of bytes written to [sink].
     */
    suspend fun pullFileOverBle(
        directory: String,
        fileName: String,
        haveBytes: Long? = null,
        onProgress: ((Float?) -> Unit)? = null,
        sink: (ByteArray, Int, Int) -> Unit,
    ): Long = commandLock.withLock {
        val command = Mr20Command.PullFileOverBle(directory, fileName, haveBytes)

        // Arm the receiver BEFORE writing the request.
        //
        // The length arrives as `AA_DEV&U&LEN` on the command characteristic (…a3)
        // while the file bytes arrive on the data characteristic (…a1). Those are
        // independent GATT notifications, so the stack may hand us the first data
        // packet before our handler for the length notification has run. Waiting for
        // the length first would silently route those leading bytes into the live
        // audio stream and truncate the file.
        val transfer = ActiveTransfer(
            Mr20FileReceiver(
                expectedBytes = 0, // filled in by announceLength below
                channel = Mr20TransferChannel.BLE,
                sink = sink,
            ),
            onProgress = onProgress,
        )
        val receiver = transfer.receiver
        activeTransfer = transfer

        // The command channel can end a transfer early; the data channel ends it
        // normally. Watch both, and let whichever fires first settle `finished`.
        val watcher = scope.launch {
            events.collect { event ->
                when (event) {
                    is Mr20Event.TransferBeginning ->
                        if (event.channel == Mr20TransferChannel.BLE) {
                            receiver.announceLength(event.lengthBytes)
                        }

                    is Mr20Event.TransferComplete -> receiver.onDeviceFinished()
                    is Mr20Event.TransferAborted -> receiver.abort("device sent AA_DEV&SHUT")
                    is Mr20Event.TransferFailed -> receiver.abort("device sent AA_DEV&U&ERR")
                    is Mr20Event.DiskFull -> receiver.abort("device storage full")
                    else -> Unit
                }
                onProgress?.invoke(receiver.progress)
                transfer.settleIfDone()
            }
        }

        try {
            // The device answers `AA_DEV&U&LEN` (or `AA_DEV&U&ERR`) first; only then
            // is a size-derived timeout meaningful.
            val begin = withTimeoutOrProtocolError(command, timeouts.transferStart) {
                events.onSubscription { commandTransport.write(command.encode()) }
                    .mapNotNull { event ->
                        when (event) {
                            is Mr20Event.TransferBeginning ->
                                event.takeIf { it.channel == Mr20TransferChannel.BLE }

                            is Mr20Event.TransferFailed ->
                                throw Mr20Exception.TransferFailed(
                                    "device could not open $directory/$fileName"
                                )

                            else -> null
                        }
                    }
                    .first()
            }

            withTimeoutOrProtocolError(command, transferTimeoutFor(begin.lengthBytes)) {
                transfer.finished.await()
            }
        } finally {
            watcher.cancel()
            activeTransfer = null
        }

        if (receiver.state != Mr20FileReceiver.State.COMPLETE) {
            throw Mr20Exception.TransferFailed(
                receiver.failure ?: "transfer of $directory/$fileName did not complete"
            )
        }
        receiver.receivedBytes
    }

    /**
     * Asks the device to push a recording over the Wi-Fi AP socket.
     *
     * Only the BLE half happens here — sending `AA_BLE&W&…` and reading back
     * `AA_DEV&W&LEN`. The caller then reads [Mr20Event.TransferBeginning.lengthBytes]
     * bytes plus the 5-byte trailer from a socket to
     * [Mr20Protocol.WIFI_HOST]:[Mr20Protocol.WIFI_PORT], feeding them to an
     * [Mr20FileReceiver] built with [Mr20TransferChannel.WIFI]. Splitting it this
     * way keeps sockets out of the protocol module.
     */
    suspend fun requestFileOverWifi(
        directory: String,
        fileName: String,
        haveBytes: Long? = null,
    ): Mr20Event.TransferBeginning {
        val command = Mr20Command.PullFileOverWifi(directory, fileName, haveBytes)
        return exchangeSingle(command, timeouts.transferStart) { event ->
            when (event) {
                is Mr20Event.TransferBeginning -> event.takeIf {
                    it.channel == Mr20TransferChannel.WIFI
                }

                is Mr20Event.TransferFailed ->
                    throw Mr20Exception.TransferFailed(
                        "device could not open $directory/$fileName"
                    )

                else -> null
            }
        }
    }

    /** `AA_BLE&SHUT` — cancels whatever transfer is in flight. */
    suspend fun abortTransfer() {
        activeTransfer?.receiver?.abort("aborted by app")
        exchangeSingle(Mr20Command.AbortTransfer, timeouts.simpleCommand) {
            it as? Mr20Event.TransferAborted
        }
    }

    // ---- OTA ---------------------------------------------------------------

    /**
     * Runs a full firmware update.
     *
     * Holds [commandLock] for the entire session because the spec is explicit:
     * "OTA过程中,禁止APP发送其他指令,否则会OTA失败."
     *
     * @param frameDelayMs inter-frame delay; the spec floor is 8ms and it is
     *   enforced here.
     */
    suspend fun performOta(
        session: Mr20OtaSession,
        frameDelayMs: Long = Mr20Protocol.OTA_MIN_FRAME_INTERVAL_MS,
        onProgress: ((sent: Int, total: Int) -> Unit)? = null,
    ) = commandLock.withLock {
        require(frameDelayMs >= Mr20Protocol.OTA_MIN_FRAME_INTERVAL_MS) {
            "OTA frame delay must be >= ${Mr20Protocol.OTA_MIN_FRAME_INTERVAL_MS}ms"
        }

        val begin = session.beginCommand
        withTimeoutOrProtocolError(begin, timeouts.otaReady) {
            events.onSubscription { commandTransport.write(begin.encode()) }
                .filter { it is Mr20Event.OtaReady }
                .first()
        }

        var sent = 0
        for (frame in session.frames()) {
            commandTransport.write(frame)
            sent++
            onProgress?.invoke(sent, session.frameCount)
            delay(frameDelayMs)
        }

        val finish = Mr20Command.FinishOta
        val outcome = withTimeoutOrProtocolError(finish, timeouts.otaFinish) {
            events.onSubscription { commandTransport.write(finish.encode()) }
                .mapNotNull { event ->
                    when (event) {
                        is Mr20Event.OtaAccepted -> event
                        is Mr20Event.OtaFailed ->
                            throw Mr20Exception.DeviceError("device rejected the OTA image")

                        is Mr20Event.WifiOtaFailed ->
                            throw Mr20Exception.DeviceError("Wi-Fi module failed to flash")

                        else -> null
                    }
                }
                .first()
        }
        check(outcome is Mr20Event.OtaAccepted)
    }

    // ---- exchange plumbing -------------------------------------------------

    /**
     * Writes [command] and returns the first event [match] maps to non-null.
     *
     * Subscription is established *before* the write via [onSubscription], which
     * closes the race where a fast device replies before we start collecting.
     *
     * This also covers the two streaming enumerations: there, [match] returns
     * non-null only for the terminator (`AA_DEV&DIRS_SUM` / `AA_DEV&LIST`) and the
     * entries in between are accumulated by side effect.
     */
    private suspend fun <T : Any> exchangeSingle(
        command: Mr20Command,
        timeoutMs: Long,
        match: (Mr20Event) -> T?,
    ): T = commandLock.withLock {
        withTimeoutOrProtocolError(command, timeoutMs) {
            events.onSubscription { commandTransport.write(command.encode()) }
                .mapNotNull(match)
                .first()
        }
    }

    private suspend fun <T> withTimeoutOrProtocolError(
        command: Mr20Command,
        timeoutMs: Long,
        block: suspend () -> T,
    ): T = try {
        withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        if (!isAuthenticated) throw Mr20Exception.NotAuthenticated()
        throw Mr20Exception.Timeout(command.wire, timeoutMs)
    }

    /**
     * BLE throughput is roughly 5-15 kB/s at a 244-byte MTU, so a budget derived
     * from the file size beats any fixed timeout.
     */
    private fun transferTimeoutFor(lengthBytes: Long): Long {
        if (lengthBytes <= 0) return timeouts.transferIdle * 6
        val estimatedMs = lengthBytes * 1000 / MIN_ASSUMED_BYTES_PER_SECOND
        return (estimatedMs * 2).coerceAtLeast(timeouts.transferIdle)
    }

    private companion object {
        const val WIFI_POLL_INTERVAL_MS = 1_000L

        /** Deliberately pessimistic, so the budget errs on the side of patience. */
        const val MIN_ASSUMED_BYTES_PER_SECOND = 2_000L
    }
}
