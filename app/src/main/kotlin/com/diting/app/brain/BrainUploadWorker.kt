package com.diting.app.brain

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.WorkInfo
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.diting.app.DitingApp
import com.diting.app.MainActivity
import com.diting.app.R
import com.diting.app.data.db.SessionDao
import com.diting.app.data.repo.SessionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first

/**
 * Sends every recording the brain has not seen through `/upload`'s full
 * pipeline (ASR → store → extract → dispatch), newest first.
 *
 * Separate from the on-device transcription worker on purpose: the brain does
 * its own ASR server-side, and a user with no vendor key configured still gets
 * transcripts, summaries and to-dos back through the brain.
 */
@HiltWorker
class BrainUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val store: BrainStore,
    private val api: BrainApi,
    private val sessionDao: SessionDao,
    private val segmentDao: com.diting.app.data.db.SegmentDao,
    private val speakerDao: com.diting.app.data.db.SpeakerDao,
    private val sessions: SessionRepository,
    private val episodes: com.diting.app.data.repo.EpisodeMerger,
    private val hotwordDao: com.diting.app.data.db.HotwordDao,
    @com.diting.app.di.RecordingsDir private val recordingsDir: java.io.File,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = uploadLock.withLock {
        val account = store.current
        if (!account.isLoggedIn) return@withLock Result.success()

        // Rows written before speaker labels were normalised to letters.
        listOf("1" to "A", "2" to "B", "3" to "C", "4" to "D").forEach { (n, letter) ->
            segmentDao.relabelSpeaker(listOf("说话人$n", "说话人 $n", "speaker $n", "Speaker $n"), letter)
        }
        speakerDao.upsertAll(emptyList())

        // Nothing legitimately stays "正在转写" once the brain has answered.
        runCatching { sessions.scanImports(recordingsDir) }
        runCatching { sessions.backfillMemoryIfEmpty() }
        val settled = sessionDao.settleStaleBrainUploads("大脑没有识别出语音（可能是静音或太短）")
        if (settled > 0) Log.i(TAG, "settled $settled stale uploading sessions")

        val since = if (account.uploadHistorical) 0L else account.pairedAtEpochSec * 1000
        val pending = sessionDao.pendingBrainUpload(since, BATCH)
        if (pending.isEmpty()) return@withLock Result.success()

        // Foreground: a phone that locks its screen freezes background work
        // (Samsung does within seconds), and a 40-minute recording in seven
        // pieces needs minutes of network. The notification is also the only
        // place the user can see that the brain is working on their audio.
        runCatching { setForeground(foregroundInfo("正在上传到大脑 0/${pending.size}")) }

        // The brain biases its ASR with the words it has been told about; tell it
        // before the audio goes up, so this batch already benefits.
        runCatching {
            val words = hotwordDao.observeAll().first().filter { it.enabled }.map { it.word }
            api.pushHotwords(words)
            Log.i(TAG, "pushed ${words.size} hotwords")
        }.onFailure { Log.w(TAG, "hotword push failed: ${it.message}") }

        var failed = false
        for ((i, session) in pending.withIndex()) {
            if (isStopped) return@withLock Result.retry()
            runCatching { setForeground(foregroundInfo("正在上传到大脑 ${i + 1}/${pending.size}：${session.title}")) }
            val file = session.audioPath?.let(::File)
            if (file == null || !file.exists()) {
                // Audio already swept by retention; nothing to send, stop asking.
                sessionDao.markBrainUploaded(session.id, System.currentTimeMillis())
                continue
            }
            if (file.length() > MAX_BYTES) {
                Log.w(TAG, "skip ${file.name}: ${file.length()} bytes exceeds upload cap")
                sessions.markBrainUploadFailed(
                    session.id, "文件 ${file.length() / 1024 / 1024} MB，超过 ${MAX_BYTES / 1024 / 1024} MB 上传上限",
                )
                // Never retried automatically; the row explains itself.
                sessionDao.markBrainUploaded(session.id, System.currentTimeMillis())
                continue
            }
            try {
                sessions.markBrainUploading(session.id)
                val report = uploadInPieces(session.id, file, session.startedAtEpochMs, session.durationMs)
                sessions.applyBrainReport(session.id, report)
                store.clearChunkProgress(session.id)
                Log.i(TAG, "uploaded ${file.name}: frames=${report.frames} facts=${report.factsNew} turns=${report.turns.size}")
            } catch (e: AuthExpiredException) {
                sessions.markBrainUploadFailed(session.id, "大脑登录已过期，请重新登录")
                store.clearAuth()
                return@withLock Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "upload ${file.name} failed: ${e.message}")
                sessions.markBrainUploadFailed(session.id, "上传大脑失败：${e.message ?: e::class.simpleName}，稍后自动重试")
                failed = true
            }
        }
        // Fragments that came back transcribed are stitched into episodes and
        // re-read as a whole before anyone looks at them.
        runCatching { setForeground(foregroundInfo("正在拼接相邻录音…")) }
        runCatching { episodes.run() }.onFailure { Log.w(TAG, "episode merge: ${it.message}") }

        // A batch is capped so one run cannot hog the foreground for an hour;
        // anything left is picked up by a fresh run rather than the next launch.
        if (!failed && sessionDao.pendingBrainUpload(since, 1).isNotEmpty()) enqueue(WorkManager.getInstance(applicationContext), chain = true)
        if (failed) Result.retry() else Result.success()
    }

    /**
     * Uploads a recording as one request, or — for MP3s past the relay limit —
     * as consecutive pieces stamped with their own start times, merging the
     * reports. Progress is remembered per session so a retry resumes at the
     * first piece the brain has not seen instead of re-sending (and
     * re-ingesting) the early ones.
     */
    private suspend fun uploadInPieces(
        sessionId: String,
        file: File,
        startedAtEpochMs: Long,
        durationMs: Long,
    ): UploadReport {
        val format = formatOf(file)
        if (format != "mp3" || file.length() <= Mp3Chunker.DEFAULT_CHUNK_BYTES) {
            return api.uploadFull(file, format, startedAtEpochMs / 1000)
        }

        val pieces = Mp3Chunker.split(file)
        val done = store.chunkProgress(sessionId, Mp3Chunker.DEFAULT_CHUNK_BYTES)
        val reportDir = File(applicationContext.cacheDir, "brain_reports/$sessionId").apply { mkdirs() }
        try {
            for (piece in pieces) {
                if (piece.index < done) continue
                // Time offset by byte position: MR20 MP3 is constant bit rate.
                val offsetMs = if (file.length() > 0) durationMs * piece.startByte / file.length() else 0L
                val at = (startedAtEpochMs + offsetMs) / 1000
                Log.i(TAG, "${file.name}: piece ${piece.index + 1}/${piece.total} (${piece.file.length()} bytes, +${offsetMs / 1000}s)")
                val report = api.uploadFull(piece.file, "mp3", at)
                // Each piece's report is kept on disk: a worker stopped between
                // pieces (network change, reinstall) resumes without losing the
                // transcript of the pieces the brain already accepted.
                File(reportDir, "%03d.json".format(piece.index)).writeText(reportJson.encodeToString(UploadReport.serializer(), report))
                store.setChunkProgress(sessionId, Mp3Chunker.DEFAULT_CHUNK_BYTES, piece.index + 1)
            }
        } finally {
            pieces.filter { it.file != file }.forEach { runCatching { it.file.delete() } }
        }
        val reports = reportDir.listFiles()?.sortedBy { it.name }?.mapNotNull { f ->
            runCatching { reportJson.decodeFromString(UploadReport.serializer(), f.readText()) }.getOrNull()
        } ?: emptyList()
        reportDir.deleteRecursively()
        return merge(reports)
    }

    private val reportJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun merge(reports: List<UploadReport>): UploadReport = UploadReport(
        frames = reports.sumOf { it.frames },
        summary = reports.map { it.summary }.filter { it.isNotBlank() }.joinToString("\n"),
        title = reports.firstOrNull { it.title.isNotBlank() }?.title ?: "",
        factsNew = reports.sumOf { it.factsNew },
        transcript = reports.flatMap { it.transcript },
        items = reports.flatMap { it.items },
        facts = reports.flatMap { it.facts },
    )

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("正在上传到大脑")

    private fun foregroundInfo(text: String): ForegroundInfo {
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(applicationContext, DitingApp.CHANNEL_DEVICE)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun formatOf(file: File): String = when (file.extension.lowercase()) {
        "wav" -> "wav"
        "m4a", "aac" -> "m4a"
        else -> "mp3"
    }

    companion object {
        private const val TAG = "BrainUpload"
        private const val NOTIFICATION_ID = 1002
        const val UNIQUE_NAME = "diting_brain_upload"

        /** Two workers once ran the same 10 MB upload side by side; never again. */
        private val uploadLock = Mutex()
        private const val BATCH = 20

        /** Base64 in a JSON body: a 40 MB file is a ~54 MB request, about 2.5 h at 32 kbps. */
        private const val MAX_BYTES = 40L * 1024 * 1024

        /** @param chain called from a finishing run: queue another run after this one instead of replacing it. */
        fun enqueue(workManager: WorkManager, chain: Boolean = false) {
            // A worker that is RUNNING drains everything pending, so keep it.
            // One merely ENQUEUED is almost always sitting in retry backoff —
            // after a few failures WorkManager waits tens of minutes — and
            // "现在上传" must not be a no-op then: replace it and start now.
            val running = runCatching {
                workManager.getWorkInfosForUniqueWork(UNIQUE_NAME).get()
                    .any { it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            workManager.enqueueUniqueWork(
                UNIQUE_NAME,
                when {
                    chain -> ExistingWorkPolicy.APPEND_OR_REPLACE
                    running -> ExistingWorkPolicy.KEEP
                    else -> ExistingWorkPolicy.REPLACE
                },
                OneTimeWorkRequestBuilder<BrainUploadWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
        }
    }
}
