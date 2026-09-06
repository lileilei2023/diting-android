package com.diting.app.data.repo

import com.diting.app.data.db.InsightDao
import com.diting.app.data.db.InsightEntity
import com.diting.app.data.db.MemoryDao
import com.diting.app.data.db.MemoryEdgeEntity
import com.diting.app.data.db.MemoryNodeEntity
import com.diting.app.data.db.ProactiveCardDao
import com.diting.app.data.db.ProactiveCardEntity
import com.diting.app.data.db.SegmentDao
import com.diting.app.data.db.SessionDao
import com.diting.domain.memory.MemoryNode
import com.diting.domain.memory.MemoryNodeType
import com.diting.domain.memory.NodeConfidence
import com.diting.domain.memory.RetentionAction
import com.diting.domain.memory.RetentionEvaluator
import com.diting.domain.memory.RetentionPolicy
import com.diting.domain.model.Citation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val MemoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** What one retention sweep did, for the 记忆与遗忘 screen. */
data class SweepReport(
    val audioDeleted: Int = 0,
    val bytesFreed: Long = 0,
    val transcriptsCompressed: Int = 0,
    val nodesArchived: Int = 0,
)

@Singleton
class MemoryRepository @Inject constructor(
    private val memoryDao: MemoryDao,
    private val insightDao: InsightDao,
    private val cardDao: ProactiveCardDao,
    private val sessionDao: SessionDao,
    private val segmentDao: SegmentDao,
) {

    fun observeLiveNodes(): Flow<List<MemoryNode>> =
        memoryDao.observeLive().map { rows -> rows.map { it.toDomain() } }

    fun observeByType(type: MemoryNodeType): Flow<List<MemoryNode>> =
        memoryDao.observeByType(type).map { rows -> rows.map { it.toDomain() } }

    fun observeLiveCount(): Flow<Int> = memoryDao.observeLiveCount()
    fun observeArchivedCount(): Flow<Int> = memoryDao.observeArchivedCount()

    fun observeInsights(): Flow<List<InsightEntity>> = insightDao.observeAll()
    fun observeProactiveCards(): Flow<List<ProactiveCardEntity>> = cardDao.observeOpen()

    /**
     * Records a mention of something, creating the node or bumping its counters.
     *
     * New nodes arrive as [NodeConfidence.PROPOSED]. Principle 3: an AI guess is
     * not a fact until the user taps 确认, and only confirmed nodes may generate a
     * proactive card.
     */
    suspend fun observeMention(
        type: MemoryNodeType,
        label: String,
        citations: List<Citation>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): MemoryNode {
        val existing = memoryDao.findNodeByLabel(label, type)

        val entity = if (existing == null) {
            MemoryNodeEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                label = label,
                firstSeenEpochMs = nowEpochMs,
                lastSeenEpochMs = nowEpochMs,
                mentionCount = 1,
                confidence = NodeConfidence.PROPOSED,
                citationsJson = citations.encode(),
            )
        } else {
            existing.copy(
                lastSeenEpochMs = nowEpochMs,
                mentionCount = existing.mentionCount + 1,
                // A node that comes up again is warm again, so un-archive it.
                archived = false,
                citationsJson = (existing.citations() + citations).distinct().encode(),
            )
        }
        memoryDao.upsertNode(entity)
        return entity.toDomain()
    }

    suspend fun link(fromId: String, toId: String, relation: String, weight: Float = 1f) =
        memoryDao.upsertEdges(listOf(MemoryEdgeEntity(fromId, toId, relation, weight)))

    /** 确认 — writes the node into the graph as fact. */
    suspend fun confirmNode(id: String) =
        memoryDao.setConfidence(id, NodeConfidence.CONFIRMED.name)

    /** 存疑 — kept, marked falsifiable, and never asserted back to the user. */
    suspend fun disputeNode(id: String) =
        memoryDao.setConfidence(id, NodeConfidence.DISPUTED.name)

    suspend fun confirmInsight(id: String) =
        insightDao.setConfidence(id, NodeConfidence.CONFIRMED.name)

    suspend fun disputeInsight(id: String) =
        insightDao.setConfidence(id, NodeConfidence.DISPUTED.name)

    suspend fun dismissInsight(id: String) = insightDao.dismiss(id)

    suspend fun saveInsight(insight: InsightEntity) = insightDao.upsert(insight)

    suspend fun saveProactiveCard(card: ProactiveCardEntity) = cardDao.upsert(card)

    suspend fun markCardActedOn(id: String) = cardDao.markActedOn(id)

    /**
     * The user says a card did not matter. Feeds noise gate 4: the phrase is
     * remembered so similar sentences stop reaching the graph.
     */
    suspend fun dismissCardAsUnimportant(id: String) = cardDao.dismissAsUnimportant(id)

    /**
     * Applies the retention policy.
     *
     * Everything cited is skipped by the queries themselves rather than filtered
     * afterwards, so the "被引用即永久" rule cannot be defeated by a bug in this
     * method's control flow.
     */
    suspend fun sweep(
        policy: RetentionPolicy = RetentionPolicy.Default,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): SweepReport {
        val evaluator = RetentionEvaluator(policy)
        var report = SweepReport()

        // Tier 1 — audio. Deleting the file leaves the transcript and every
        // citation pointing at it intact; only playback is lost.
        val audioCutoff = nowEpochMs - policy.audioLifetime.inWholeMilliseconds
        sessionDao.audioExpiredBefore(audioCutoff).forEach { session ->
            val action = evaluator.forAudio(nowEpochMs - session.startedAtEpochMs, session.isCited)
            if (action == RetentionAction.DELETE_AUDIO) {
                val file = session.audioPath?.let(::File)
                val size = file?.length() ?: 0
                if (file?.delete() == true || file == null) {
                    sessionDao.clearAudioPath(session.id)
                    report = report.copy(
                        audioDeleted = report.audioDeleted + 1,
                        bytesFreed = report.bytesFreed + size,
                    )
                }
            }
        }

        // Tier 3 — graph nodes go cold. Never deleted: they stay searchable, they
        // just stop earning the right to interrupt.
        val coldCutoff = nowEpochMs - policy.graphColdAge.inWholeMilliseconds
        val cold = memoryDao.coldNodes(coldCutoff)
        if (cold.isNotEmpty()) {
            memoryDao.archive(cold.map { it.id })
            report = report.copy(nodesArchived = cold.size)
        }

        return report
    }

    /** Rows the 「本月到期」 list shows, so the user can rescue any of them. */
    suspend fun expiringSoon(
        policy: RetentionPolicy = RetentionPolicy.Default,
        withinMs: Long = 30L * 24 * 60 * 60 * 1000,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<ExpiringItem> {
        val cutoff = nowEpochMs + withinMs - policy.audioLifetime.inWholeMilliseconds
        return sessionDao.audioExpiredBefore(cutoff).map { session ->
            ExpiringItem(
                sessionId = session.id,
                title = session.title,
                expiresAtEpochMs = session.startedAtEpochMs +
                    policy.audioLifetime.inWholeMilliseconds,
                sizeBytes = session.audioPath?.let { File(it).length() } ?: 0,
            )
        }
    }

    /** 「保留」 on an expiring item marks it cited, which makes it permanent. */
    suspend fun keepForever(sessionId: String) = sessionDao.markCited(sessionId)

    /** 「全部遗忘」 — behind a second confirmation in the UI. */
    suspend fun forgetEverything() = memoryDao.forgetEverything()
}

data class ExpiringItem(
    val sessionId: String,
    val title: String,
    val expiresAtEpochMs: Long,
    val sizeBytes: Long,
)

private fun List<Citation>.encode(): String =
    MemoryJson.encodeToString(ListSerializer(Citation.serializer()), this)

private fun MemoryNodeEntity.citations(): List<Citation> =
    runCatching {
        MemoryJson.decodeFromString(ListSerializer(Citation.serializer()), citationsJson)
    }.getOrDefault(emptyList())

fun MemoryNodeEntity.toDomain() = MemoryNode(
    id = id,
    type = type,
    label = label,
    firstSeenEpochMs = firstSeenEpochMs,
    lastSeenEpochMs = lastSeenEpochMs,
    mentionCount = mentionCount,
    archived = archived,
    isCited = isCited,
    confidence = confidence,
)
