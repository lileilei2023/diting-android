package com.diting.app.data.repo

import com.diting.ai.asr.AsrClient
import com.diting.ai.understanding.SessionSummary
import com.diting.ai.understanding.UnderstandingService
import com.diting.app.data.db.HotwordDao
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

@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val segmentDao: SegmentDao,
    private val speakerDao: SpeakerDao,
    private val hotwordDao: HotwordDao,
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
