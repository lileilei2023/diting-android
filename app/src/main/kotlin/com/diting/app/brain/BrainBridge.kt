package com.diting.app.brain

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import com.diting.app.DitingApp
import com.diting.app.MainActivity
import com.diting.app.device.Mr20DeviceManager
import com.diting.app.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Glue between the brain and the rest of the app, alive for the process.
 *
 *  * Opens the device channel whenever the account is complete (logged in and
 *    the recorder adopted) and closes it when it is not.
 *  * Turns downlink `say` into a notification, and — above the importance
 *    threshold, outside quiet hours — into a phone buzz plus `AA_BLE&SHAKE`
 *    on the recorder, which is the only feedback a screenless card has.
 *  * Adopts the recorder (mints its channel key) after pairing, or on login
 *    if pairing happened first.
 */
@Singleton
class BrainBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: BrainStore,
    private val api: BrainApi,
    private val channel: PendantChannel,
    private val deviceManager: Mr20DeviceManager,
    @ApplicationScope private val scope: CoroutineScope,
    private val sessions: com.diting.app.data.repo.SessionRepository,
    private val episodes: com.diting.app.data.repo.EpisodeMerger,
    @com.diting.app.di.RecordingsDir private val recordingsDir: java.io.File,
) {
    val channelState get() = channel.state

    private var started = false
    private var sayNotificationId = 4000

    fun start() {
        if (started) return
        started = true

        // Files dropped into the recordings folder become sessions even when the
        // upload worker cannot run (no network); the list must not wait for it.
        scope.launch {
            runCatching { sessions.scanImports(recordingsDir) }
            runCatching { episodes.run() }
            runCatching { sessions.backfillMemoryIfEmpty() }
        }

        store.account
            .map { ChannelCreds(it.baseUrl, it.token, it.sn, it.deviceKey) }
            .distinctUntilChanged()
            .onEach { creds ->
                if (creds.token != null && creds.sn != null && creds.deviceKey != null) {
                    channel.start(creds.baseUrl, creds.sn, creds.token, creds.deviceKey)
                } else {
                    channel.stop()
                }
            }
            .launchIn(scope)

        // Paired before logging in: adopt as soon as a token appears.
        store.account
            .map { Triple(it.token, it.sn, it.deviceKey) }
            .distinctUntilChanged()
            .onEach { (token, sn, deviceKey) ->
                if (token != null && sn != null && deviceKey == null) {
                    runCatching { adoptDevice(sn) }
                        .onFailure { Log.w(TAG, "auto-adopt failed: ${it.message}") }
                }
            }
            .launchIn(scope)

        channel.says.onEach(::onSay).launchIn(scope)

        // Every launch: anything synced while offline or while the process was
        // frozen goes up now, not at the next sync.
        if (store.current.isLoggedIn) enqueueUpload()

        // A rejected handshake with a valid login almost always means the key on
        // file is stale (re-minted elsewhere, or a server that kept an older
        // hash). Re-adopt once per key; if that key is rejected too, stop and
        // let the screen show it.
        channel.state
            .onEach { state ->
                val account = store.current
                if (state is ChannelState.AuthFailed && account.isLoggedIn && account.sn != null &&
                    account.deviceKey != null && account.deviceKey != lastReadoptedKey
                ) {
                    lastReadoptedKey = account.deviceKey
                    Log.i(TAG, "channel rejected; re-adopting ${account.sn}")
                    runCatching { adoptDevice(account.sn) }
                        .onFailure { Log.w(TAG, "re-adopt failed: ${it.message}") }
                }
            }
            .launchIn(scope)
    }

    private var lastReadoptedKey: String? = null

    /** Registers the recorder with the brain and stores the minted channel key. */
    suspend fun adoptDevice(sn: String): String {
        val key = try {
            api.mintDeviceKey(sn)
        } catch (e: AuthExpiredException) {
            store.clearAuth()
            throw e
        }
        store.setAdopted(sn, key)
        enqueueUpload()
        return key
    }

    fun enqueueUpload() = BrainUploadWorker.enqueue(WorkManager.getInstance(context))

    suspend fun logout() {
        api.logout()
        // The device key was minted under this account; a new login re-mints.
        store.clearAdoption()
    }

    private fun onSay(say: Say) {
        Log.i(TAG, "say(${say.importance}): ${say.text.take(80)}")
        notify(say)
        val quiet = store.inQuietHours()
        if (!quiet && say.importance >= store.current.shakeThreshold) {
            vibratePhone()
            deviceManager.client.value?.let { client ->
                scope.launch { runCatching { client.vibrate() } }
            }
        }
    }

    private fun notify(say: Say) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, DitingApp.CHANNEL_BRAIN)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("小谛")
            .setContentText(say.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(say.text))
            .setPriority(
                if (say.importance >= 0.8) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(sayNotificationId++, n)
        }
    }

    private fun vibratePhone() {
        runCatching {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1)
            )
        }
    }

    private data class ChannelCreds(
        val baseUrl: String,
        val token: String?,
        val sn: String?,
        val deviceKey: String?,
    )

    private companion object {
        const val TAG = "BrainBridge"
    }
}
