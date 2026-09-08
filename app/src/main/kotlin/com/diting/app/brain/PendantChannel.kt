package com.diting.app.brain

import android.util.Log
import com.diting.app.di.ApplicationScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live wire: `WS /channel/pendant-<SN>?token=&key=`.
 *
 * Uplink frames are acked one by one in order (the kernel has no correlation
 * ids), so [send] holds a mutex and a FIFO of pending acks. Downlink `say`
 * frames are surfaced on [says]; the kernel flushes its offline backlog before
 * `hello`, so a `say` may arrive first — that is normal.
 *
 * Reconnect backoff 1,2,5,15,60s. Close code 1008 means the token or the device
 * key was rejected; that is a user problem (re-login / re-adopt), so it does
 * not reconnect.
 */
@Singleton
class PendantChannel @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<ChannelState>(ChannelState.NotConfigured)
    val state: StateFlow<ChannelState> = _state.asStateFlow()

    private val _says = MutableSharedFlow<Say>(extraBufferCapacity = 64)
    val says: SharedFlow<Say> = _says.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var attempt = 0
    @Volatile private var wanted = false
    private var url: String = ""
    private val sendMutex = Mutex()
    private val pendingAcks = ArrayDeque<CompletableDeferred<Long>>()

    private val backoff = longArrayOf(1, 2, 5, 15, 60)

    @Synchronized
    fun start(baseUrl: String, sn: String, token: String, deviceKey: String) {
        val wsBase = baseUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val t = URLEncoder.encode(token, "UTF-8")
        val k = URLEncoder.encode(deviceKey, "UTF-8")
        val next = "$wsBase/channel/pendant-$sn?token=$t&key=$k"
        if (wanted && next == url && _state.value !is ChannelState.AuthFailed) return
        stopInternal()
        url = next
        if (com.diting.app.BuildConfig.DEBUG) Log.d(TAG, "channel url: $next")
        wanted = true
        attempt = 0
        open()
    }

    @Synchronized
    fun stop() {
        stopInternal()
        _state.value = ChannelState.NotConfigured
    }

    private fun stopInternal() {
        wanted = false
        reconnectJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
        failAllPending()
        _state.value = ChannelState.Disconnected
    }

    private fun open() {
        if (!wanted) return
        _state.value = ChannelState.Opening
        ws = http.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private fun scheduleReconnect() {
        if (!wanted || _state.value is ChannelState.AuthFailed) return
        _state.value = ChannelState.Disconnected
        failAllPending()
        val d = backoff[minOf(attempt, backoff.size - 1)]
        attempt++
        reconnectJob?.cancel()
        reconnectJob = scope.launch { delay(d * 1000); open() }
    }

    private fun failAllPending() {
        synchronized(pendingAcks) {
            while (pendingAcks.isNotEmpty()) {
                pendingAcks.poll()?.completeExceptionally(ChannelOfflineException())
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val obj = json.parseToJsonElement(text).jsonObject
                when (obj["type"]?.jsonPrimitive?.content) {
                    "hello" -> {
                        attempt = 0
                        val backlog = obj["backlog"]?.jsonPrimitive?.int ?: 0
                        _state.value = ChannelState.Live(backlog)
                        Log.i(TAG, "hello, backlog=$backlog")
                    }

                    "ack" -> {
                        val id = obj["memory_id"]?.jsonPrimitive?.long ?: -1L
                        synchronized(pendingAcks) { pendingAcks.poll() }?.complete(id)
                    }

                    "say" -> {
                        val say = Say(
                            text = obj["text"]?.jsonPrimitive?.content ?: "",
                            importance = obj["importance"]?.jsonPrimitive?.double ?: 0.5,
                        )
                        if (say.text.isNotBlank()) _says.tryEmit(say)
                    }

                    else -> Log.d(TAG, "unknown downlink: ${text.take(200)}")
                }
            }.onFailure { Log.w(TAG, "bad downlink: ${text.take(200)}", it) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "closed code=$code reason=$reason")
            if (webSocket !== ws) return
            if (code == 1008) {
                _state.value = ChannelState.AuthFailed(reason.ifBlank { "token 或设备 key 被拒绝 (1008)" })
                failAllPending()
            } else {
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            Log.w(TAG, "ws failure: ${t.message}")
            // A 403 on the upgrade is how a rejected handshake looks before
            // the socket ever opens; treat it like 1008 rather than hammering.
            if ((response?.code == 403 || response?.code == 401) && attempt >= 3) {
                _state.value = ChannelState.AuthFailed("握手被拒绝 (HTTP ${response.code})")
                failAllPending()
            } else {
                scheduleReconnect()
            }
        }
    }

    /**
     * Sends one frame and waits for its ack. Returns the memory id (the same id
     * on replay of the same `idem`). Throws [ChannelOfflineException] when not
     * live; the caller keeps the frame and retries later.
     */
    suspend fun send(frame: AsrFrame, timeoutMs: Long = 15_000): Long = sendMutex.withLock {
        val socket = ws
        if (_state.value !is ChannelState.Live || socket == null) throw ChannelOfflineException()
        val ack = CompletableDeferred<Long>()
        synchronized(pendingAcks) { pendingAcks.add(ack) }
        if (!socket.send(json.encodeToString(frame))) {
            synchronized(pendingAcks) { pendingAcks.remove(ack) }
            throw ChannelOfflineException("ws send buffer refused")
        }
        withTimeout(timeoutMs) { ack.await() }
    }

    private companion object {
        const val TAG = "PendantChannel"
    }
}
