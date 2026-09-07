package com.diting.domain.task

import com.diting.domain.model.Citation
import com.diting.domain.model.Intent
import com.diting.domain.scene.ResponseAction
import com.diting.domain.scene.Scene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DevAgentPolicyTest {

    private val agent = DevAgent(
        id = "cc",
        name = "Claude Code",
        host = "workstation.tailnet.ts.net",
        port = 8765,
        repositories = listOf("lileilei2023/diting-android"),
    )

    private fun payload(
        action: DevAgentAction,
        repo: String? = "lileilei2023/diting-android",
    ) = DevAgentPayload(
        intent = "把 MR20 的 Wi-Fi 传输尾部处理写成 RFC",
        refs = listOf(Citation("s1", 0, 5_000)),
        repository = repo,
        requestedAction = action,
    )

    // -- the whitelist --------------------------------------------------------

    @Test
    fun `the three reviewable actions are permitted by default`() {
        val policy = DevAgentPolicy()
        assertTrue(policy.permits(DevAgentAction.OPEN_ISSUE))
        assertTrue(policy.permits(DevAgentAction.DRAFT_RFC))
        assertTrue(policy.permits(DevAgentAction.OPEN_DRAFT_PR))
    }

    @Test
    fun `merge, push to main and deploy are denied by default`() {
        // This is the property that makes the destination acceptable at all.
        val policy = DevAgentPolicy()
        assertFalse(policy.permits(DevAgentAction.MERGE))
        assertFalse(policy.permits(DevAgentAction.PUSH_TO_MAIN))
        assertFalse(policy.permits(DevAgentAction.DEPLOY))
    }

    @Test
    fun `every permitted action produces something a human reviews first`() {
        // The line between allowed and denied is not arbitrary: allowed actions
        // create artefacts pending review, denied ones change a running system.
        // A new enum case that is reviewable can join the default set; one that
        // is not, must not.
        DevAgentPolicy.DEFAULT_ALLOWED.forEach { action ->
            assertTrue(action.isReviewable, "$action is allowed but not reviewable")
        }
        (DevAgentAction.entries.toSet() - DevAgentPolicy.DEFAULT_ALLOWED).forEach { action ->
            assertFalse(action.isReviewable, "$action is denied but looks reviewable")
        }
    }

    @Test
    fun `the policy is default-deny, so a new action grants nothing`() {
        val narrow = DevAgentPolicy(allowed = setOf(DevAgentAction.OPEN_ISSUE))

        assertTrue(narrow.permits(DevAgentAction.OPEN_ISSUE))
        assertFalse(narrow.permits(DevAgentAction.DRAFT_RFC))
        assertEquals(5, narrow.denied.size)
    }

    // -- dispatch validation --------------------------------------------------

    @Test
    fun `dispatching a permitted action against a scoped repo passes`() {
        DevAgentDispatch.validate(agent, payload(DevAgentAction.OPEN_ISSUE))
    }

    @Test
    fun `dispatching a denied action is refused before it leaves the app`() {
        val error = assertThrows<DevAgentActionDenied> {
            DevAgentDispatch.validate(agent, payload(DevAgentAction.MERGE))
        }
        assertEquals(DevAgentAction.MERGE, error.action)
        assertEquals("Claude Code", error.agent)
    }

    @Test
    fun `an agent cannot act on a repository it was not scoped to`() {
        assertThrows<IllegalStateException> {
            DevAgentDispatch.validate(
                agent,
                payload(DevAgentAction.OPEN_ISSUE, repo = "someone-else/private"),
            )
        }
    }

    @Test
    fun `a disabled agent is refused`() {
        assertThrows<IllegalStateException> {
            DevAgentDispatch.validate(
                agent.copy(enabled = false),
                payload(DevAgentAction.OPEN_ISSUE),
            )
        }
    }

    @Test
    fun `an agent scoped to no repository is not usable`() {
        assertFalse(agent.copy(repositories = emptyList()).isUsable)
        assertTrue(agent.isUsable)
    }

    // -- payload contract -----------------------------------------------------

    @Test
    fun `the payload carries intent and refs, not the memory graph`() {
        // "拿到的是意图 + 引用片段，不是全部记忆" — the type is the enforcement.
        val fields = DevAgentPayload::class.java.declaredFields.map { it.name }.toSet()

        assertTrue(fields.contains("intent"))
        assertTrue(fields.contains("refs"))
        assertTrue(fields.contains("decisions"))
        assertFalse(fields.any { it.contains("graph", ignoreCase = true) })
        assertFalse(fields.any { it.contains("memory", ignoreCase = true) })
    }

    @Test
    fun `the result carries a link and a rationale, not a diff`() {
        val summary = CodeChangeSummary(
            what = "把尾部 5 字节的剥离改成跨包扫描",
            why = listOf(Citation("s1", 12_000, 18_000)),
            mergeable = false,
            externalRef = "https://github.com/lileilei2023/diting-android/pull/7",
        )

        assertTrue(summary.why.isNotEmpty(), "a change must trace back to the audio")
        val fields = CodeChangeSummary::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("diff", ignoreCase = true) })
        assertFalse(fields.any { it.contains("patch", ignoreCase = true) })
    }
}

class DestinationRoutingTest {

    @Test
    fun `the routing table covers every artifact kind`() {
        assertEquals(Destination.CALENDAR, ArtifactKind.TODO.defaultDestination)
        assertEquals(Destination.NOTION, ArtifactKind.DOCUMENT.defaultDestination)
        assertEquals(Destination.EMAIL, ArtifactKind.EMAIL.defaultDestination)
        assertEquals(Destination.LARK, ArtifactKind.MESSAGE.defaultDestination)
        assertEquals(Destination.OPENCLAW, ArtifactKind.MULTI_STEP.defaultDestination)
        assertEquals(Destination.DEV_AGENT, ArtifactKind.TECH_DECISION.defaultDestination)
    }

    @Test
    fun `a technical decision falls back to Notion when no agent is connected`() {
        val router = DestinationRouter.Unconfigured

        assertEquals(Destination.NOTION, router.route(ArtifactKind.TECH_DECISION))
        assertTrue(router.didFallBack(ArtifactKind.TECH_DECISION))
    }

    @Test
    fun `a technical decision goes to the agent once one is connected`() {
        val router = DestinationRouter(available = setOf(Destination.DEV_AGENT))

        assertEquals(Destination.DEV_AGENT, router.route(ArtifactKind.TECH_DECISION))
        assertFalse(router.didFallBack(ArtifactKind.TECH_DECISION))
    }

    @Test
    fun `nothing else falls back`() {
        // Silently rerouting a meeting invite into Notion because no calendar is
        // connected would be worse than telling the user the calendar is missing.
        val router = DestinationRouter.Unconfigured

        assertEquals(Destination.CALENDAR, router.route(ArtifactKind.TODO))
        assertEquals(Destination.EMAIL, router.route(ArtifactKind.EMAIL))
        assertEquals(Destination.LARK, router.route(ArtifactKind.MESSAGE))
        assertEquals(Destination.OPENCLAW, router.route(ArtifactKind.MULTI_STEP))
        assertFalse(router.didFallBack(ArtifactKind.TODO))
    }

    // -- the gate still holds -------------------------------------------------

    @Test
    fun `adopting to a dev agent needs the second confirmation`() {
        // The agent runs on the user's own machine, but an issue or PR is visible
        // to colleagues the instant it exists. That is an external write.
        assertTrue(Destination.DEV_AGENT.isExternalWrite)

        val task = Task(
            id = "t",
            goal = "把定价接口的变更写成 RFC",
            state = TaskState.AWAITING_RESULT_CONFIRMATION,
            origin = TaskOrigin.SESSION_TODO,
            artifact = Artifact(ArtifactKind.TECH_DECISION, "RFC", "…"),
        )

        assertThrows<IllegalStateException> {
            TaskGate.adopt(task, Destination.DEV_AGENT, secondConfirmationGiven = false, nowEpochMs = 1)
        }
        assertEquals(
            TaskState.PUBLISHING,
            TaskGate.adopt(task, Destination.DEV_AGENT, true, nowEpochMs = 1).state,
        )
    }

    @Test
    fun `OpenClaw still does not count as an external write`() {
        // It hands its result back to gate ② instead of acting outward, which is
        // exactly what distinguishes it from the dev agent.
        assertFalse(Destination.OPENCLAW.isExternalWrite)
    }

    @Test
    fun `still exactly one state may touch the outside world`() {
        assertEquals(
            listOf(TaskState.PUBLISHING),
            TaskState.entries.filter { it.causesExternalSideEffects },
        )
    }
}

class TechSceneTest {

    private val tech = Scene.builtIn("tech")!!

    @Test
    fun `the tech scene routes a technical decision to a dev agent`() {
        assertEquals(
            ResponseAction.DISPATCH_TO_DEV_AGENT,
            tech.actionFor(Intent.TECH_DECISION),
        )
    }

    @Test
    fun `an ordinary decision in a tech meeting still goes to the minutes`() {
        // Only *technical* decisions route to an agent. "我们下周三发布" is a
        // decision but not something to open an issue about.
        assertEquals(ResponseAction.RECORD_IN_MINUTES, tech.actionFor(Intent.DECISION))
    }

    @Test
    fun `no other built-in scene can dispatch to a dev agent`() {
        // The spec says the rule is "默认仅在 tech 场景开启", and that default is
        // what stops a customer call proposing a pull request.
        val offenders = Scene.BuiltIns
            .filter { it.id != "tech" }
            .filter { scene ->
                scene.rules.any { it.action == ResponseAction.DISPATCH_TO_DEV_AGENT }
            }
            .map { it.name }

        assertTrue(offenders.isEmpty(), "these scenes can dispatch to an agent: $offenders")
    }

    @Test
    fun `disabling the rule stops the dispatch`() {
        val disabled = tech.copy(
            rules = tech.rules.map {
                if (it.intent == Intent.TECH_DECISION) it.copy(enabled = false) else it
            }
        )

        assertNull(disabled.actionFor(Intent.TECH_DECISION))
    }

    @Test
    fun `the tech scene separates speakers`() {
        // Its cue list assumes two or more engineers; attributing a decision to
        // the wrong one is the failure mode that matters here.
        assertTrue(tech.speakerSeparation)
    }
}
