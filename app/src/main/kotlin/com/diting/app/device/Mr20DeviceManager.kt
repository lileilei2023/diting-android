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
)

/** What the sync engine is doing, for the device screen. */
sealed interface SyncProgress {
    data object Idle : SyncProgress
    data class Listing(val directoriesDone: Int) : SyncProgress
    data class Transferring(
        val fileName: String,
        val index: Int,
        val total: Int,
        val fraction: Float?,
    ) : SyncProgress

    data class Done(val filesSynced: Int, val bytes: Long) : SyncProgress
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

    /** Only one sync at a time; the protocol allows one outstanding command. */
    private val syncLock = Mutex()

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    /** Device events, for the UI to react to `AA_DEV&DISK&ERR` and friends. */
    val events: SharedFlow<Mr20Event>? get() = _client.value?.events

    // ---- discovery ---------------------------------------------------------

    /**
     * Scans for recorders advertising the MR20 service UUID.
     *
     * Filtering by service UUID rather than by name: the advertised name varies
     * across firmware builds, but the service is the protocol's identity.
     */
    @SuppressLint("MissingPermission")
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
            ?: run {
                close(IllegalStateException("蓝牙不可用，请先打开蓝牙"))
                return@callbackFlow
            }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Mr20Protocol.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(
                    DiscoveredDevice(
                        address = result.device.address,
                        name = result.device.name ?: result.scanRecord?.deviceName,
                        rssi = result.rssi,
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("扫描失败（errorCode=$errorCode）"))
            }
        }

        scanner.startScan(listOf(filter), settings, callback)
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
        onFile: suspend (Mr20File, File) -> Unit,
    ): Int = syncLock.withLock {
        val mr20 = _client.value ?: throw IllegalStateException("设备未连接")

        try {
            _sync.value = SyncProgress.Listing(0)
            val all = mr20.listAllFiles()

            val pending = all.filter { file ->
                val have = alreadyHave[file.path]
                have == null || have < file.sizeBytes
            }

            if (pending.isEmpty()) {
                _sync.value = SyncProgress.Done(0, 0)
                return@withLock 0
            }

            val bytesTotal = if (preferWifi) {
                syncOverWifi(mr20, pending, targetDir, alreadyHave, onFile)
            } else {
                syncOverBle(mr20, pending, targetDir, alreadyHave, onFile)
            }

            _sync.value = SyncProgress.Done(pending.size, bytesTotal)
            pending.size
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
        pending.forEachIndexed { index, file ->
            val target = localFileFor(targetDir, file)
            val have = alreadyHave[file.path]?.takeIf { it > 0 && target.exists() }

            _sync.value = SyncProgress.Transferring(file.name, index + 1, pending.size, 0f)

            FileOutputStream(target, /* append = */ have != null).use { out ->
                total += mr20.pullFileOverBle(
                    directory = file.directory,
                    fileName = file.name,
                    haveBytes = have,
                    onProgress = { fraction ->
                        _sync.value = SyncProgress.Transferring(
                            file.name, index + 1, pending.size, fraction,
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

    suspend fun refreshInfo(): Mr20DeviceInfo {
        val mr20 = _client.value ?: throw Mr20Exception.DeviceError("设备未连接")
        return mr20.readDeviceInfo().also { _info.value = it }
    }

    private companion object {
        /** No '&': it is the protocol's field separator. */
        const val KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
