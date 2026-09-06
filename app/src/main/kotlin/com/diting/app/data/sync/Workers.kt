package com.diting.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.diting.app.data.prefs.SettingsStore
import com.diting.app.data.repo.MemoryRepository
import com.diting.app.data.repo.SessionRepository
import com.diting.app.di.AiClientFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Transcribes and summarises everything still marked PENDING.
 *
 * Runs on a schedule rather than immediately after sync because transcription
 * needs the network and the user may have synced over the recorder's own
 * (routeless) Wi-Fi AP, where no upload can succeed.
 */
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sessions: SessionRepository,
    private val settings: SettingsStore,
    private val aiClients: AiClientFactory,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val asr = aiClients.asrClient()
            // Not a failure: the user simply has not configured a model yet, and
            // retrying on a backoff would drain the battery for nothing.
            ?: return Result.success()

        val understanding = aiClients.understanding()
        val scenes = settings.scenes.first()
        val extraPrompt = settings.summaryPrompt.first()

        val pending = sessions.pendingTranscription()
        var failed = false

        pending.forEach { session ->
            runCatching {
                sessions.transcribe(session.id, asr)
                understanding?.let {
                    sessions.summarize(session.id, it, scenes, extraPrompt)
                }
            }.onFailure { failed = true }
        }

        // retry() re-runs on WorkManager's backoff; the per-session state is
        // already persisted, so a retry only picks up what genuinely failed.
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "diting_transcription"

        fun periodicRequest() = PeriodicWorkRequestBuilder<TranscriptionWorker>(
            15, TimeUnit.MINUTES,
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()
    }
}

/**
 * Applies the retention policy: deletes expired audio, archives cold graph nodes.
 *
 * Daily, and only while charging. Deleting files is cheap, but the sweep walks
 * every session and node, and doing that on battery for something with no
 * deadline would be rude.
 */
@HiltWorker
class RetentionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val memory: MemoryRepository,
    private val settings: SettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val policy = settings.retentionPolicy.first()
        return runCatching { memory.sweep(policy) }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        const val UNIQUE_NAME = "diting_retention"

        fun periodicRequest() = PeriodicWorkRequestBuilder<RetentionWorker>(
            1, TimeUnit.DAYS,
        ).setConstraints(
            Constraints.Builder()
                .setRequiresCharging(true)
                .build()
        ).build()
    }
}
