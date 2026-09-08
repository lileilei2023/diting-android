package com.diting.app.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.diting.app.DitingPermissions
import com.diting.app.data.db.DeviceEntity
import com.diting.app.data.db.DeviceDao
import com.diting.app.device.ble.BleGattConnection
import com.diting.app.device.wifi.Mr20WifiTransfer
import com.diting.domain.model.DeviceKind
import com.diting.protocol.mr20.Mr20Client
import com.diting.protocol.mr20.Mr20DeviceInfo
import com.diting.protocol.mr20.Mr20Event
import com.diting.protocol.mr20.Mr20Exception
import com.diting.protocol.mr20.Mr20File
import com.diting.protocol.mr20.Mr20Protocol
import com.diting.protocol.mr20.Mr20WifiState
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** A device found while scanning. */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Advertised the MR20 service UUID, or carries an MR20-looking name. */
    val isLikelyMr20: Boolean = false,
)

/** What the sync engine is doing, for the device screen. */
sealed interface SyncProgress {
    data object Idle : SyncProgress
    data class Listing(val directoriesDone: Int) : SyncProgress

    /** Wi-Fi was preferred but could not be brought up; continuing over BLE. */
    data class FallingBackToBle(val reason: String) : SyncProgress
    data class Transferring(
        val fileName: String,
        val index: Int,
        val total: Int,
        val fraction: Float?,
        val fileBytes: Long = 0,
        /** Files after this one in the queue, so the user can size the wait. */
        val remainingBytes: Long = 0,
    ) : SyncProgress

    /** [skippedLarge] recordings were left on the card: too big for BLE (see [Mr20DeviceManager.MAX_BLE_AUTO_BYTES]). */
    data class Done(val filesSynced: Int, val bytes: Long, val skippedLarge: Int = 0) : SyncProgress
    data class Failed(val reason: String) : SyncProgress
}

/**
 * Owns the connection to an MR20 and everything that flows over it.
 *
 * The bind key deserves a note. `AA_BLE&SK&PWD` *sets* the key on first
 * connection and *presents* it afterwards, with no way to ask the device which
 * one it holds. So the key is generated once, stored, and the value persisted is
 * the one the device actually kept — the input truncated to 16 characters. Losing
 * it means the user must run `AA_BLE&SK&RESET`, which also drops the link.
 */
@Singleton
class Mr20DeviceManager @Inject constructor(
    private val context: Context,
    private val deviceDao: DeviceDao,
    private val wifiTransfer: Mr20WifiTransfer,
    private val scope: CoroutineScope,
) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    private var connection: BleGattConnection? = null

    private val _client = MutableStateFlow<Mr20Client?>(null)
    val client: StateFlow<Mr20Client?> = _client.asStateFlow()

    private val _info = MutableStateFlow(Mr20DeviceInfo())
    val info: StateFlow<Mr20DeviceInfo> = _info.asStateFlow()

    private val _sync = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val sync: StateFlow<SyncProgress> = _sync.asStateFlow()

    /** Files still on the card after the last listing: (count, bytes). */
    data class Outstanding(val files: Int, val bytes: Long)

    private val _outstanding = MutableStateFlow<Outstanding?>(null)
    val outstanding: StateFlow<Outstanding?> = _outstanding.asStateFlow()

    /** Only one sync at a time; the protocol allows one outstanding command. */
    private val syncLock = Mutex()

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    /** Device events, for the UI to react to `AA_DEV&DISK&ERR` and friends. */
    val events: SharedFlow<Mr20Event>? get() = _client.value?.events

    // ---- discovery ---------------------------------------------------------

    /**
     * Scans for nearby BLE devices, flagging the ones that look like an MR20.
     *
     * No hardware filter: on the real card the advertisement does not reliably
     * carry the service UUID (a UUID-filtered scan returned zero results every
     * time, while the unfiltered Echo bridge found it at once). The UUID is the
     * protocol's identity, but it is confirmed at GATT discovery, not here.
     * Unnamed devices are dropped unless they advertise the UUID — a room full
     * of appliances is otherwise unreadable.
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        // startScan throws SecurityException without BLUETOOTH_SCAN, which takes
        // the process with it. Closing the flow instead puts the reason in the
        // dialog the pairing screen already shows.
        val denied = runCatching { DitingPermissions.requireBluetooth(context) }.exceptionOrNull()
        if (denied != null) {
            close(denied)
            return@callbackFlow
        }

        val scanner = adapter?.bluetoothLeScanner
            ?: run {
                close(IllegalStateException("蓝牙不可用，请先打开蓝牙"))
                return@callbackFlow
            }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val knownAddresses = runCatching { deviceDao.knownAddresses() }.getOrDefault(emptyList())

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName
                val advertisesService = result.scanRecord?.serviceUuids
                    ?.any { it.uuid == Mr20Protocol.SERVICE_UUID } == true
                // The real card advertises as "YLF20_<mac tail>" (verified on
                // hardware); other firmware builds may say MR20. Previously
                // paired addresses count too.
                val looksLikeMr20 = advertisesService ||
                    (name?.uppercase()?.let { n -> LIKELY_NAMES.any { it in n } } == true) ||
                    result.device.address in knownAddresses
                if (name.isNullOrBlank() && !advertisesService) return
                trySend(
                    DiscoveredDevice(
                        address = result.device.address,
                        name = name,
                        rssi = result.rssi,
                        isLikelyMr20 = looksLikeMr20,
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("扫描失败（errorCode=$errorCode）"))
            }
        }

        scanner.startScan(emptyList<ScanFilter>(), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    // ---- connect & pair ----------------------------------------------------

    /**
     * Connects and authenticates.
     *
     * @param address BLE MAC.
     * @param existingKey the stored bind key, or null to generate one on first pair.
     * @return the key the device accepted; persist it.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(address: String, existingKey: String?): String {
        // Every BluetoothGatt call below needs BLUETOOTH_CONNECT. Checked once
        // here rather than at each call so the failure arrives before a
        // half-open connection has to be unwound.
        DitingPermissions.requireBluetooth(context)

        disconnect()

        val adapter = adapter ?: throw IllegalStateException("蓝牙不可用")
        val remote = adapter.getRemoteDevice(address)

        val gatt = BleGattConnection(context, remote)
        connection = gatt
        gatt.connect()

        val mr20 = Mr20Client(gatt.commandTransport, gatt.dataTransport, scope).also { it.start() }
        _client.value = mr20

        val key = existingKey ?: generateBindKey()
        val storedKey = mr20.authenticate(key)

        // First pairing only: the AP credentials are derived from the key, and the
        // device needs ~10s plus a reset to apply them. Skipping this leaves the
        // Wi-Fi transfer path unusable.
        if (existingKey == null) {
            runCatching { mr20.syncWifiCredentialsToKey() }
        }

        // Keep the recorder's clock right, or every filename's date is wrong.
        runCatching { mr20.setDeviceTime(java.time.LocalDateTime.now()) }

        val deviceInfo = mr20.readDeviceInfo()
        _info.value = deviceInfo

        // makeDefault clears the flag on every other device, then writes this one.
        deviceDao.makeDefault(
            DeviceEntity(
                id = address,
                kind = DeviceKind.MR20,
                name = remote.name ?: "MR20",
                address = address,
                bindKey = storedKey,
                firmwareVersion = deviceInfo.firmwareVersion,
                wifiFirmwareVersion = deviceInfo.wifiFirmwareVersion,
                batteryPercent = deviceInfo.batteryPercent,
                freeMb = deviceInfo.freeMb,
                totalMb = deviceInfo.totalMb,
                lastSeenEpochMs = System.currentTimeMillis(),
            )
        )

        return storedKey
    }

    fun disconnect() {
        connection?.shutdown()
        connection = null
        _client.value = null
        _info.value = Mr20DeviceInfo()
    }

    /**
     * A 16-character key from [SecureRandom], drawn from an alphabet that excludes
     * `&` — the protocol's field separator, which would corrupt the command.
     */
    private fun generateBindKey(): String {
        val random = SecureRandom()
        return (1..Mr20Protocol.SECRET_KEY_LENGTH)
            .map { KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)] }
            .joinToString("")
    }

    // ---- sync --------------------------------------------------------------

    /**
     * Pulls every recording the app does not already have.
     *
     * @param alreadyHave device paths already stored locally, so a re-sync does not
     *   re-download them. Partially-synced files are resumed via `haveBytes`.
     * @param preferWifi use the AP path, which is far faster for long recordings.
     * @param onFile called after each file lands, with (devicePath, localFile, meta).
     */
    suspend fun syncNewRecordings(
        targetDir: File,
        alreadyHave: Map<String, Long>,
        preferWifi: Boolean,
        includeLargeOverBle: Boolean = false,
        onFile: suspend (Mr20File, File) -> Unit,
    ): Int = syncLock.withLock {
        val mr20 = _client.value ?: throw IllegalStateException("设备未连接")

        try {
            // While the card records it streams live MP3 on the same characteristic
            // the file bytes arrive on; a file pulled now would have audio from
            // *this* moment spliced into it. Refuse rather than corrupt.
            if (runCatching { mr20.queryRecordingState() }.getOrDefault(false)) {
                throw Mr20Exception.DeviceError("录音卡正在录音。先停止录音再同步（设备页有「停止录音」按钮）。")
            }

            _sync.value = SyncProgress.Listing(0)
            val all = mr20.listAllFiles()

            // Newest first: what the user wants to see is what just happened, and
            // BLE moves tens of KB/s — a card with months of recordings would
            // otherwise spend the first hours on the oldest ones.
            val outstanding = all.filter { file ->
                val have = alreadyHave[file.path]
                have == null || have < file.sizeBytes
            }.sortedWith(compareByDescending<Mr20File> { it.directory }.thenByDescending { it.name })
            _outstanding.value = Outstanding(outstanding.size, outstanding.sumOf { it.sizeBytes })

            if (outstanding.isEmpty()) {
                _sync.value = SyncProgress.Done(0, 0)
                return@withLock 0
            }

            var wifiFailure: String? = null
            if (preferWifi) {
                try {
                    val bytes = syncOverWifi(mr20, outstanding, targetDir, alreadyHave, onFile)
                    _sync.value = SyncProgress.Done(outstanding.size, bytes)
                    return@withLock outstanding.size
                } catch (e: Exception) {
                    // The AP path depends on credentials only a first pairing
                    // writes; a card paired elsewhere may never bring it up.
                    // Falling back is better than a sync that silently stalls.
                    wifiFailure = e.message ?: e::class.simpleName.orEmpty()
                    _sync.value = SyncProgress.FallingBackToBle(wifiFailure)
                }
            }

            // Over BLE a long recording takes tens of minutes; big files wait for
            // a working Wi-Fi path (or a manual choice) rather than blocking
            // everything behind them.
            val (small, large) = if (includeLargeOverBle) outstanding to emptyList()
            else outstanding.partition { it.sizeBytes <= MAX_BLE_AUTO_BYTES }
            val bytesTotal = if (small.isEmpty()) 0L
            else syncOverBle(mr20, small, targetDir, alreadyHave, onFile)

            _sync.value = SyncProgress.Done(small.size, bytesTotal, skippedLarge = large.size)
            _outstanding.value = Outstanding(large.size, large.sumOf { it.sizeBytes })
            small.size
        } catch (e: Exception) {
            _sync.value = SyncProgress.Failed(e.message ?: e::class.simpleName.orEmpty())
            throw e
        }
    }

    private suspend fun syncOverBle(
        mr20: Mr20Client,
        pending: List<Mr20File>,
        targetDir: File,
        alreadyHave: Map<String, Long>,
        onFile: suspend (Mr20File, File) -> Unit,
    ): Long {
        var total = 0L
        var remaining = pending.sumOf { it.sizeBytes }
        pending.forEachIndexed { index, file ->
            val target = localFileFor(targetDir, file)
            val have = alreadyHave[file.path]?.takeIf { it > 0 && target.exists() }
            remaining -= file.sizeBytes

            _sync.value = SyncProgress.Transferring(
                file.name, index + 1, pending.size, 0f, file.sizeBytes, remaining,
            )

            FileOutputStream(target, /* append = */ have != null).use { out ->
                total += mr20.pullFileOverBle(
                    directory = file.directory,
                    fileName = file.name,
                    haveBytes = have,
                    onProgress = { fraction ->
                        _sync.value = SyncProgress.Transferring(
                            file.name, index + 1, pending.size, fraction, file.sizeBytes, remaining,
                        )
                    },
                ) { buffer, offset, length -> out.write(buffer, offset, length) }
            }
            onFile(file, target)
        }
        return total
    }

    /**
     * The Wi-Fi path: bring the AP up once, pull everything, close it once.
     *
     * The device drops its AP when BLE disconnects and after 30s idle, so the BLE
     * link stays open the whole time and the requests keep flowing over it.
     */
    private suspend fun syncOverWifi(
        mr20: Mr20Client,
        pending: List<Mr20File>,
        targetDir: File,
        alreadyHave: Map<String, Long>,
        onFile: suspend (Mr20File, File) -> Unit,
    ): Long {
        val credentials = mr20.openWifiAndAwaitAp()

        return try {
            wifiTransfer.withDeviceNetwork(credentials.ssid, credentials.password) { network ->
                var total = 0L
                pending.forEachIndexed { index, file ->
                    val target = localFileFor(targetDir, file)
                    val have = alreadyHave[file.path]?.takeIf { it > 0 && target.exists() }

                    _sync.value = SyncProgress.Transferring(file.name, index + 1, pending.size, 0f)

                    val begin = mr20.requestFileOverWifi(file.directory, file.name, have)
                    FileOutputStream(target, have != null).use { out ->
                        total += wifiTransfer.receiveFile(
                            network = network,
                            expectedBytes = begin.lengthBytes,
                            onProgress = { fraction ->
                                _sync.value = SyncProgress.Transferring(
                                    file.name, index + 1, pending.size, fraction,
                                )
                            },
                            sink = out,
                        )
                    }
                    onFile(file, target)
                }
                total
            }
        } finally {
            // Best effort: the device auto-closes Wi-Fi when BLE drops anyway, and
            // states 4/5/6 refuse the command outright.
            runCatching { mr20.closeWifi() }
        }
    }

    /** `2025-08-13/REC0001.MP3` becomes `2025-08-13_REC0001.MP3` locally. */
    private fun localFileFor(dir: File, file: Mr20File): File {
        dir.mkdirs()
        return File(dir, "${file.directory}_${file.name}".replace('/', '_'))
    }

    /** Deletes a recording from the device once it is safely stored locally. */
    suspend fun deleteFromDevice(file: Mr20File) {
        val mr20 = _client.value ?: throw IllegalStateException("设备未连接")
        mr20.deleteFile(file.directory, file.name)
    }

    /** Outcome of [testWifi], for the device screen to explain. */
    data class WifiTest(val ssid: String, val password: String, val joined: Boolean, val detail: String)

    enum class WifiAssistPhase { OPENING, TRYING, MANUAL_WAIT, JOINED, FAILED }

    /** Live state of the Wi-Fi assist flow, rendered by the device screen. */
    data class WifiAssist(
        val phase: WifiAssistPhase,
        val ssid: String = "",
        val password: String = "",
        val detail: String = "",
        /** Seconds left in the manual-join window. */
        val secondsLeft: Int = 0,
    )

    private val _wifiAssist = MutableStateFlow<WifiAssist?>(null)
    val wifiAssist: StateFlow<WifiAssist?> = _wifiAssist.asStateFlow()
    private var wifiAssistJob: kotlinx.coroutines.Job? = null

    /**
     * Brings the AP up and gets the phone onto it, one way or another.
     *
     * The in-app request (`WifiNetworkSpecifier`) is tried once. On this
     * Samsung build it fails deterministically once the AP has been approved
     * before ("Network not present in config manager"), so the flow then holds
     * the AP open and waits for the user to join from system settings,
     * re-opening the AP whenever the card's 30 s idle timer closes it. The
     * moment the card reports a client and the socket answers, the assist
     * reports JOINED and a following sync uses that network directly.
     */
    fun startWifiAssist(windowSeconds: Int = 150) {
        wifiAssistJob?.cancel()
        wifiAssistJob = scope.launch {
            val mr20 = _client.value
            if (mr20 == null) {
                _wifiAssist.value = WifiAssist(WifiAssistPhase.FAILED, detail = "设备未连接")
                return@launch
            }
            _wifiAssist.value = WifiAssist(WifiAssistPhase.OPENING)
            val credentials = try {
                syncLock.withLock { mr20.openWifiAndAwaitAp() }
            } catch (e: Exception) {
                _wifiAssist.value = WifiAssist(WifiAssistPhase.FAILED, detail = "录音卡热点没起来：${e.message}")
                return@launch
            }
            val ssid = credentials.ssid
            val password = credentials.password
            _wifiAssist.value = WifiAssist(WifiAssistPhase.TRYING, ssid, password, "系统正在尝试自动加入…")

            // One in-app attempt, ~10 s. Success here means no manual step at all.
            val auto = runCatching {
                wifiTransfer.withDeviceNetwork(ssid, password, attempts = 1) { wifiTransfer.probe(it) }
            }
            if (auto.isSuccess) {
                _wifiAssist.value = WifiAssist(WifiAssistPhase.JOINED, ssid, password, "已加入录音卡热点，可以同步了。")
                return@launch
            }

            // Manual window: keep the AP alive and watch for a client.
            var left = windowSeconds
            while (left > 0) {
                _wifiAssist.value = WifiAssist(
                    WifiAssistPhase.MANUAL_WAIT, ssid, password,
                    "自动加入被系统拒绝。请到 系统设置 › Wi-Fi 连接这个热点（提示无网络时选保持连接），连上后自动继续。",
                    left,
                )
                val state = runCatching { syncLock.withLock { mr20.queryWifiState() } }.getOrNull()
                if (state == Mr20WifiState.CONNECTED || wifiTransfer.findDeviceNetwork() != null) {
                    if (wifiTransfer.findDeviceNetwork() != null) {
                        _wifiAssist.value = WifiAssist(WifiAssistPhase.JOINED, ssid, password, "已连上录音卡热点，可以同步了。同步期间请留在这个 Wi-Fi 上。")
                        return@launch
                    }
                }
                if (state == Mr20WifiState.OFF || state == Mr20WifiState.AUTO_CLOSED) {
                    // The card gave up waiting (30 s idle). Bring it back.
                    runCatching { syncLock.withLock { mr20.openWifiAndAwaitAp() } }
                }
                kotlinx.coroutines.delay(3_000)
                left -= 3
            }
            _wifiAssist.value = WifiAssist(WifiAssistPhase.FAILED, ssid, password, "等了 ${windowSeconds} 秒没有连上。可以再试一次。")
            runCatching { syncLock.withLock { mr20.closeWifi() } }
        }
    }

    fun dismissWifiAssist() {
        wifiAssistJob?.cancel()
        wifiAssistJob = null
        _wifiAssist.value = null
    }

    /**
     * Brings the card's AP up and tries to join it, reporting what happened.
     *
     * Joining needs the system's one-time approval dialog on Android 10+; doing it
     * here, deliberately, is what lets a later sync go over Wi-Fi without the user
     * being surprised by a dialog mid-transfer.
     */
    suspend fun testWifi(): WifiTest = syncLock.withLock {
        val mr20 = _client.value ?: throw Mr20Exception.DeviceError("设备未连接")
        val credentials = mr20.openWifiAndAwaitAp()
        try {
            wifiTransfer.withDeviceNetwork(credentials.ssid, credentials.password) { network ->
                wifiTransfer.probe(network)
            }
            WifiTest(credentials.ssid, credentials.password, joined = true,
                detail = "已加入录音卡热点并连通 ${Mr20Protocol.WIFI_HOST}:${Mr20Protocol.WIFI_PORT}，之后同步可以走 Wi-Fi。")
        } catch (e: Exception) {
            WifiTest(credentials.ssid, credentials.password, joined = false,
                detail = e.message ?: e::class.simpleName.orEmpty())
        } finally {
            runCatching { mr20.closeWifi() }
        }
    }

    /** Stops the card's current recording so a sync can run clean. */
    suspend fun stopRecording() {
        val mr20 = _client.value ?: throw Mr20Exception.DeviceError("设备未连接")
        mr20.stopRecording()
        _info.value = _info.value.copy(isRecording = false)
    }

    suspend fun refreshInfo(): Mr20DeviceInfo {
        val mr20 = _client.value ?: throw Mr20Exception.DeviceError("设备未连接")
        return mr20.readDeviceInfo().also { _info.value = it }
    }

    companion object {
        /** No '&': it is the protocol's field separator. */
        private const val KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** Advertised-name fragments that identify the recorder. */
        private val LIKELY_NAMES = listOf("MR20", "MR-20", "YLF20", "YLF")

        /** Largest recording pulled automatically over BLE (~5 MB ≈ 10–20 min at 32 kbps). */
        const val MAX_BLE_AUTO_BYTES = 5L * 1024 * 1024
    }
}
