package com.diting.app.device.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import com.diting.protocol.mr20.Mr20FileReceiver
import com.diting.protocol.mr20.Mr20Protocol
import com.diting.protocol.mr20.Mr20TransferChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class WifiTransferException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Pulls a recording over the MR20's own Wi-Fi access point.
 *
 * The AP has **no internet route**, and that is the whole difficulty. On Android
 * 10+ the system keeps the phone's default network on mobile data when it joins
 * a routeless Wi-Fi, so a plain `Socket()` to 192.168.200.1 leaves over the
 * cellular interface and never reaches the recorder. The fix is to request the
 * network explicitly and bind the socket to it — [Network.getSocketFactory].
 *
 * Flow, per the protocol document:
 * ```
 *   1. AA_BLE&WIFIO                  (over BLE)
 *   2. poll AA_BLE&WIFIS until '2'   (over BLE)
 *   3. join the AP, open this socket
 *   4. AA_BLE&W&DIR&FNAME            (over BLE) -> AA_DEV&W&LEN
 *   5. read LEN bytes + the 5-byte trailer from the socket
 *   6. AA_BLE&WIFIC                  (over BLE)
 * ```
 * Steps 1, 2, 4 and 6 belong to `Mr20Client`; this class owns 3 and 5.
 */
class Mr20WifiTransfer(private val context: Context) {

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Joins the recorder's AP and keeps it for the duration of [block].
     *
     * @param ssid from `AA_DEV&WIFI&SSID&PWD`
     * @param password likewise
     */
    @SuppressLint("MissingPermission")
    suspend fun <T> withDeviceNetwork(
        ssid: String,
        password: String,
        timeoutMs: Long = JOIN_TIMEOUT_MS,
        attempts: Int = JOIN_ATTEMPTS,
        block: suspend (Network) -> T,
    ): T {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Pre-10 there is no per-app network request; the user joins the AP in
            // Settings and the default route follows. Nothing to bind.
            @Suppress("DEPRECATION")
            return block(connectivity.activeNetwork ?: throw WifiTransferException("没有可用网络"))
        }

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // The AP has no internet; asking for INTERNET or VALIDATED would make
            // the request never resolve.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        // The user may already have joined the AP by hand in system settings —
        // the reliable path on phones that refuse the in-app request. Look for
        // a Wi-Fi network that can actually reach the recorder before asking.
        findDeviceNetwork()?.let {
            Log.i(TAG, "already on a network that reaches the recorder")
            return block(it)
        }

        // The system's approval dialog scans for the SSID itself and gives up
        // (onUnavailable) about six seconds after its first empty scan. The AP
        // has only just come up, so the first scan routinely misses it. Give the
        // beacon a moment, then ask again a few times before declaring failure.
        var lastFailure: String? = null
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            repeat(attempts) { attempt ->
                if (attempt == 0) kotlinx.coroutines.delay(BEACON_SETTLE_MS)
                val available = CompletableDeferred<Network>()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        available.complete(network)
                    }

                    override fun onUnavailable() {
                        available.completeExceptionally(
                            WifiTransferException("系统没有找到或没有允许加入热点 $ssid")
                        )
                    }
                }
                callback = cb
                Log.i(TAG, "requestNetwork $ssid attempt ${attempt + 1}/$attempts")
                connectivity.requestNetwork(request, cb)
                try {
                    val joined = withTimeout(timeoutMs) { available.await() }
                    Log.i(TAG, "joined $ssid")
                    return block(joined)
                } catch (e: Exception) {
                    lastFailure = e.message
                    Log.w(TAG, "attempt ${attempt + 1} failed: ${e.message}")
                    runCatching { connectivity.unregisterNetworkCallback(cb) }
                    callback = null
                    findDeviceNetwork()?.let {
                        Log.i(TAG, "found the recorder on an existing network")
                        return block(it)
                    }
                    if (attempt < attempts - 1) kotlinx.coroutines.delay(RETRY_GAP_MS)
                }
            }
            throw WifiTransferException(
                "$lastFailure。可以到系统设置手动连接热点 $ssid（密码 $password），保持连接后回来再点同步。",
            )
        } finally {
            // Releasing drops the AP and lets the phone go back to its normal
            // network; the device closes its own Wi-Fi 5s later.
            callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        }
    }

    /**
     * A Wi-Fi network through which the recorder answers on its socket port, or
     * null. Probing by TCP avoids needing location permission to read the SSID.
     */
    suspend fun findDeviceNetwork(): Network? = withContext(Dispatchers.IO) {
        connectivity.allNetworks.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                runCatching { probe(network, attempts = 1) }.isSuccess
        }
    }

    /**
     * Opens and closes a socket to the recorder over [network]; throws if unreachable.
     * Retries for a few seconds: `onAvailable` fires before DHCP has settled, and
     * the card's socket server takes a moment after the AP reports state 2.
     */
    suspend fun probe(network: Network, attempts: Int = 4) = withContext(Dispatchers.IO) {
        var last: Exception? = null
        repeat(attempts) { i ->
            try {
                network.socketFactory.createSocket().use {
                    it.connect(InetSocketAddress(Mr20Protocol.WIFI_HOST, Mr20Protocol.WIFI_PORT), PROBE_TIMEOUT_MS)
                }
                return@withContext
            } catch (e: Exception) {
                last = e
                if (i < attempts - 1) kotlinx.coroutines.delay(1_500)
            }
        }
        throw WifiTransferException(
            "已加入热点，但 ${Mr20Protocol.WIFI_HOST}:${Mr20Protocol.WIFI_PORT} 没有应答：${last?.message}", last,
        )
    }

    /**
     * Reads one file from the AP socket into [sink].
     *
     * @param expectedBytes the length from `AA_DEV&W&LEN`.
     * @return bytes written, excluding the 5-byte trailer.
     */
    suspend fun receiveFile(
        network: Network,
        expectedBytes: Long,
        onProgress: ((Float?) -> Unit)? = null,
        sink: OutputStream,
    ): Long = withContext(Dispatchers.IO) {
        val receiver = Mr20FileReceiver(
            expectedBytes = expectedBytes,
            channel = Mr20TransferChannel.WIFI,
        ) { buffer, offset, length -> sink.write(buffer, offset, length) }

        // Bind through the AP's network, or the bytes go out over mobile data.
        val socket: Socket = network.socketFactory.createSocket()
        socket.use {
            it.soTimeout = READ_TIMEOUT_MS
            it.connect(
                InetSocketAddress(Mr20Protocol.WIFI_HOST, Mr20Protocol.WIFI_PORT),
                CONNECT_TIMEOUT_MS,
            )

            val input = it.getInputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)

            while (receiver.state == Mr20FileReceiver.State.RECEIVING) {
                val read = input.read(buffer)
                if (read < 0) {
                    // The socket closed. With a known length that is only correct
                    // if every byte already arrived.
                    receiver.onDeviceFinished()
                    break
                }
                receiver.onChunk(buffer, 0, read)
                onProgress?.invoke(receiver.progress)
            }
        }

        sink.flush()

        if (receiver.state != Mr20FileReceiver.State.COMPLETE) {
            throw WifiTransferException(
                receiver.failure ?: "Wi-Fi 传输未完成（已收到 ${receiver.receivedBytes} 字节）"
            )
        }
        receiver.receivedBytes
    }

    private companion object {
        const val TAG = "Mr20Wifi"
        const val JOIN_TIMEOUT_MS = 45_000L
        const val JOIN_ATTEMPTS = 4
        const val BEACON_SETTLE_MS = 4_000L
        const val RETRY_GAP_MS = 2_000L

        /**
         * The device closes an idle AP after 30s, so a read gap longer than that
         * means the link is gone rather than merely slow.
         */
        const val READ_TIMEOUT_MS = 30_000
        const val CONNECT_TIMEOUT_MS = 10_000
        const val PROBE_TIMEOUT_MS = 2_000
        const val READ_BUFFER_BYTES = 32 * 1024
    }
}
