package com.diting.app.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackUi(
    val ready: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * The detail page's recessed player. One ExoPlayer per screen; released with
 * the view model. Position is polled rather than event-driven because Media3
 * has no progress callback and the waveform only needs ~5 fps.
 */
class SessionPlayer(context: Context, private val scope: CoroutineScope) {

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()
    private val _state = MutableStateFlow(PlaybackUi())
    val state: StateFlow<PlaybackUi> = _state
    private var ticker: Job? = null
    private var loadedPath: String? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish()
                if (isPlaying) startTicker() else ticker?.cancel()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) player.seekTo(0).also { player.pause() }
                publish()
            }
        })
    }

    /** Points the player at [path] (a local file or a content URI); idempotent. */
    fun load(path: String?, durationHintMs: Long) {
        if (path == null || path == loadedPath) return
        loadedPath = path
        val uri = if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        _state.value = PlaybackUi(ready = true, durationMs = durationHintMs)
    }

    fun toggle() {
        if (loadedPath == null) return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(ms: Long, andPlay: Boolean = true) {
        if (loadedPath == null) return
        player.seekTo(ms.coerceAtLeast(0))
        if (andPlay) player.play()
        publish()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && player.isPlaying) {
                publish()
                delay(200)
            }
        }
    }

    private fun publish() {
        val dur = player.duration.takeIf { it > 0 } ?: _state.value.durationMs
        _state.value = PlaybackUi(
            ready = loadedPath != null,
            playing = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = dur,
        )
    }

    fun release() {
        ticker?.cancel()
        player.release()
    }
}
