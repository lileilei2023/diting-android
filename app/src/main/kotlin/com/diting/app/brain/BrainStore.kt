package com.diting.app.brain

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the app knows about its brain account and the adopted recorder. */
data class BrainAccount(
    val baseUrl: String = BrainStore.DEFAULT_BASE_URL,
    val token: String? = null,
    val subject: String? = null,
    val nick: String = "",
    /** Recorder SN = BT MAC, lowercase, no colons. Known once BLE pairing succeeds. */
    val sn: String? = null,
    /** Minted by `POST /capabilities/register`; the WS handshake key. */
    val deviceKey: String? = null,
    /** Epoch seconds of the first successful adoption. Older recordings are "history". */
    val pairedAtEpochSec: Long = 0,
    /** Upload recordings made before [pairedAtEpochSec]. Off by default (privacy). */
    val uploadHistorical: Boolean = false,
    /** `say.importance` at or above this shakes the recorder and buzzes the phone. */
    val shakeThreshold: Float = 0.8f,
    /** 22:00–08:00 local: notify silently, never shake. */
    val quietHours: Boolean = true,
) {
    val isLoggedIn: Boolean get() = !token.isNullOrBlank()
    val isAdopted: Boolean get() = !sn.isNullOrBlank() && !deviceKey.isNullOrBlank()

    /** Ready to open the device channel. */
    val canOpenChannel: Boolean get() = isLoggedIn && isAdopted
}

/**
 * Credentials for the brain, in [EncryptedSharedPreferences] like the AI keys.
 *
 * A [StateFlow] mirror is kept so screens and the bridge react to login,
 * logout and adoption without polling.
 */
@Singleton
class BrainStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "brain_creds",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _account = MutableStateFlow(load())
    val account: StateFlow<BrainAccount> = _account.asStateFlow()

    val current: BrainAccount get() = _account.value

    private fun load(): BrainAccount = BrainAccount(
        baseUrl = prefs.getString(K_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
        token = prefs.getString(K_TOKEN, null),
        subject = prefs.getString(K_SUBJECT, null),
        nick = prefs.getString(K_NICK, "").orEmpty(),
        sn = prefs.getString(K_SN, null),
        deviceKey = prefs.getString(K_DEVICE_KEY, null),
        pairedAtEpochSec = prefs.getLong(K_PAIRED_AT, 0L),
        uploadHistorical = prefs.getBoolean(K_UPLOAD_HISTORICAL, false),
        shakeThreshold = prefs.getFloat(K_SHAKE_THRESHOLD, 0.8f),
        quietHours = prefs.getBoolean(K_QUIET_HOURS, true),
    )

    @Synchronized
    fun update(block: (BrainAccount) -> BrainAccount) {
        val next = block(_account.value)
        prefs.edit()
            .putString(K_BASE_URL, next.baseUrl.trim().trimEnd('/'))
            .putString(K_TOKEN, next.token)
            .putString(K_SUBJECT, next.subject)
            .putString(K_NICK, next.nick)
            .putString(K_SN, next.sn)
            .putString(K_DEVICE_KEY, next.deviceKey)
            .putLong(K_PAIRED_AT, next.pairedAtEpochSec)
            .putBoolean(K_UPLOAD_HISTORICAL, next.uploadHistorical)
            .putFloat(K_SHAKE_THRESHOLD, next.shakeThreshold)
            .putBoolean(K_QUIET_HOURS, next.quietHours)
            .apply()
        _account.value = next.copy(baseUrl = next.baseUrl.trim().trimEnd('/'))
    }

    fun setBaseUrl(url: String) = update { it.copy(baseUrl = url) }

    fun setAuth(result: AuthResult) = update {
        it.copy(token = result.token, subject = result.subject, nick = result.nick)
    }

    fun clearAuth() = update { it.copy(token = null, subject = null, nick = "") }

    fun setSn(sn: String) = update { it.copy(sn = sn) }

    fun setAdopted(sn: String, deviceKey: String) = update {
        it.copy(
            sn = sn,
            deviceKey = deviceKey,
            pairedAtEpochSec = if (it.pairedAtEpochSec > 0) it.pairedAtEpochSec
            else System.currentTimeMillis() / 1000,
        )
    }

    fun clearAdoption() = update { it.copy(deviceKey = null) }

    // ---- chunked-upload progress (session id -> pieces already accepted) ----

    // Keyed by piece size too: a resume must never skip pieces cut at another size.
    fun chunkProgress(sessionId: String, chunkBytes: Long): Int =
        prefs.getInt("$K_CHUNK_PREFIX$sessionId:$chunkBytes", 0)

    fun setChunkProgress(sessionId: String, chunkBytes: Long, piecesDone: Int) =
        prefs.edit().putInt("$K_CHUNK_PREFIX$sessionId:$chunkBytes", piecesDone).apply()

    fun clearChunkProgress(sessionId: String) =
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("$K_CHUNK_PREFIX$sessionId:") }.forEach { remove(it) }
        }.apply()

    fun inQuietHours(): Boolean {
        if (!current.quietHours) return false
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return h >= 22 || h < 8
    }

    companion object {
        /**
         * The deployed brain. Plain HTTP on :8000 — the TLS name
         * (echo.brainfussion.com) is not reachable from every network, and the
         * manifest allows cleartext for exactly this reason. User-editable.
         */
        const val DEFAULT_BASE_URL = "http://47.115.135.22:8000"

        private const val K_BASE_URL = "base_url"
        private const val K_TOKEN = "token"
        private const val K_SUBJECT = "subject"
        private const val K_NICK = "nick"
        private const val K_SN = "sn"
        private const val K_DEVICE_KEY = "device_key"
        private const val K_PAIRED_AT = "paired_at"
        private const val K_UPLOAD_HISTORICAL = "upload_historical"
        private const val K_SHAKE_THRESHOLD = "shake_threshold"
        private const val K_QUIET_HOURS = "quiet_hours"
        private const val K_CHUNK_PREFIX = "chunk_done:"
    }
}
