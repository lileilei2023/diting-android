package com.diting.domain.task

import com.diting.domain.model.Citation
import com.diting.domain.model.Cited
import kotlinx.serialization.Serializable

/**
 * The task state machine — principle 2 of the design: **one amber gate, not two
 * kinds of "待确认"**.
 *
 * ```
 *   ① 待确认目标 --confirm--> 规划 --> 执行 --> ② 待确认结果 --adopt--> ③ 发布
 *        |                                          |
 *        +--------------- reject -------------------+
 *                                                   |
 *                                            reviseRequested
 *                                                   v
 *                                                 规划
 * ```
 *
 * The rule that matters: **no external side effect happens before [TaskState.PUBLISHING]**.
 * Planning and execution are free to read the graph and call skills, but nothing
 * leaves the app — no mail sent, no calendar written, no message posted — until
 * the user passes gate ③, and that gate alone raises a second confirmation.
 */
@Serializable
enum class TaskState {
    /** ① The gate. 小谛 proposed a goal; nothing has run. */
    AWAITING_GOAL_CONFIRMATION,

    /** Agent is choosing skills and drafting a plan. */
    PLANNING,

    /** Agent is producing the artefact. Still no external side effects. */
    EXECUTING,

    /** ② The gate. The artefact is back with the user. */
    AWAITING_RESULT_CONFIRMATION,

    /** ③ Adopted; the destination write is in flight. This is where side effects happen. */
    PUBLISHING,

    /** Delivered to its destination. */
    PUBLISHED,

    /** The user declined the goal or the result. */
    REJECTED,

    /** Execution failed; the user may retry. */
    FAILED;

    /** True while a human decision is required. Drives the amber styling. */
    val isGate: Boolean
        get() = this == AWAITING_GOAL_CONFIRMATION || this == AWAITING_RESULT_CONFIRMATION

    /** True once the task can no longer change without the user starting over. */
    val isTerminal: Boolean
        get() = this == PUBLISHED || this == REJECTED

    /**
     * Whether reaching this state may touch the outside world.
     *
     * Exactly one state may. If this ever returns true for more than
     * [PUBLISHING], the design's central promise is broken.
     */
    val causesExternalSideEffects: Boolean
        get() = this == PUBLISHING
}

/** Where an adopted artefact goes. Principle: "Todo 发布 = 目的地路由". */
@Serializable
enum class Destination {
    /** 待办 → 系统日历 + 提醒 */
    CALENDAR,

    /** 文档 → Notion */
    NOTION,

    /** 邮件 → 邮箱（发送前必须预览） */
    EMAIL,

    /** 消息 → 飞书（只 @ 任务里出现的人） */
    LARK,

    /** 多步执行 → 龙虾 OpenClaw，结果必须回到「待确认结果」 */
    OPENCLAW,

    /**
     * 技术决策 → 开发 Agent (Claude Code / WorkBuddy)。
     *
     * Bounded by [DevAgentPolicy]: it may open an issue, draft an RFC or open a
     * draft PR, and may not merge, push to the default branch, or deploy.
     */
    DEV_AGENT,

    /** Stays in 谛听 only. */
    NONE;

    /**
     * Whether adopting to this destination writes to a third-party service.
     *
     * [NONE] and [OPENCLAW] do not: OpenClaw runs on the user's own machine and
     * hands its result back to gate ② rather than acting outward.
     *
     * [DEV_AGENT] does, and this is the one that is easy to get wrong. It also
     * runs on the user's own machine — but what it produces is an issue or a PR
     * that appears in a shared repository the moment it is created. Colleagues
     * see it. That is an external write, whatever the agent's IP address is, so
     * adopting to it raises the second confirmation like any other.
     */
    val isExternalWrite: Boolean
        get() = this == CALENDAR || this == NOTION || this == EMAIL ||
            this == LARK || this == DEV_AGENT
}

/** The kind of thing a task produces, which picks the default [Destination]. */
@Serializable
enum class ArtifactKind {
    TODO,
    DOCUMENT,
    EMAIL,
    MESSAGE,
    MULTI_STEP,

    /** 技术决策 — routes to a development agent when one is configured. */
    TECH_DECISION;

    /**
     * The default routing table from the design.
     *
     * ```
     *   待办     → 日历 + 提醒
     *   文档     → Notion
     *   邮件     → 邮箱
     *   消息     → 飞书
     *   多步执行 → 龙虾 OpenClaw
     *   技术决策 → 开发 Agent
     * ```
     *
     * This is the *ideal* destination. [DestinationRouter] is what resolves it
     * against what the user has actually connected — routing a technical
     * decision to a dev agent that does not exist would leave the task stuck at
     * gate ② with nowhere to go.
     */
    val defaultDestination: Destination
        get() = when (this) {
            TODO -> Destination.CALENDAR
            DOCUMENT -> Destination.NOTION
            EMAIL -> Destination.EMAIL
            MESSAGE -> Destination.LARK
            MULTI_STEP -> Destination.OPENCLAW
            TECH_DECISION -> Destination.DEV_AGENT
        }
}

/**
 * Resolves the routing table against what is actually connected.
 *
 * The spec states the one fallback it wants — 技术决策 「未启用则回落 Notion」 — and
 * the reasoning generalises: a technical decision that cannot reach an agent is
 * still a decision worth writing down, and a document is where a decision goes.
 * Falling back beats leaving the task with no destination.
 *
 * Nothing else falls back. A to-do with no calendar connected stays a to-do
 * bound for the calendar and simply cannot be adopted yet — silently rerouting
 * someone's meeting invite into Notion would be worse than telling them the
 * calendar is not set up.
 */
class DestinationRouter(
    /** Destinations the user has actually connected. */
    private val available: Set<Destination> = emptySet(),
) {

    /** Where an artefact of this kind should go, given what is connected. */
    fun route(kind: ArtifactKind): Destination {
        val ideal = kind.defaultDestination
        if (available.contains(ideal)) return ideal

        return when (ideal) {
            // The one documented fallback.
            Destination.DEV_AGENT -> Destination.NOTION
            else -> ideal
        }
    }

    /** True when [route] had to substitute, so the UI can say why. */
    fun didFallBack(kind: ArtifactKind): Boolean = route(kind) != kind.defaultDestination

    companion object {
        /** Nothing connected — every kind resolves to its ideal but unusable target. */
        val Unconfigured = DestinationRouter()
    }
}

/** Where a task came from. Used to de-duplicate repeated intents. */
@Serializable
enum class TaskOrigin {
    /** 会话待办「交给小谛办」 */
    SESSION_TODO,

    /** 洞察「反复被提」 */
    INSIGHT,

    /** 对话里的祈使句（"帮我…"） */
    CHAT_IMPERATIVE,

    /** 碎念升级 */
    NOTE_PROMOTION,

    /** 周复盘建议 */
    WEEKLY_REVIEW,
}

/** The artefact produced at gate ②. */
@Serializable
data class Artifact(
    val kind: ArtifactKind,
    val title: String,
    val body: String,
    override val citations: List<Citation> = emptyList(),
    /** Skills the agent used, surfaced in the task detail screen. */
    val skillsUsed: List<String> = emptyList(),
) : Cited

@Serializable
data class Task(
    val id: String,
    val goal: String,
    val state: TaskState = TaskState.AWAITING_GOAL_CONFIRMATION,
    val origin: TaskOrigin,
    override val citations: List<Citation> = emptyList(),
    val artifact: Artifact? = null,
    val destination: Destination? = null,
    /** Free-text note from "让小谛改改". */
    val revisionRequest: String? = null,
    val failureReason: String? = null,
    val createdAtEpochMs: Long = 0,
    val updatedAtEpochMs: Long = 0,
) : Cited {

    /** The destination that will be used if the user does not choose one. */
    val effectiveDestination: Destination
        get() = destination ?: artifact?.kind?.defaultDestination ?: Destination.NONE

    /**
     * True when adopting will write to a third-party service, which is the only
     * case that warrants the second confirmation dialog.
     */
    val adoptionNeedsSecondConfirmation: Boolean
        get() = effectiveDestination.isExternalWrite
}

/** Rejected because the requested move is not legal from the current state. */
class IllegalTaskTransition(val from: TaskState, val to: TaskState) :
    IllegalStateException("cannot move a task from $from to $to")

/**
 * The only place task state changes.
 *
 * Keeping every transition here — rather than letting screens flip `state`
 * directly — is what makes "no side effect before gate ③" checkable instead of
 * merely intended.
 */
object TaskGate {

    /** Legal moves. Anything not listed throws [IllegalTaskTransition]. */
    private val allowed: Map<TaskState, Set<TaskState>> = mapOf(
        TaskState.AWAITING_GOAL_CONFIRMATION to setOf(TaskState.PLANNING, TaskState.REJECTED),
        TaskState.PLANNING to setOf(TaskState.EXECUTING, TaskState.FAILED, TaskState.REJECTED),
        TaskState.EXECUTING to setOf(
            TaskState.AWAITING_RESULT_CONFIRMATION,
            TaskState.FAILED,
            TaskState.REJECTED,
        ),
        TaskState.AWAITING_RESULT_CONFIRMATION to setOf(
            TaskState.PUBLISHING,
            // "让小谛改改" goes back to planning, not back to gate ①.
            TaskState.PLANNING,
            TaskState.REJECTED,
        ),
        TaskState.PUBLISHING to setOf(TaskState.PUBLISHED, TaskState.FAILED),
        TaskState.FAILED to setOf(TaskState.PLANNING, TaskState.REJECTED),
        TaskState.PUBLISHED to emptySet(),
        TaskState.REJECTED to emptySet(),
    )

    fun canMove(from: TaskState, to: TaskState): Boolean =
        allowed[from].orEmpty().contains(to)

    /** ① The user confirms the goal. This is what allows the agent to run at all. */
    fun confirmGoal(task: Task, nowEpochMs: Long): Task =
        move(task, TaskState.PLANNING, nowEpochMs)

    fun beginExecution(task: Task, nowEpochMs: Long): Task =
        move(task, TaskState.EXECUTING, nowEpochMs)

    /** The agent finished; the artefact lands at gate ②. */
    fun deliverArtifact(task: Task, artifact: Artifact, nowEpochMs: Long): Task =
        move(task, TaskState.AWAITING_RESULT_CONFIRMATION, nowEpochMs)
            .copy(artifact = artifact, revisionRequest = null)

    /** "让小谛改改" — back to planning, carrying the user's note. */
    fun requestRevision(task: Task, note: String, nowEpochMs: Long): Task =
        move(task, TaskState.PLANNING, nowEpochMs).copy(revisionRequest = note)

    /**
     * ③ The user adopts. This is the *only* transition after which the app may
     * touch a third-party service.
     *
     * @param secondConfirmationGiven must be true when
     *   [Task.adoptionNeedsSecondConfirmation] is — callers cannot skip the
     *   dialog by calling this directly.
     */
    fun adopt(
        task: Task,
        destination: Destination? = null,
        secondConfirmationGiven: Boolean = false,
        nowEpochMs: Long,
    ): Task {
        val routed = task.copy(destination = destination ?: task.effectiveDestination)
        check(!routed.adoptionNeedsSecondConfirmation || secondConfirmationGiven) {
            "adopting to ${routed.effectiveDestination} writes to a third-party service " +
                "and requires the second confirmation"
        }
        return move(routed, TaskState.PUBLISHING, nowEpochMs)
    }

    fun markPublished(task: Task, nowEpochMs: Long): Task =
        move(task, TaskState.PUBLISHED, nowEpochMs)

    fun fail(task: Task, reason: String, nowEpochMs: Long): Task =
        move(task, TaskState.FAILED, nowEpochMs).copy(failureReason = reason)

    fun reject(task: Task, nowEpochMs: Long): Task =
        move(task, TaskState.REJECTED, nowEpochMs)

    private fun move(task: Task, to: TaskState, nowEpochMs: Long): Task {
        if (!canMove(task.state, to)) throw IllegalTaskTransition(task.state, to)
        return task.copy(state = to, updatedAtEpochMs = nowEpochMs)
    }
}
