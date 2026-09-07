package com.diting.domain.task

import com.diting.domain.model.Citation
import kotlinx.serialization.Serializable

/**
 * What a development agent (Claude Code, WorkBuddy, …) is allowed to do on the
 * user's behalf.
 *
 * The spec draws the line as "开 issue / 起草 RFC / 开草稿 PR；禁止：合并、推主干、
 * 部署", and that line is the entire reason this destination is acceptable at all.
 * It lives here as an enum rather than as a sentence in a prompt because a
 * prompt is a request and this is a constraint: [DevAgentPolicy.permits] is
 * checked before dispatch, so an agent that decides on its own to merge is
 * refused by the caller rather than trusted not to try.
 */
@Serializable
enum class DevAgentAction {
    /** Open an issue describing the decision. */
    OPEN_ISSUE,

    /** Draft an RFC document. */
    DRAFT_RFC,

    /** Open a **draft** pull request. Draft specifically: it cannot auto-merge. */
    OPEN_DRAFT_PR,

    // -- everything below is denied ------------------------------------------

    /** Merge a pull request. */
    MERGE,

    /** Push directly to the default branch. */
    PUSH_TO_MAIN,

    /** Trigger a deployment. */
    DEPLOY;

    /**
     * The three permitted actions share a property: each produces something a
     * human reviews before it has any effect. The three denied ones each change
     * the state of a running system.
     */
    val isReviewable: Boolean
        get() = this == OPEN_ISSUE || this == DRAFT_RFC || this == OPEN_DRAFT_PR
}

/**
 * The whitelist in force for a configured agent.
 *
 * Default-deny: an action absent from [allowed] is refused. Adding a case to
 * [DevAgentAction] therefore cannot silently grant a new capability.
 */
@Serializable
data class DevAgentPolicy(
    val allowed: Set<DevAgentAction> = DEFAULT_ALLOWED,
) {
    fun permits(action: DevAgentAction): Boolean = allowed.contains(action)

    /** The actions this policy refuses, for showing the user what it cannot do. */
    val denied: Set<DevAgentAction>
        get() = DevAgentAction.entries.toSet() - allowed

    companion object {
        val DEFAULT_ALLOWED: Set<DevAgentAction> = setOf(
            DevAgentAction.OPEN_ISSUE,
            DevAgentAction.DRAFT_RFC,
            DevAgentAction.OPEN_DRAFT_PR,
        )
    }
}

/** One configured development agent. */
@Serializable
data class DevAgent(
    val id: String,
    val name: String,
    /**
     * Where it runs. A tailnet MagicDNS name for a machine on the user's own
     * network; 谛听 never stores a public address or opens a public port.
     */
    val host: String,
    val port: Int,
    /** Repositories it may act on. Empty means it has been configured but scoped to nothing. */
    val repositories: List<String> = emptyList(),
    val policy: DevAgentPolicy = DevAgentPolicy(),
    val enabled: Boolean = true,
) {
    /** Usable only when it is enabled *and* scoped to at least one repository. */
    val isUsable: Boolean get() = enabled && repositories.isNotEmpty()
}

/**
 * What gets handed to the agent.
 *
 * Deliberately not the memory graph. The agent receives the intent, the audio
 * excerpts that justify it, and the decision nodes it touches — enough to write
 * a defensible issue, and nothing about the rest of the user's world. This
 * mirrors the constraint already applied to OpenClaw: "拿到的是意图 + 引用片段，
 * 不是全部记忆".
 */
@Serializable
data class DevAgentPayload(
    val intent: String,
    /** The audio this decision came from. */
    val refs: List<Citation> = emptyList(),
    /** Labels of the decision nodes involved — not the whole graph. */
    val decisions: List<String> = emptyList(),
    val repository: String? = null,
    val requestedAction: DevAgentAction = DevAgentAction.OPEN_ISSUE,
)

/**
 * What comes back, and lands at gate ② as a [Artifact].
 *
 * The diff body is deliberately absent: the artefact carries a link and a
 * rationale, because a code review belongs in the tool built for it, not in a
 * confirmation card on a phone.
 */
@Serializable
data class CodeChangeSummary(
    /** What changed. */
    val what: String,
    /** Why — traced back to the audio, same as every other generated surface. */
    val why: List<Citation> = emptyList(),
    /** Whether the agent believes this is ready to merge. Advisory only. */
    val mergeable: Boolean = false,
    /** URL of the issue or draft PR that was opened. */
    val externalRef: String? = null,
)

/** Raised when a dispatch asks for something the policy refuses. */
class DevAgentActionDenied(val agent: String, val action: DevAgentAction) :
    IllegalStateException("开发 Agent「$agent」不允许执行 $action")

object DevAgentDispatch {

    /**
     * Validates a dispatch before it leaves the app.
     *
     * Checks three things, all of which are the caller's responsibility to get
     * right and none of which the agent can be trusted to enforce on itself:
     * the agent is usable, the action is permitted, and the repository is one it
     * was actually scoped to.
     */
    fun validate(agent: DevAgent, payload: DevAgentPayload) {
        check(agent.enabled) { "开发 Agent「${agent.name}」已停用" }

        if (!agent.policy.permits(payload.requestedAction)) {
            throw DevAgentActionDenied(agent.name, payload.requestedAction)
        }

        payload.repository?.let { repo ->
            check(agent.repositories.contains(repo)) {
                "开发 Agent「${agent.name}」没有被授权操作仓库 $repo"
            }
        }
    }
}
