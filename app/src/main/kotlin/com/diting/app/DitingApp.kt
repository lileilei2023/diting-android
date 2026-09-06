package com.diting.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager
import com.diting.app.data.sync.RetentionWorker
import com.diting.app.data.sync.TranscriptionWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class DitingApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        // Manual init: the manifest removes WorkManagerInitializer so the Hilt
        // worker factory above is in place before the first worker runs.
        val workManager = WorkManager.getInstance(this)

        workManager.enqueueUniquePeriodicWork(
            TranscriptionWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            TranscriptionWorker.periodicRequest(),
        )

        workManager.enqueueUniquePeriodicWork(
            RetentionWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            RetentionWorker.periodicRequest(),
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DEVICE,
                getString(R.string.channel_device_name),
                // LOW: syncing is background work the user started; it should be
                // visible but must not buzz.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_device_desc) }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                getString(R.string.channel_capture_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_capture_desc) }
        )
    }

    companion object {
        const val CHANNEL_DEVICE = "device_sync"
        const val CHANNEL_CAPTURE = "capture"
    }
}
