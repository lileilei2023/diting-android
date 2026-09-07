package com.diting.app.capture

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.diting.app.DitingApp
import com.diting.app.DitingPermissions
import com.diting.app.MainActivity
import com.diting.app.R
import com.diting.app.data.repo.SessionRepository
import com.diting.app.di.RecordingsDir
import com.diting.domain.model.DeviceKind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Phone-microphone capture — the short press on the record key.
 *
 * Kept deliberately separate from the recorder path: this is the "quick capture"
 * of the design, works with no hardware present, and produces the same kind of
 * session so everything downstream (transcription, summary, tasks) is identical.
 *
 * Encodes to AAC in an MP4 container. The MR20 produces MP3, but there is no
 * reason to make the phone match it — the transcription service takes both, and
 * AAC is the only hardware-accelerated option available everywhere.
 */
@AndroidEntryPoint
class CaptureService : LifecycleService() {

    @Inject lateinit var sessions: SessionRepository
    @Inject @RecordingsDir lateinit var recordingsDir: File

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtEpochMs: Long = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> lifecycleScope.launch { stopAndSave() }
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null) return

        // Before anything else, and before going foreground: on Android 14+
        // startForeground with FOREGROUND_SERVICE_TYPE_MICROPHONE throws
        // SecurityException when RECORD_AUDIO is missing, and that exception
        // kills the process rather than failing the call. The user denying a
        // permission dialog must not crash the app.
        if (!DitingPermissions.hasAudio(this)) {
            _error.value = "需要麦克风权限才能录音。请在系统设置里开启后重试。"
            stopSelf()
            return
        }

        goForeground(buildNotification("正在录音"))
        startedAtEpochMs = System.currentTimeMillis()

        val target = File(recordingsDir, "capture_$startedAtEpochMs.m4a")
        outputFile = target

        // prepare() and start() throw on a busy microphone — another app
        // recording, or an in-progress call — and on any storage problem. Left
        // uncaught they would take the process down for a condition the user can
        // simply retry out of.
        val started = runCatching {
            buildRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // 16 kHz mono is what speech recognisers want; higher rates cost
                // storage and upload time without improving the transcript.
                setAudioSamplingRate(16_000)
                setAudioChannels(1)
                setAudioEncodingBitRate(64_000)
                setOutputFile(target.absolutePath)
                prepare()
                start()
            }
        }

        started.onSuccess {
            recorder = it
            _error.value = null
            _isRecording.value = true
        }.onFailure { failure ->
            // Roll the whole thing back: no half-started recorder, no orphaned
            // zero-byte file, and no foreground notification for a recording
            // that is not happening.
            target.delete()
            outputFile = null
            _error.value = "无法开始录音：${failure.message ?: "麦克风被其他应用占用"}"
            stopSelf()
        }
    }

    private suspend fun stopAndSave() {
        val active = recorder ?: return
        val file = outputFile

        // A recorder stopped before it captured anything throws; the file is
        // useless either way, so treat it as a cancelled capture rather than an
        // error the user has to acknowledge.
        val captured = runCatching { active.stop() }.isSuccess
        runCatching { active.release() }
        recorder = null
        _isRecording.value = false

        if (captured && file != null && file.length() > 0) {
            sessions.registerSyncedFile(
                device = DeviceKind.PHONE,
                devicePath = "phone/${file.name}",
                localFile = file,
                durationSeconds = (System.currentTimeMillis() - startedAtEpochMs) / 1000,
                sizeBytes = file.length(),
                recordedAtEpochMs = startedAtEpochMs,
            )
        } else {
            file?.delete()
        }

        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun buildRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else MediaRecorder()

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, DitingApp.CHANNEL_CAPTURE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        runCatching { recorder?.release() }
        recorder = null
        _isRecording.value = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1002
        const val ACTION_START = "com.diting.app.CAPTURE_START"
        const val ACTION_STOP = "com.diting.app.CAPTURE_STOP"

        private val _isRecording = MutableStateFlow(false)

        /** Observed by the recording screen so its UI matches the actual state. */
        val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

        private val _error = MutableStateFlow<String?>(null)

        /**
         * Why the last capture refused to start.
         *
         * A service has no way to show a dialog, and silently doing nothing when
         * the record button is tapped is indistinguishable from a broken app —
         * so the reason is published here for the shell to display.
         */
        val error: StateFlow<String?> = _error.asStateFlow()

        fun clearError() { _error.value = null }

        fun start(context: Context) = context.startForegroundService(
            Intent(context, CaptureService::class.java).setAction(ACTION_START)
        )

        fun stop(context: Context) = context.startService(
            Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
        )
    }
}
