package com.diting.domain.memory

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * The three-tier storage lifetime from the design, and its one exception.
 *
 * ```
 *   原声音频   30 天    过期只删音频，转写与引用保留
 *   转写文本   1 年     未被引用的段落 90 天后压缩为摘要
 *   记忆图谱   永久     180 天不出现的冷节点归档：可搜索，不再触发主动提醒
 *
 *   唯一例外：被任务 / 洞察 / 报告引用过的片段，永不过期。
 * ```
 *
 * The exception is deliberately the only one. "Simple and explicable" was the
 * stated requirement, and every additional carve-out makes it impossible for a
 * user to predict what 谛听 still remembers.
 */
@Serializable
data class RetentionPolicy(
    val audioLifetime: Duration = 30.days,
    val transcriptLifetime: Duration = 365.days,
    /** Uncited transcript segments are compressed to a summary after this. */
    val transcriptCompressionAge: Duration = 90.days,
    /** Graph nodes not seen for this long are archived (searchable, but silent). */
    val graphColdAge: Duration = 180.days,
) {
    companion object {
        val Default = RetentionPolicy()

        /** For users who want less kept. Offered in 教小谛 › 记忆与遗忘. */
        val Minimal = RetentionPolicy(
            audioLifetime = 7.days,
            transcriptLifetime = 90.days,
            transcriptCompressionAge = 30.days,
            graphColdAge = 60.days,
        )
    }
}

/** What should happen to one piece of stored data. */
@Serializable
enum class RetentionAction {
    /** Nothing to do yet. */
    KEEP,

    /** Delete the audio file; keep the transcript and any citations. */
    DELETE_AUDIO,

    /** Replace the verbatim text with a summary. */
    COMPRESS_TRANSCRIPT,

    /** Delete the transcript text outright. */
    DELETE_TRANSCRIPT,

    /** Keep the node but stop it driving proactive cards. */
    ARCHIVE_NODE,

    /** Exempt: something cites this. */
    KEEP_FOREVER,
}

/**
 * Decides retention actions. Pure functions of (age, cited) so the "本月到期清单"
 * screen and the background sweeper cannot disagree with each other.
 */
class RetentionEvaluator(private val policy: RetentionPolicy = RetentionPolicy.Default) {

    /** @param isCited true when a task, insight or report cites this recording. */
    fun forAudio(ageMs: Long, isCited: Boolean): RetentionAction = when {
        isCited -> RetentionAction.KEEP_FOREVER
        ageMs >= policy.audioLifetime.inWholeMilliseconds -> RetentionAction.DELETE_AUDIO
        else -> RetentionAction.KEEP
    }

    fun forTranscript(ageMs: Long, isCited: Boolean): RetentionAction = when {
        isCited -> RetentionAction.KEEP_FOREVER
        ageMs >= policy.transcriptLifetime.inWholeMilliseconds -> RetentionAction.DELETE_TRANSCRIPT
        ageMs >= policy.transcriptCompressionAge.inWholeMilliseconds ->
            RetentionAction.COMPRESS_TRANSCRIPT

        else -> RetentionAction.KEEP
    }

    /**
     * Graph nodes are never deleted by age — they go cold.
     *
     * "永久，但有温度": an archived node still answers searches, it just stops
     * generating proactive reminders, which is the behaviour users actually
     * complain about when memory grows stale.
     */
    fun forGraphNode(msSinceLastSeen: Long, isCited: Boolean): RetentionAction = when {
        isCited -> RetentionAction.KEEP_FOREVER
        msSinceLastSeen >= policy.graphColdAge.inWholeMilliseconds -> RetentionAction.ARCHIVE_NODE
        else -> RetentionAction.KEEP
    }

    /** When [audioPath] will be deleted, or null if it never will. */
    fun audioExpiryEpochMs(recordedAtEpochMs: Long, isCited: Boolean): Long? =
        if (isCited) null else recordedAtEpochMs + policy.audioLifetime.inWholeMilliseconds
}

/** Node types in the memory graph — the "你所在世界" model. */
@Serializable
enum class MemoryNodeType {
    PERSON,
    COMMITMENT,
    DECISION,
    RISK,
    RECURRING_ASK,
    TOPIC,
    ARTIFACT,
}

/**
 * One node of the memory graph.
 *
 * [temperature] is the "温度" from the design: a decayed recency/frequency score
 * that decides whether the node still earns the right to interrupt the user.
 */
@Serializable
data class MemoryNode(
    val id: String,
    val type: MemoryNodeType,
    val label: String,
    val firstSeenEpochMs: Long,
    val lastSeenEpochMs: Long,
    val mentionCount: Int = 1,
    val archived: Boolean = false,
    /** True once a task, insight or report cites this node. Makes it permanent. */
    val isCited: Boolean = false,
    /**
     * Confirmation status. Principle 3: "「待确认」也是记忆的写入口" — an AI guess
     * only becomes fact when the user taps 确认.
     */
    val confidence: NodeConfidence = NodeConfidence.PROPOSED,
) {
    /**
     * 0..1. Decays with time since last mention, rewarded by repetition.
     * Archived nodes report 0 so they can never win a ranking.
     */
    fun temperature(nowEpochMs: Long, policy: RetentionPolicy = RetentionPolicy.Default): Float {
        if (archived) return 0f
        val age = (nowEpochMs - lastSeenEpochMs).coerceAtLeast(0).toDouble()
        val coldMs = policy.graphColdAge.inWholeMilliseconds.toDouble()
        val recency = (1.0 - age / coldMs).coerceIn(0.0, 1.0)
        // Repetition matters, but with diminishing returns: being mentioned twice
        // is meaningfully different from once, twenty times barely differs from ten.
        val repetition = (kotlin.math.ln(1.0 + mentionCount) / kotlin.math.ln(11.0))
            .coerceIn(0.0, 1.0)
        return (0.7 * recency + 0.3 * repetition).toFloat()
    }

    /** Only confirmed, unarchived nodes may generate a proactive card. */
    fun mayTriggerProactiveCard(): Boolean =
        !archived && confidence == NodeConfidence.CONFIRMED
}

/**
 * Whether the graph treats a node as fact.
 *
 * The design is explicit that AI uncertainty must not quietly become truth:
 * 点"确认"才入图谱，点"存疑"标为可证伪.
 */
@Serializable
enum class NodeConfidence {
    /** 小谛 proposed it; it is not yet part of the user's world model. */
    PROPOSED,

    /** The user tapped 确认. */
    CONFIRMED,

    /** The user tapped 存疑 — kept, but marked falsifiable and never asserted. */
    DISPUTED,
}
