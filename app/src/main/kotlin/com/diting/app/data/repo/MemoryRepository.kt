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
import kotlinx.coroutines.flow.first
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
                // Sessions can be ingested out of order (imports, backfill), so
                // the span is min/max rather than "now".
                firstSeenEpochMs = minOf(existing.firstSeenEpochMs, nowEpochMs),
                lastSeenEpochMs = maxOf(existing.lastSeenEpochMs, nowEpochMs),
                mentionCount = existing.mentionCount + 1,
                // A node that comes up again is warm again, so un-archive it.
                archived = false,
                citationsJson = (existing.citations() + citations).distinct().encode(),
            )
        }
        memoryDao.upsertNode(entity)
        return entity.toDomain()
    }


    // ---- feeding the graph from the brain's report ---------------------------

    /**
     * Turns one session's brain report into graph nodes.
     *
     * Facts arrive as `主体 · 关系 · 客体` triples. People become PERSON nodes,
     * tasks and promises COMMITMENT, decisions DECISION, risks RISK, everything
     * else a TOPIC keyed on the subject. Every node cites the whole session, so
     * 反复被提 / 复盘 / 成长卡 can count across sessions and jump back to the audio.
     *
     * @return words worth offering as hotword candidates (names, product codes).
     */
    suspend fun ingestBrainFacts(
        sessionId: String,
        durationMs: Long,
        facts: List<String>,
        todos: List<Pair<String, String?>>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<String> {
        val cite = listOf(Citation(sessionId, 0, durationMs.coerceAtLeast(0)))
        val candidates = mutableSetOf<String>()
        val touched = mutableListOf<MemoryNode>()

        val seen = mutableSetOf<Pair<MemoryNodeType, String>>()
        suspend fun mention(type: MemoryNodeType, label: String) {
            val clean = label.trim().take(60)
            if (clean.length < 2 || !seen.add(type to clean)) return
            // One session counts once, however many facts name the same thing;
            // otherwise a single long meeting would look like a recurring theme.
            val existing = memoryDao.findNodeByLabel(clean, type)
            if (existing != null && existing.citations().any { it.sessionId == sessionId }) return
            touched += observeMention(type, clean, cite, nowEpochMs)
        }

        for (fact in facts) {
            val parts = fact.split(" · ").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size < 3) continue
            val (subject, relation, obj) = Triple(parts[0], parts[1], parts.drop(2).joinToString(" · "))
            // "说话人A · 观点 · …" names nobody; a diarisation label is not a person.
            if (subject.startsWith("说话人") || subject.startsWith("对话者") || subject in setOf("A", "B", "用户")) continue
            val subjectIsPerson = PERSON_RELATIONS.any { relation.contains(it) } || looksLikePersonName(subject)
            val objectIsPerson = OBJECT_PERSON_RELATIONS.any { relation.contains(it) }

            if (subjectIsPerson) { mention(MemoryNodeType.PERSON, subject); if (looksLikePersonName(subject)) candidates += subject }
            else { mention(MemoryNodeType.TOPIC, subject); if (looksLikeProductName(subject)) candidates += subject }
            if (objectIsPerson) { mention(MemoryNodeType.PERSON, obj); if (looksLikePersonName(obj)) candidates += obj }

            when {
                COMMITMENT_RELATIONS.any { relation.contains(it) } -> mention(MemoryNodeType.COMMITMENT, "$subject：$obj")
                DECISION_RELATIONS.any { relation.contains(it) } -> mention(MemoryNodeType.DECISION, "$subject：$obj")
                RISK_RELATIONS.any { relation.contains(it) } -> mention(MemoryNodeType.RISK, "$subject：$obj")
            }
        }

        for ((task, owner) in todos) {
            if (task.isBlank()) continue
            mention(MemoryNodeType.COMMITMENT, task)
            if (!owner.isNullOrBlank() && !owner.startsWith("说话人")) { mention(MemoryNodeType.PERSON, owner); if (looksLikePersonName(owner)) candidates += owner }
        }

        // A node that just crossed two sessions is an observation worth showing.
        for (node in touched.distinctBy { it.id }) {
            if (node.mentionCount == 2) {
                insightDao.upsert(
                    InsightEntity(
                        id = "recurring:${node.id}",
                        title = node.label,
                        body = "在 ${node.mentionCount} 场会话里被提起。确认它是同一件事，小谛才会持续跟踪。",
                        kind = "RECURRING",
                        citationsJson = memoryDao.findNode(node.id)?.citationsJson ?: "[]",
                        createdAtEpochMs = nowEpochMs,
                    )
                )
            }
        }
        return candidates.filter { it.length in 2..8 && !it.contains(' ') && GENERIC_WORDS.none { g -> it.contains(g) } }
    }

    /** One-time rebuild for sessions summarised before the graph was wired. */
    suspend fun isGraphEmpty(): Boolean = memoryDao.observeLiveCount().first() == 0 && memoryDao.observeArchivedCount().first() == 0

    private fun looksLikePersonName(s: String): Boolean {
        if (GENERIC_WORDS.any { s.contains(it) }) return false
        val han = s.length in 2..4 && s.all { it in '一'..'鿿' }
        return han && (PERSON_SUFFIXES.any { s.endsWith(it) } || s.startsWith("小") || s.startsWith("老") || s.startsWith("阿"))
    }

    private fun looksLikeProductName(s: String): Boolean = s.any { it.isLetterOrDigit() && it.code < 128 } && s.length <= 12

    private companion object {
        val PERSON_RELATIONS = listOf("任务", "承诺", "观点", "职位", "角色", "身份", "要求", "建议", "说")
        val GENERIC_WORDS = listOf("负责人", "员工", "人事", "客户", "领导", "同事", "团队", "公司", "用户", "对方", "大家", "本人", "接收方", "参与者", "说话人", "对话者", "发言人")
        val OBJECT_PERSON_RELATIONS = listOf("负责人", "接收方", "对接人", "汇报对象", "联系人")
        val COMMITMENT_RELATIONS = listOf("任务", "承诺", "待办", "交付")
        val DECISION_RELATIONS = listOf("决策", "决定", "结论")
        val RISK_RELATIONS = listOf("风险", "问题", "分歧", "顾虑")
        val PERSON_SUFFIXES = listOf("总", "哥", "姐", "工", "老师", "经理", "医生", "主任")
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
    /** Insights are derived from the graph, so they go with it. */
    suspend fun forgetEverything() {
        memoryDao.forgetEverything()
        insightDao.deleteAll()
    }
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
