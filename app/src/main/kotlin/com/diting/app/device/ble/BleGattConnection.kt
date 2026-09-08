package com.diting.app.device.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.diting.protocol.common.ByteStreamTransport
import com.diting.protocol.common.PacketTransport
import com.diting.protocol.common.TransportException
import com.diting.protocol.mr20.Mr20Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.Executors

enum class BleConnectionState { DISCONNECTED, CONNECTING, DISCOVERING, READY, FAILED }

/**
 * Wraps one `BluetoothGatt` and exposes it as the two transports the MR20
 * protocol layer expects.
 *
 * Three Android BLE facts shape this class, and each of them is a common source
 * of flaky recorder apps:
 *
 *  1. **One GATT operation at a time.** The stack silently drops a write issued
 *     before the previous one's callback arrives. [operationLock] plus
 *     [pendingWrite] serialise them.
 *  2. **Notifications need both a local enable and a CCCD write.**
 *     `setCharacteristicNotification` alone changes nothing on the wire.
 *  3. **The default 23-byte MTU cannot carry a 244-byte OTA frame.** The MTU is
 *     negotiated during setup and the result is reported through
 *     [PacketTransport.maxFrameSize] so the protocol layer can refuse rather than
 *     silently truncate.
 */
@SuppressLint("MissingPermission") // callers hold BLUETOOTH_CONNECT; see DeviceService
class BleGattConnection(
    private val context: Context,
    private val device: BluetoothDevice,
) {

    private val _state = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var audioChar: BluetoothGattCharacteristic? = null

    @Volatile private var negotiatedMtu: Int = DEFAULT_ATT_MTU

    /** Only one GATT operation may be outstanding — see the class doc. */
    private val operationLock = Mutex()

    @Volatile private var pendingWrite: CompletableDeferred<Unit>? = null

    @Volatile private var pendingDescriptorWrite: CompletableDeferred<Unit>? = null

    private val readySignal = CompletableDeferred<Unit>()

    /**
     * Command-channel notifications (`…a3`).
     *
     * SUSPEND rather than DROP: a lost `AA_DEV&DIRS_SUM` would make an
     * enumeration hang, and a lost `AA_DEV&OFF` would strand a file transfer.
     */
    private val commandFrames = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND,
    )

    /**
     * Data-channel notifications (`…a1`) — live MP3 or file-sync bytes.
     *
     * Also SUSPEND: during a file sync every one of these frames is file content
     * that must be written. Back-pressure here slows the transfer, which is
     * correct; dropping would corrupt the recording.
     */
    private val dataFrames = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.SUSPEND,
    )

    val commandTransport: PacketTransport = object : PacketTransport {
        override val incoming: Flow<ByteArray> = commandFrames.asSharedFlow()
        override val isConnected: Boolean get() = _state.value == BleConnectionState.READY
        override val maxFrameSize: Int get() = negotiatedMtu - ATT_HEADER_BYTES
        override suspend fun write(frame: ByteArray) = writeFrame(frame)
    }

    val dataTransport: ByteStreamTransport = object : ByteStreamTransport {
        override val incoming: Flow<ByteArray> = dataFrames.asSharedFlow()
        override val isConnected: Boolean get() = _state.value == BleConnectionState.READY
    }

    /** Connects, discovers services, raises the MTU and subscribes to both channels. */
    suspend fun connect(timeoutMs: Long = CONNECT_TIMEOUT_MS) {
        check(gatt == null) { "connection already established" }
        _state.value = BleConnectionState.CONNECTING

        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

        try {
            withTimeout(timeoutMs) { readySignal.await() }
        } catch (e: Exception) {
            close()
            throw TransportException(
                _failure.value ?: "连接 ${device.address} 超时",
                e,
            )
        }
    }

    fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        commandChar = null
        audioChar = null
        if (_state.value != BleConnectionState.FAILED) {
            _state.value = BleConnectionState.DISCONNECTED
        }
    }

    private suspend fun writeFrame(frame: ByteArray) = operationLock.withLock {
        val target = writeChar ?: throw TransportException("写特征尚未就绪")
        val connection = gatt ?: throw TransportException("BLE 已断开")

        val limit = negotiatedMtu - ATT_HEADER_BYTES
        if (frame.size > limit) {
            throw TransportException(
                "帧长 ${frame.size} 超过协商后的 MTU 上限 $limit —— " +
                    "OTA 需要 ${Mr20Protocol.OTA_FRAME_SIZE} 字节，请确认 MTU 协商成功"
            )
        }

        val ack = CompletableDeferred<Unit>()
        pendingWrite = ack
        Log.i(TAG, "→ a2: ${frame.toString(Charsets.UTF_8)}")

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            connection.writeCharacteristic(
                target,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                target.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                target.value = frame
                connection.writeCharacteristic(target)
            }
        }

        if (!started) {
            pendingWrite = null
            throw TransportException("BLE 写入未能入队（设备可能已断开）")
        }

        try {
            withTimeout(WRITE_TIMEOUT_MS) { ack.await() }
        } catch (e: Exception) {
            throw TransportException("BLE 写入超时", e)
        } finally {
            pendingWrite = null
        }
    }

    private suspend fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        val connection = gatt ?: throw TransportException("BLE 已断开")

        if (!connection.setCharacteristicNotification(characteristic, true)) {
            throw TransportException("无法开启 ${characteristic.uuid} 的通知")
        }

        // The local enable above does nothing on the wire; the CCCD write is what
        // actually asks the peripheral to start notifying.
        val cccd = characteristic.getDescriptor(Mr20Protocol.CCCD_UUID)
            ?: throw TransportException("${characteristic.uuid} 缺少 CCCD 描述符")

        val ack = CompletableDeferred<Unit>()
        pendingDescriptorWrite = ack

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            connection.writeDescriptor(
                cccd,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                connection.writeDescriptor(cccd)
            }
        }
        if (!started) {
            pendingDescriptorWrite = null
            throw TransportException("CCCD 写入未能入队")
        }

        try {
            withTimeout(WRITE_TIMEOUT_MS) { ack.await() }
        } finally {
            pendingDescriptorWrite = null
        }
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = BleConnectionState.DISCOVERING
                    // Raise the MTU before discovery: a 244-byte OTA frame cannot
                    // fit the default 23, and the peripheral must agree first.
                    g.requestMtu(REQUESTED_MTU)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (!readySignal.isCompleted) {
                        _failure.value = "连接断开（status=$status）"
                        _state.value = BleConnectionState.FAILED
                        readySignal.completeExceptionally(
                            TransportException("连接断开（status=$status）")
                        )
                    } else {
                        _state.value = BleConnectionState.DISCONNECTED
                    }
                    // Fail any operation still waiting, so callers see an error
                    // instead of sitting out the full timeout.
                    pendingWrite?.completeExceptionally(TransportException("BLE 已断开"))
                    pendingDescriptorWrite
                        ?.completeExceptionally(TransportException("BLE 已断开"))
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            // A refused request is not fatal — everything but OTA fits in 23 bytes
            // — so record what we got and let writeFrame reject oversized frames.
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_ATT_MTU
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("服务发现失败（status=$status）")
                return
            }

            val service = g.getService(Mr20Protocol.SERVICE_UUID)
            if (service == null) {
                fail("设备没有 MR20 主服务 ${Mr20Protocol.SERVICE_UUID}")
                return
            }

            writeChar = service.getCharacteristic(Mr20Protocol.WRITE_UUID)
            commandChar = service.getCharacteristic(Mr20Protocol.COMMAND_NOTIFY_UUID)
            audioChar = service.getCharacteristic(Mr20Protocol.AUDIO_NOTIFY_UUID)

            val missing = buildList {
                if (writeChar == null) add("写 ${Mr20Protocol.WRITE_UUID}")
                if (commandChar == null) add("指令 notify ${Mr20Protocol.COMMAND_NOTIFY_UUID}")
                if (audioChar == null) add("音频 notify ${Mr20Protocol.AUDIO_NOTIFY_UUID}")
            }
            if (missing.isNotEmpty()) {
                fail("设备缺少特征：${missing.joinToString("、")}")
                return
            }

            subscribeThenReady()
        }

        // Kept for API < 33; the byte-array overload below covers 33+.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                dispatchNotification(characteristic.uuid, characteristic.value ?: return)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = dispatchNotification(characteristic.uuid, value)

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val pending = pendingWrite ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) pending.complete(Unit)
            else pending.completeExceptionally(TransportException("写入失败（status=$status）"))
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val pending = pendingDescriptorWrite ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) pending.complete(Unit)
            else pending.completeExceptionally(
                TransportException("CCCD 写入失败（status=$status）")
            )
        }
    }

    /**
     * Subscribes to both notify characteristics, then reports READY.
     *
     * Runs off the GATT callback thread: enabling notifications requires waiting
     * for a descriptor-write callback, and blocking the callback thread to do that
     * would deadlock the stack.
     */
    private fun subscribeThenReady() {
        subscriptionScope.launch {
            try {
                enableNotifications(commandChar!!)
                enableNotifications(audioChar!!)
                _state.value = BleConnectionState.READY
                readySignal.complete(Unit)
            } catch (e: Exception) {
                fail(e.message ?: "开启通知失败")
            }
        }
    }

    private fun dispatchNotification(uuid: UUID, value: ByteArray) {
        if (uuid == Mr20Protocol.COMMAND_NOTIFY_UUID) {
            Log.i(TAG, "← a3: ${value.toString(Charsets.UTF_8)}")
        }
        // Copy: the framework reuses its buffer on the pre-33 path.
        val frame = value.copyOf()
        val target = when (uuid) {
            Mr20Protocol.COMMAND_NOTIFY_UUID -> commandFrames
            Mr20Protocol.AUDIO_NOTIFY_UUID -> dataFrames
            else -> return
        }
        // trySend semantics: these flows have large buffers and SUSPEND overflow,
        // so emitting from a non-suspending callback needs its own scope.
        subscriptionScope.launch { target.emit(frame) }
    }

    private fun fail(reason: String) {
        _failure.value = reason
        _state.value = BleConnectionState.FAILED
        if (!readySignal.isCompleted) readySignal.completeExceptionally(TransportException(reason))
    }

    /**
     * Serialises everything that must not run on the GATT callback thread.
     *
     * Single-threaded on purpose: notification order is protocol-significant
     * (`AA_DEV&U&LEN` must be seen before the bytes it announces), so the
     * dispatcher must not reorder emissions.
     */
    private val bleExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diting-ble-${device.address}")
    }

    private val subscriptionScope =
        CoroutineScope(SupervisorJob() + bleExecutor.asCoroutineDispatcher())

    /** Releases the GATT link and the notification thread. */
    fun shutdown() {
        close()
        subscriptionScope.cancel()
        bleExecutor.shutdown()
    }

    private companion object {
        const val TAG = "BleGatt"
        const val DEFAULT_ATT_MTU = 23
        const val ATT_HEADER_BYTES = 3

        /** 244-byte OTA payload + 3-byte ATT header. */
        const val REQUESTED_MTU = Mr20Protocol.OTA_FRAME_SIZE + ATT_HEADER_BYTES

        const val CONNECT_TIMEOUT_MS = 20_000L
        const val WRITE_TIMEOUT_MS = 5_000L
    }
}
