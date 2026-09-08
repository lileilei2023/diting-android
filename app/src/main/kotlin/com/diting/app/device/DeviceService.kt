package com.diting.app.device

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.diting.app.DitingApp
import com.diting.app.MainActivity
import com.diting.app.R
import com.diting.app.brain.BrainUploadWorker
import com.diting.app.data.prefs.SettingsStore
import com.diting.app.data.repo.SessionRepository
import com.diting.app.di.RecordingsDir
import com.diting.domain.model.DeviceKind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Keeps the BLE link and a running file sync alive across screen changes.
 *
 * A sync of a long recording over BLE takes minutes; without a foreground
 * service Android will freeze the process the moment the user leaves the app,
 * and the transfer dies mid-file. The notification is what buys that time, so it
 * shows real progress rather than a generic "working…".
 */
@AndroidEntryPoint
class DeviceService : LifecycleService() {

    @Inject lateinit var deviceManager: Mr20DeviceManager
    @Inject lateinit var sessions: SessionRepository
    @Inject lateinit var settings: SettingsStore

    @Inject @RecordingsDir lateinit var recordingsDir: File

    override fun onCreate() {
        super.onCreate()
        goForeground(buildNotification("已连接", null))

        // Mirror sync progress into the notification.
        deviceManager.sync
            .onEach { progress ->
                when (progress) {
                    is SyncProgress.Transferring -> updateNotification(
                        "正在同步 ${progress.index}/${progress.total}：${progress.fileName}",
                        progress.fraction,
                    )

                    is SyncProgress.Listing -> updateNotification("正在读取设备文件列表", null)
                    is SyncProgress.Done -> updateNotification(
                        buildString {
                            append(if (progress.filesSynced == 0) "没有新录音" else "已同步 ${progress.filesSynced} 条录音")
                            if (progress.skippedLarge > 0) append("，${progress.skippedLarge} 条大文件待 Wi-Fi")
                        },
                        null,
                    )

                    is SyncProgress.FallingBackToBle ->
                        updateNotification("Wi-Fi 不可用，改用蓝牙同步", null)

                    is SyncProgress.Failed -> updateNotification("同步失败：${progress.reason}", null)
                    SyncProgress.Idle -> updateNotification("已连接", null)
                }
            }
            .launchIn(lifecycleScope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_SYNC -> {
                val includeLarge = intent.getBooleanExtra(EXTRA_INCLUDE_LARGE, false)
                lifecycleScope.launch { runSync(includeLarge) }
            }
            ACTION_STOP -> stopSelf()
        }
        // Restarting with a null intent would reconnect without being asked, and
        // the BLE link is something the user opted into.
        return START_NOT_STICKY
    }

    private suspend fun runSync(includeLarge: Boolean) {
        // Wi-Fi sync is shelved (the card's AP never came up reliably on the
        // test phone); the preference is kept in storage but not honoured.
        val preferWifi = false
        settings.preferWifiSync.first()
        val deleteAfter = settings.deleteAfterSync.first()
        val alreadyHave = sessions.syncedBytesByDevicePath(DeviceKind.MR20)

        runCatching {
            deviceManager.syncNewRecordings(
                targetDir = recordingsDir,
                alreadyHave = alreadyHave,
                preferWifi = preferWifi,
                includeLargeOverBle = includeLarge,
            ) { deviceFile, localFile ->
                sessions.registerSyncedFile(
                    device = DeviceKind.MR20,
                    devicePath = deviceFile.path,
                    localFile = localFile,
                    durationSeconds = deviceFile.durationSeconds,
                    sizeBytes = deviceFile.sizeBytes,
                    recordedAtEpochMs = recordedAtFrom(deviceFile.directory, deviceFile.name, localFile),
                )

                // Only after the bytes are on disk and the row is written. Deleting
                // earlier would lose a recording if the app died mid-sync.
                if (deleteAfter) {
                    runCatching { deviceManager.deleteFromDevice(deviceFile) }
                }
            }
        }

        // Whatever landed, hand it to the brain. The worker is a no-op when the
        // user is not logged in, so this costs nothing in standalone mode.
        BrainUploadWorker.enqueue(androidx.work.WorkManager.getInstance(this))
    }

    /**
     * When the recording was made.
     *
     * On a real MR20 the file name *is* the start time — `2026-08-09 23-09-49`
     * (confirmed on hardware by the Echo bridge). Folders are `yyyy-MM-dd`, so
     * the folder date at noon is the fallback, and the local mtime the last
     * resort. Getting this right is what keeps a batch synced tonight from all
     * landing on "today" in the brain's timeline.
     */
    private fun recordedAtFrom(directory: String, fileName: String, localFile: File): Long {
        val zone = java.time.ZoneId.systemDefault()
        FNAME_TIMESTAMP.find(fileName)?.let { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            runCatching {
                return java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
                    .atZone(zone).toInstant().toEpochMilli()
            }
        }
        return runCatching {
            java.time.LocalDate.parse(directory).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        }.getOrElse { localFile.lastModified() }
    }

    private fun buildNotification(text: String, progress: Float?): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, DitingApp.CHANNEL_DEVICE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (progress != null) {
                    setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
                }
            }
            .build()
    }

    private fun updateNotification(text: String, progress: Float?) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    /**
     * connectedDevice covers holding the GATT link; dataSync covers the transfer
     * itself. Declaring both matches the manifest, which API 34 enforces.
     */
    @Suppress("DEPRECATION")
    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        deviceManager.disconnect()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        /** `2026-08-09 23-09-49`, and tolerant of other separators. */
        private val FNAME_TIMESTAMP =
            Regex("(\\d{4})\\D(\\d{2})\\D(\\d{2})\\D+(\\d{2})\\D(\\d{2})\\D(\\d{2})")
        const val ACTION_SYNC = "com.diting.app.SYNC"
        const val ACTION_STOP = "com.diting.app.STOP"
        const val EXTRA_INCLUDE_LARGE = "include_large"

        /** @param includeLarge also pull recordings over 5 MB across BLE (slow). */
        fun sync(context: Context, includeLarge: Boolean = false) = context.startForegroundService(
            Intent(context, DeviceService::class.java)
                .setAction(ACTION_SYNC)
                .putExtra(EXTRA_INCLUDE_LARGE, includeLarge)
        )

        fun stop(context: Context) = context.startService(
            Intent(context, DeviceService::class.java).setAction(ACTION_STOP)
        )
    }
}
