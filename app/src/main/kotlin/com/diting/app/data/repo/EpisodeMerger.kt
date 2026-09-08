package com.diting.app.data.repo

import android.util.Log
import com.diting.app.data.prefs.SettingsStore
import com.diting.app.di.AiClientFactory
import com.diting.app.di.RecordingsDir
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stitches adjacent recordings into episodes and re-reads each episode as a
 * whole.
 *
 * Per-fragment summaries are kept as a fallback, but when an understanding
 * model is configured the merged transcript is summarised again in one pass —
 * that is the point of merging: the model sees the conversation, not slices.
 */
@Singleton
class EpisodeMerger @Inject constructor(
    private val sessions: SessionRepository,
    private val aiClients: AiClientFactory,
    private val settings: SettingsStore,
    @RecordingsDir private val recordingsDir: File,
) {
    /** @return how many episodes were created this run. */
    suspend fun run(): Int {
        val understanding = runCatching { aiClients.understanding() }.getOrNull()
        val created = runCatching { sessions.mergeAdjacentEpisodes(recordingsDir, stitchSummary = understanding == null) }
            .onFailure { Log.w(TAG, "merge failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (created.isNotEmpty()) Log.i(TAG, "${created.size} episodes merged")

        if (understanding == null) return created.size
        // Includes episodes from earlier runs whose read failed (offline, quota).
        val pending = runCatching { sessions.episodesWithoutSummary() }.getOrDefault(emptyList())
        if (pending.isEmpty()) return created.size
        val scenes = settings.scenes.first()
        val prompt = settings.summaryPrompt.first()
        pending.forEach { id ->
            runCatching { sessions.summarize(id, understanding, scenes, prompt) }
                .onSuccess { Log.i(TAG, "episode $id read as a whole: ${it?.points?.size ?: 0} points") }
                .onFailure { Log.w(TAG, "episode $id read failed: ${it.message}") }
        }
        return created.size
    }

    private companion object { const val TAG = "EpisodeMerger" }
}
