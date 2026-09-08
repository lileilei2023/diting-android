package com.diting.app.data.repo

import com.diting.ai.asr.AsrClient
import com.diting.ai.understanding.SessionSummary
import com.diting.ai.understanding.UnderstandingService
import com.diting.app.data.db.HotwordDao
import com.diting.app.data.db.HotwordEntity
import com.diting.app.data.db.SegmentDao
import com.diting.app.data.db.SegmentEntity
import com.diting.app.data.db.SessionDao
import com.diting.app.data.db.SessionEntity
import com.diting.app.data.db.SpeakerDao
import com.diting.app.data.db.SpeakerEntity
import com.diting.domain.model.Citation
import com.diting.domain.model.DeviceKind
import com.diting.domain.model.Segment
import com.diting.domain.model.Session
import com.diting.domain.model.Speaker
import com.diting.domain.model.TranscriptState
import com.diting.domain.scene.Scene
import com.diting.domain.scene.SceneDetector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val RepoJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A session plus everything the detail screen needs. */
data class SessionWithTranscript(
    val session: Session,
    val segments: List<Segment>,
    val speakers: List<Speaker>,
    val summary: SessionSummary?,
)

private const val MIN_IMPORT_BYTES = 100_000L
private const val ACCIDENTAL_PRESS_MS = 30_000L
/** Two recordings closer than this are one conversation. */
private const val EPISODE_GAP_MS = 10 * 60 * 1000L
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "wav", "aac")
private val PART_FILE = Regex("\\.part\\d+\\.[A-Za-z0-9]+$")
private val FILE_TIMESTAMP = Regex("(\\d{4})-(\\d{2})-(\\d{2})[ _](\\d{2})-(\\d{2})-(\\d{2})")

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val segmentDao: SegmentDao,
    private val speakerDao: SpeakerDao,
    private val hotwordDao: HotwordDao,
    private val memory: MemoryRepository,
) {

    fun observeAll(): Flow<List<Session>> =
        sessionDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observeBetween(fromEpochMs: Long, toEpochMs: Long): Flow<List<Session>> =
        sessionDao.observeBetween(fromEpochMs, toEpochMs).map { rows -> rows.map { it.toDomain() } }

    fun observeDetail(sessionId: String): Flow<SessionWithTranscript?> = combine(
        sessionDao.observe(sessionId),
        segmentDao.observeForSession(sessionId),
        speakerDao.observeForSession(sessionId),
    ) { session, segments, speakers ->
        session?.let { row ->
            SessionWithTranscript(
                session = row.toDomain(),
                segments = segments.map { it.toDomain() },
                speakers = speakers.map { it.toDomain() },
                summary = row.summaryJson?.let {
                    runCatching { RepoJson.decodeFromString<StoredSummary>(it).toDomain() }
                        .getOrNull()
                },
            )
        }
    }

    /**
     * Registers a synced device file as a session.
     *
     * Idempotent on `(device, deviceFilePath)`: a re-sync of a partially
     * transferred file must update the existing row rather than create a second
     * session for the same recording.
     */
    suspend fun registerSyncedFile(
        device: DeviceKind,
        devicePath: String,
        localFile: File,
        durationSeconds: Long,
        sizeBytes: Long,
        recordedAtEpochMs: Long,
    ): String {
        val existing = sessionDao.findByDeviceFile(device, devicePath)
        val id = existing?.id ?: UUID.randomUUID().toString()

        sessionDao.upsert(
            (existing ?: SessionEntity(
                id = id,
                title = defaultTitle(recordedAtEpochMs),
                startedAtEpochMs = recordedAtEpochMs,
                durationMs = durationSeconds * 1000,
                device = device,
                audioPath = localFile.absolutePath,
                deviceFilePath = devicePath,
                sceneId = null,
            )).copy(
                audioPath = localFile.absolutePath,
                durationMs = durationSeconds * 1000,
                syncedBytes = localFile.length(),
                totalBytes = sizeBytes,
                // A file that changed size must be transcribed again.
                transcriptState = if (existing?.syncedBytes == localFile.length()) {
                    existing.transcriptState
                } else {
                    TranscriptState.PENDING
                },
            )
        )
        return id
    }


    // ---- speakers ---------------------------------------------------------------

    /** Names a diarisation label in one session; `owner` makes it 「你」. */
    suspend fun nameSpeaker(sessionId: String, label: String, name: String?, owner: Boolean) {
        val existing = speakerDao.forSession(sessionId).firstOrNull { it.label == label }
        if (existing == null) speakerDao.upsertAll(listOf(SpeakerEntity(id = "$sessionId:$label", sessionId = sessionId, label = label)))
        speakerDao.setIdentity(sessionId, label, name?.takeIf { it.isNotBlank() }, owner)
    }

    suspend fun knownSpeakerNames(): List<String> = speakerDao.knownNames()

    // ---- deleting ---------------------------------------------------------------

    /** Removes a session, its transcript and its audio file. Irreversible; the UI confirms first. */
    suspend fun deleteSession(id: String) {
        val row = sessionDao.find(id) ?: return
        // An episode takes its fragments with it; they were hidden anyway.
        sessionDao.partsOf(id).forEach { deleteSession(it.id) }
        segmentDao.deleteForSession(id)
        speakerDao.deleteForSession(id)
        sessionDao.delete(id)
        row.audioPath?.let { File(it).delete() }
    }

    // ---- episodes: fragments stitched into one conversation -----------------

    /**
     * Folds consecutive recordings into one episode.
     *
     * The MR20 (and a user pressing the key repeatedly) splits one conversation
     * into many short files. Summarising each on its own gives the brain a few
     * sentences of context and a skewed reading; stitched, it reads the whole
     * exchange. Audio is byte-concatenated (raw MPEG frames, same encoder), the
     * transcripts are shifted by the preceding parts' durations, and the parts
     * are hidden behind [SessionEntity.mergedIntoId] rather than deleted, so
     * sync de-duplication and existing citations keep working.
     *
     * @return the new episode's id, or null when there is nothing to merge.
     */
    suspend fun mergeIntoEpisode(ids: List<String>, targetDir: File, stitchSummary: Boolean): String? {
        val parts = ids.mapNotNull { sessionDao.find(it) }.sortedBy { it.startedAtEpochMs }
        if (parts.size < 2) return null
        val files = parts.map { p -> p.audioPath?.let(::File)?.takeIf { it.exists() } ?: return null }

        val id = UUID.randomUUID().toString()
        val merged = File(targetDir, "episode_$id.mp3")
        merged.outputStream().use { out ->
            files.forEach { f -> f.inputStream().use { it.copyTo(out) } }
        }

        // Shift each part's transcript by the audio that precedes it.
        var offset = 0L
        val segments = mutableListOf<SegmentEntity>()
        val speakers = mutableListOf<SpeakerEntity>()
        val summaries = mutableListOf<StoredSummary>()
        parts.forEachIndexed { pi, part ->
            val partOffset = offset
            segmentDao.forSession(part.id).forEachIndexed { si, seg ->
                segments += seg.copy(
                    id = "$id:$pi:$si",
                    sessionId = id,
                    startMs = seg.startMs + partOffset,
                    endMs = seg.endMs + partOffset,
                )
            }
            speakerDao.forSession(part.id).forEach { sp ->
                if (speakers.none { it.label == sp.label }) speakers += sp.copy(id = "$id:${sp.label}", sessionId = id)
            }
            part.summaryJson?.let { runCatching { RepoJson.decodeFromString<StoredSummary>(it) }.getOrNull() }?.let { summaries += it }
            offset += part.durationMs
        }

        val first = parts.first()
        val combined = StoredSummary(
            title = summaries.firstOrNull()?.title?.ifBlank { null } ?: first.title,
            oneLine = summaries.joinToString("\n") { it.oneLine.trim() }.trim(),
            points = summaries.flatMap { it.points },
            decisions = summaries.flatMap { it.decisions },
            todos = summaries.flatMap { it.todos },
            risks = summaries.flatMap { it.risks },
        )
        sessionDao.upsert(
            SessionEntity(
                id = id,
                title = combined.title,
                startedAtEpochMs = first.startedAtEpochMs,
                durationMs = offset,
                device = first.device,
                audioPath = merged.absolutePath,
                deviceFilePath = "episode/${first.deviceFilePath ?: first.id}",
                sceneId = first.sceneId,
                transcriptState = TranscriptState.DONE,
                syncedBytes = merged.length(),
                totalBytes = merged.length(),
                // With a model available the stitched summary is only a fallback;
                // leaving it empty makes the whole-episode read happen (and retry).
                summaryJson = if (summaries.isEmpty() || !stitchSummary) null else RepoJson.encodeToString(combined),
                brainUploadedAt = System.currentTimeMillis(),
            )
        )
        segmentDao.insertAll(segments)
        speakerDao.upsertAll(speakers)
        sessionDao.markMerged(parts.map { it.id }, id)
        return id
    }

    /**
     * Finds runs of transcribed recordings on the same device whose gaps are
     * under [gapMs] and merges each run. Returns the new episode ids.
     */
    suspend fun mergeAdjacentEpisodes(targetDir: File, stitchSummary: Boolean, gapMs: Long = EPISODE_GAP_MS): List<String> {
        val created = mutableListOf<String>()
        sessionDao.mergeCandidates().groupBy { it.device }.values.forEach { list ->
            val sorted = list.sortedBy { it.startedAtEpochMs }
            var run = mutableListOf<SessionEntity>()
            suspend fun flush() {
                if (run.size >= 2) mergeIntoEpisode(run.map { it.id }, targetDir, stitchSummary)?.let { created += it }
                run = mutableListOf()
            }
            for (s in sorted) {
                val prev = run.lastOrNull()
                if (prev != null && s.startedAtEpochMs - (prev.startedAtEpochMs + prev.durationMs) > gapMs) flush()
                run += s
            }
            flush()
        }
        return created
    }

    suspend fun episodesWithoutSummary(): List<String> = sessionDao.episodesWithoutSummary()

    /** Ids of the recordings folded into [episodeId]. */
    suspend fun partsOf(episodeId: String): List<Session> = sessionDao.partsOf(episodeId).map { it.toDomain() }

    /** Sessions the brain heard nothing in; candidates for 「清理无内容录音」. */
    fun observeEmptyRecordings(): Flow<List<Session>> =
        sessionDao.observeEmptyRecordings().map { rows -> rows.map { it.toDomain() } }

    /** Deletes every empty recording. @return how many went. */
    suspend fun deleteEmptyRecordings(): Int {
        val rows = sessionDao.observeEmptyRecordings().first()
        rows.forEach { deleteSession(it.id) }
        return rows.size
    }

    // ---- importing recordings that did not come over BLE ----------------------

    /**
     * Registers one local audio file as an MR20-style session. Used by the
     * 「导入录音文件」 picker and by [scanImports]. The recording time comes from
     * an MR20 file name (`2026-07-09 17-07-39.mp3`) when present, else mtime.
     */
    suspend fun importLocalRecording(file: File): String? {
        if (!file.exists() || file.length() < MIN_IMPORT_BYTES) return null
        val devicePath = "import/${file.name}"
        val duration = probeDurationMs(file) ?: return null
        return registerSyncedFile(
            device = DeviceKind.MR20,
            devicePath = devicePath,
            localFile = file,
            durationSeconds = duration / 1000,
            sizeBytes = file.length(),
            recordedAtEpochMs = recordedAtFromName(file.name) ?: file.lastModified(),
        )
    }

    /** Picks up audio files dropped into [dir] that no session references yet. */
    suspend fun scanImports(dir: File): Int {
        // Chunk pieces the uploader writes next to the original are not recordings.
        sessionDao.deleteImportedParts()
        val known = sessionDao.knownAudioPaths().toSet()
        var count = 0
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in AUDIO_EXTENSIONS && !PART_FILE.containsMatchIn(f.name) }
            ?.filter { it.absolutePath !in known }
            ?.forEach { if (importLocalRecording(it) != null) count++ }
        return count
    }

    private fun probeDurationMs(file: File): Long? = runCatching {
        android.media.MediaMetadataRetriever().use { r ->
            r.setDataSource(file.absolutePath)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }.getOrNull()?.takeIf { it > 0 }

    private fun recordedAtFromName(name: String): Long? =
        FILE_TIMESTAMP.find(name)?.destructured?.let { (y, mo, d, h, mi, s) ->
            runCatching {
                java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
        }

    /**
     * Stored summaries for a date range, keyed by session id.
     *
     * A row whose JSON no longer parses (an older schema, a truncated write) is
     * dropped rather than failing the whole screen — one bad summary must not
     * blank out 今日谛听.
     */
    fun observeSummariesBetween(
        fromEpochMs: Long,
        toEpochMs: Long,
    ): Flow<Map<String, StoredSummary>> =
        sessionDao.observeSummariesBetween(fromEpochMs, toEpochMs).map { rows ->
            rows.mapNotNull { row ->
                runCatching { RepoJson.decodeFromString<StoredSummary>(row.summaryJson) }
                    .getOrNull()
                    ?.let { row.id to it }
            }.toMap()
        }

    /** Device paths already synced, with the byte count, so transfers can resume. */
    suspend fun syncedBytesByDevicePath(device: DeviceKind): Map<String, Long> =
        sessionDao.syncedByDevice(device).associate { it.path to it.bytes }

    suspend fun pendingTranscription(): List<Session> =
        sessionDao.pendingTranscription().map { it.toDomain() }

    /**
     * Transcribes one session and stores the result.
     *
     * Hotwords are passed to the recogniser rather than applied afterwards: biasing
     * the model is what makes 「欧艾斯艾斯」 come back as 「OSS」 in the first place,
     * and post-hoc substitution would corrupt legitimate homophone text.
     */
    suspend fun transcribe(sessionId: String, asr: AsrClient) {
        val session = sessionDao.find(sessionId) ?: return
        val audio = session.audioPath?.let(::File)
        if (audio == null || !audio.exists()) {
            sessionDao.upsert(
                session.copy(
                    transcriptState = TranscriptState.FAILED,
                    transcriptError = "音频文件不存在，可能已过 30 天保留期",
                )
            )
            return
        }

        sessionDao.upsert(session.copy(transcriptState = TranscriptState.RUNNING))

        try {
            val result = asr.transcribe(audio, hotwordDao.enabledWords())

            val segments = result.segments.mapIndexed { index, segment ->
                SegmentEntity(
                    id = "$sessionId:$index",
                    sessionId = sessionId,
                    speakerLabel = segment.speakerLabel ?: "A",
                    startMs = segment.startMs,
                    endMs = segment.endMs,
                    text = segment.text,
                )
            }
            segmentDao.insertAll(segments)

            speakerDao.upsertAll(
                segments.map { it.speakerLabel }.distinct().map { label ->
                    SpeakerEntity(
                        id = "$sessionId:$label",
                        sessionId = sessionId,
                        label = label,
                    )
                }
            )

            sessionDao.upsert(
                session.copy(
                    transcriptState = TranscriptState.DONE,
                    transcriptError = null,
                    durationMs = maxOf(session.durationMs, result.durationMs),
                )
            )
        } catch (e: Exception) {
            sessionDao.upsert(
                session.copy(
                    transcriptState = TranscriptState.FAILED,
                    transcriptError = e.message ?: e::class.simpleName,
                )
            )
        }
    }

    /**
     * Fills a session from the brain's `/upload` report: transcript, title,
     * summary and to-dos, exactly as the on-device pipeline would have.
     *
     * The brain returns turns without timestamps, so they are spread evenly over
     * the recording. "回到原声" lands near, not on, the sentence — a known
     * trade-off until the server returns per-turn offsets.
     */
    suspend fun applyBrainReport(sessionId: String, report: com.diting.app.brain.UploadReport) {
        val session = sessionDao.find(sessionId) ?: return
        val turns = report.turns
        if (turns.isNotEmpty()) {
            val duration = maxOf(session.durationMs, 1000L)
            val totalChars = turns.sumOf { it.text.length }.coerceAtLeast(1)
            var cursor = 0L
            val segments = turns.mapIndexed { index, turn ->
                val span = duration * turn.text.length / totalChars
                val entity = SegmentEntity(
                    id = "$sessionId:$index",
                    sessionId = sessionId,
                    speakerLabel = speakerLetter(turn.speaker),
                    startMs = cursor,
                    endMs = (cursor + span).coerceAtMost(duration),
                    text = turn.text,
                )
                cursor += span
                entity
            }
            segmentDao.deleteForSession(sessionId)
            segmentDao.insertAll(segments)
            speakerDao.upsertAll(
                segments.map { it.speakerLabel }.distinct().map { label ->
                    SpeakerEntity(id = "$sessionId:$label", sessionId = sessionId, label = label)
                }
            )
        }

        val summary = if (report.summary.isNotBlank() || report.items.isNotEmpty()) {
            StoredSummary(
                title = report.title.ifBlank { session.title },
                oneLine = report.summary,
                points = report.facts.map { StoredLine(it) },
                todos = report.items.filter { it.task.isNotBlank() }.map { item ->
                    StoredTodo(
                        text = item.task,
                        owner = item.owner.takeIf { it.isNotBlank() },
                        // The brain counts due days from the recording, so a
                        // two-month-old file must not read "3 天内" today.
                        due = item.dueDays?.let { d ->
                            val at = java.time.Instant.ofEpochMilli(session.startedAtEpochMs + d * 86_400_000L).atZone(java.time.ZoneId.systemDefault())
                            "${at.monthValue}月${at.dayOfMonth}日前"
                        },
                    )
                },
            )
        } else null

        if (summary != null) feedMemory(sessionId, session.durationMs, summary, session.startedAtEpochMs)

        // A press shorter than half a minute with nothing in it is a mis-press,
        // not a recording; keeping it only clutters 记录 with "转写失败" rows.
        if (turns.isEmpty() && summary == null && session.durationMs < ACCIDENTAL_PRESS_MS) {
            deleteSession(sessionId)
            return
        }

        sessionDao.upsert(
            session.copy(
                title = report.title.ifBlank { session.title },
                transcriptState = if (turns.isNotEmpty()) TranscriptState.DONE else TranscriptState.FAILED,
                transcriptError = if (turns.isNotEmpty()) null else "大脑没有识别出语音（可能是静音或太短）",
                summaryJson = summary?.let { RepoJson.encodeToString(it) } ?: session.summaryJson,
                brainUploadedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Graph nodes + hotword candidates from one summary. Idempotent per session only by label bumps. */
    private suspend fun feedMemory(sessionId: String, durationMs: Long, summary: StoredSummary, recordedAtEpochMs: Long) {
        val candidates = memory.ingestBrainFacts(
            sessionId = sessionId,
            durationMs = durationMs,
            facts = summary.points.map { it.text },
            todos = summary.todos.map { it.text to it.owner },
            // Graph time is when it was said, not when the brain got round to it.
            nowEpochMs = recordedAtEpochMs,
        )
        val now = System.currentTimeMillis()
        candidates.forEach { word ->
            // Disabled until the user taps it on the 热词 page — a candidate, not a fact.
            hotwordDao.insertIgnoring(HotwordEntity(word = word, source = "DISCOVERED", enabled = false, createdAtEpochMs = now))
        }
    }

    /** Rebuilds the graph from stored summaries when it is empty (sessions summarised before the graph existed). */
    suspend fun backfillMemoryIfEmpty() {
        if (!memory.isGraphEmpty()) return
        hotwordDao.deleteDisabledDiscovered()
        val rows = sessionDao.observeSummariesBetween(0L, Long.MAX_VALUE).first()
        for (row in rows) {
            val summary = runCatching { RepoJson.decodeFromString<StoredSummary>(row.summaryJson) }.getOrNull() ?: continue
            val session = sessionDao.find(row.id) ?: continue
            feedMemory(row.id, session.durationMs, summary, session.startedAtEpochMs)
        }
    }

    /**
     * "说话人1" → "A", "说话人2" → "B" … so brain transcripts use the same one-letter
     * labels as on-device ones, and the screen never reads "说话人 说话人1".
     */
    private fun speakerLetter(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length == 1 && trimmed[0].isLetter()) return trimmed.uppercase()
        val n = Regex("\\d+").find(trimmed)?.value?.toIntOrNull()
        if (n != null && n in 1..26) return ('A' + (n - 1)).toString()
        return trimmed.ifBlank { "A" }
    }

    /** Marks a session's upload attempt so the failure is visible, not just logged. */
    suspend fun markBrainUploadFailed(sessionId: String, reason: String) {
        val session = sessionDao.find(sessionId) ?: return
        if (session.transcriptState == TranscriptState.DONE) return
        sessionDao.upsert(session.copy(transcriptState = TranscriptState.FAILED, transcriptError = reason))
    }

    suspend fun markBrainUploading(sessionId: String) {
        val session = sessionDao.find(sessionId) ?: return
        if (session.transcriptState == TranscriptState.DONE) return
        sessionDao.upsert(session.copy(transcriptState = TranscriptState.RUNNING, transcriptError = null))
    }

    /** Generates and stores the summary, minutes and to-dos for a session. */
    suspend fun summarize(
        sessionId: String,
        understanding: UnderstandingService,
        scenes: List<Scene>,
        extraPrompt: String?,
    ): SessionSummary? {
        val session = sessionDao.find(sessionId) ?: return null
        val segments = segmentDao.forSession(sessionId).map { it.toDomain() }
        if (segments.isEmpty()) return null

        val scene = session.sceneId?.let { id -> scenes.firstOrNull { it.id == id } }
            ?: SceneDetector(scenes).detect(segments.joinToString(" ") { it.text })

        val summary = understanding.summarize(segments, scene, extraPrompt)

        sessionDao.upsert(
            session.copy(
                title = summary.title.ifBlank { session.title },
                sceneId = session.sceneId ?: scene?.id,
                summaryJson = RepoJson.encodeToString(StoredSummary.from(summary)),
            )
        )

        // Everything the summary points at is now cited, and therefore exempt from
        // the retention sweep. Marking it here — rather than deriving it later —
        // is what makes "被引用即永久" hold even after the summary is edited.
        markCited(
            (summary.points + summary.decisions + summary.risks).mapNotNull { it.citation } +
                summary.todos.mapNotNull { it.citation }
        )
        feedMemory(sessionId, session.durationMs, StoredSummary.from(summary), session.startedAtEpochMs)

        return summary
    }

    /** The "随手教" loop: correcting a word both fixes the text and teaches a hotword. */
    suspend fun correctSegment(segmentId: String, newText: String): String? {
        val segment = segmentDao.find(segmentId) ?: return null
        val original = segment.correctedFrom ?: segment.text
        segmentDao.update(segment.copy(text = newText, correctedFrom = original))
        return original
    }

    suspend fun setScene(sessionId: String, sceneId: String?, overridden: Boolean) {
        val session = sessionDao.find(sessionId) ?: return
        sessionDao.upsert(session.copy(sceneId = sceneId, sceneOverridden = overridden))
    }

    suspend fun rename(sessionId: String, title: String) {
        val session = sessionDao.find(sessionId) ?: return
        sessionDao.upsert(session.copy(title = title))
    }

    suspend fun delete(sessionId: String) {
        sessionDao.find(sessionId)?.audioPath?.let { runCatching { File(it).delete() } }
        sessionDao.delete(sessionId)
    }

    suspend fun search(query: String): List<Segment> =
        segmentDao.search(query).map { it.toDomain() }

    /**
     * The transcript text a citation points at.
     *
     * Prefers the exact segment id; falls back to every segment overlapping the
     * cited span, which is what a citation spanning a re-transcribed session
     * degrades to.
     */
    suspend fun textFor(citation: Citation): String? {
        citation.segmentId?.let { id -> segmentDao.find(id)?.let { return it.text } }
        return segmentDao.forSession(citation.sessionId)
            .filter { it.endMs > citation.startMs && it.startMs < citation.endMs }
            .joinToString("") { it.text }
            .takeIf { it.isNotBlank() }
    }

    /** Marks every cited segment and session so retention leaves them alone. */
    suspend fun markCited(citations: List<Citation>) {
        if (citations.isEmpty()) return
        segmentDao.markCited(citations.mapNotNull { it.segmentId })
        citations.map { it.sessionId }.distinct().forEach { sessionDao.markCited(it) }
    }

    private fun defaultTitle(epochMs: Long): String {
        val time = java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
        return "%d月%d日 %02d:%02d 的录音".format(
            time.monthValue, time.dayOfMonth, time.hour, time.minute,
        )
    }
}

// -- mapping --------------------------------------------------------------------

fun SessionEntity.toDomain() = Session(
    id = id,
    title = title,
    startedAtEpochMs = startedAtEpochMs,
    durationMs = durationMs,
    device = device,
    audioPath = audioPath,
    deviceFilePath = deviceFilePath,
    sceneId = sceneId,
    transcriptState = transcriptState,
    transcriptError = transcriptError,
    sceneOverridden = sceneOverridden,
)

fun SegmentEntity.toDomain() = Segment(
    id = id,
    sessionId = sessionId,
    speakerLabel = speakerLabel,
    startMs = startMs,
    endMs = endMs,
    text = text,
    correctedFrom = correctedFrom,
    intent = intent,
    isCited = isCited,
)

fun SpeakerEntity.toDomain() = Speaker(
    label = label,
    personId = personId,
    displayName = displayName,
    isOwner = isOwner,
)

/** Serialisable mirror of [SessionSummary] — the domain type is not @Serializable. */
@kotlinx.serialization.Serializable
data class StoredSummary(
    val title: String,
    val oneLine: String,
    val points: List<StoredLine> = emptyList(),
    val decisions: List<StoredLine> = emptyList(),
    val todos: List<StoredTodo> = emptyList(),
    val risks: List<StoredLine> = emptyList(),
) {
    fun toDomain() = SessionSummary(
        title = title,
        oneLine = oneLine,
        points = points.map { it.toDomain() },
        decisions = decisions.map { it.toDomain() },
        risks = risks.map { it.toDomain() },
        todos = todos.map { it.toDomain() },
    )

    companion object {
        fun from(summary: SessionSummary) = StoredSummary(
            title = summary.title,
            oneLine = summary.oneLine,
            points = summary.points.map(StoredLine::from),
            decisions = summary.decisions.map(StoredLine::from),
            risks = summary.risks.map(StoredLine::from),
            todos = summary.todos.map(StoredTodo::from),
        )
    }
}

@kotlinx.serialization.Serializable
data class StoredLine(val text: String, val citation: Citation? = null) {
    fun toDomain() = com.diting.ai.understanding.CitedLine(text, citation)

    companion object {
        fun from(line: com.diting.ai.understanding.CitedLine) = StoredLine(line.text, line.citation)
    }
}

@kotlinx.serialization.Serializable
data class StoredTodo(
    val text: String,
    val owner: String? = null,
    val due: String? = null,
    val citation: Citation? = null,
) {
    fun toDomain() = com.diting.ai.understanding.CitedTodo(text, owner, due, citation)

    companion object {
        fun from(todo: com.diting.ai.understanding.CitedTodo) =
            StoredTodo(todo.text, todo.owner, todo.due, todo.citation)
    }
}

/**
 * A recording the user would rather not see: the brain heard talk but found
 * nothing to act on or remember. Small talk, venting, a meeting's opening
 * seconds. Never applied automatically — it feeds the 清理 card.
 */
fun StoredSummary.isLowValue(): Boolean {
    if (todos.isNotEmpty() || decisions.isNotEmpty()) return false
    val text = "$title $oneLine"
    val hollow = LOW_VALUE_MARKERS.any { text.contains(it) }
    return hollow || points.size <= 1
}

private val LOW_VALUE_MARKERS = listOf(
    "闲聊", "宣泄", "寒暄", "无实质", "无具体", "未出现具体", "仅体现", "没有具体", "无明确", "未提及具体", "碎片化", "无法判断",
)
