package com.diting.domain.task

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TaskGateTest {

    private fun task(state: TaskState = TaskState.AWAITING_GOAL_CONFIRMATION) = Task(
        id = "t1",
        goal = "输出三方案对比表",
        state = state,
        origin = TaskOrigin.SESSION_TODO,
    )

    private val artifact = Artifact(
        kind = ArtifactKind.DOCUMENT,
        title = "Q4 定价三方案对比",
        body = "…",
    )

    // -- the happy path -------------------------------------------------------

    @Test
    fun `the full loop runs goal to published`() {
        var t = task()
        t = TaskGate.confirmGoal(t, 1)
        assertEquals(TaskState.PLANNING, t.state)

        t = TaskGate.beginExecution(t, 2)
        assertEquals(TaskState.EXECUTING, t.state)

        t = TaskGate.deliverArtifact(t, artifact, 3)
        assertEquals(TaskState.AWAITING_RESULT_CONFIRMATION, t.state)
        assertEquals(artifact, t.artifact)

        t = TaskGate.adopt(t, Destination.NOTION, secondConfirmationGiven = true, nowEpochMs = 4)
        assertEquals(TaskState.PUBLISHING, t.state)

        t = TaskGate.markPublished(t, 5)
        assertEquals(TaskState.PUBLISHED, t.state)
        assertTrue(t.state.isTerminal)
    }

    // -- the gates ------------------------------------------------------------

    @Test
    fun `exactly two states are gates`() {
        val gates = TaskState.entries.filter { it.isGate }
        assertEquals(
            listOf(TaskState.AWAITING_GOAL_CONFIRMATION, TaskState.AWAITING_RESULT_CONFIRMATION),
            gates,
        )
    }

    @Test
    fun `exactly one state may touch the outside world`() {
        // This is the design's central promise. If a second state ever qualifies,
        // "主动，但可控" no longer holds.
        val effectful = TaskState.entries.filter { it.causesExternalSideEffects }
        assertEquals(listOf(TaskState.PUBLISHING), effectful)
    }

    @Test
    fun `an agent cannot run before the goal is confirmed`() {
        assertThrows<IllegalTaskTransition> { TaskGate.beginExecution(task(), 1) }
    }

    @Test
    fun `an artifact cannot be delivered before execution starts`() {
        val planning = task(TaskState.PLANNING)
        assertThrows<IllegalTaskTransition> { TaskGate.deliverArtifact(planning, artifact, 1) }
    }

    @Test
    fun `a task cannot be adopted straight from gate one`() {
        assertThrows<IllegalTaskTransition> {
            TaskGate.adopt(task(), Destination.NONE, nowEpochMs = 1)
        }
    }

    @Test
    fun `a published task is frozen`() {
        val published = task(TaskState.PUBLISHED)
        assertThrows<IllegalTaskTransition> { TaskGate.reject(published, 1) }
        assertThrows<IllegalTaskTransition> { TaskGate.requestRevision(published, "改改", 1) }
    }

    // -- revision -------------------------------------------------------------

    @Test
    fun `让小谛改改 goes back to planning, not back to the first gate`() {
        // Re-confirming the goal a user already confirmed is the kind of friction
        // the single-gate design exists to remove.
        val delivered = task(TaskState.AWAITING_RESULT_CONFIRMATION).copy(artifact = artifact)

        val revised = TaskGate.requestRevision(delivered, "口径改成按席位", 9)

        assertEquals(TaskState.PLANNING, revised.state)
        assertEquals("口径改成按席位", revised.revisionRequest)
    }

    @Test
    fun `delivering a new artifact clears the previous revision note`() {
        var t = task(TaskState.AWAITING_RESULT_CONFIRMATION).copy(artifact = artifact)
        t = TaskGate.requestRevision(t, "再短一点", 1)
        t = TaskGate.beginExecution(t, 2)
        t = TaskGate.deliverArtifact(t, artifact.copy(body = "shorter"), 3)

        assertEquals(null, t.revisionRequest)
    }

    // -- second confirmation --------------------------------------------------

    @Test
    fun `adopting to an external destination demands the second confirmation`() {
        val delivered = task(TaskState.AWAITING_RESULT_CONFIRMATION).copy(artifact = artifact)

        assertThrows<IllegalStateException> {
            TaskGate.adopt(delivered, Destination.EMAIL, secondConfirmationGiven = false, nowEpochMs = 1)
        }
    }

    @Test
    fun `adopting into 谛听 itself needs no second confirmation`() {
        val delivered = task(TaskState.AWAITING_RESULT_CONFIRMATION).copy(artifact = artifact)

        val adopted = TaskGate.adopt(delivered, Destination.NONE, nowEpochMs = 1)

        assertEquals(TaskState.PUBLISHING, adopted.state)
    }

    @Test
    fun `OpenClaw is not an external write because its result comes back to gate two`() {
        // 龙虾 runs on the user's own machine and must hand its output back to
        // 「待确认结果」 rather than acting outward, so adopting to it is not the
        // irreversible step that mail or a calendar write is.
        assertFalse(Destination.OPENCLAW.isExternalWrite)
        assertTrue(Destination.EMAIL.isExternalWrite)
        assertTrue(Destination.CALENDAR.isExternalWrite)
        assertTrue(Destination.NOTION.isExternalWrite)
        assertTrue(Destination.LARK.isExternalWrite)
        assertFalse(Destination.NONE.isExternalWrite)
    }

    // -- destination routing --------------------------------------------------

    @Test
    fun `the default routing table matches the design`() {
        assertEquals(Destination.CALENDAR, ArtifactKind.TODO.defaultDestination)
        assertEquals(Destination.NOTION, ArtifactKind.DOCUMENT.defaultDestination)
        assertEquals(Destination.EMAIL, ArtifactKind.EMAIL.defaultDestination)
        assertEquals(Destination.LARK, ArtifactKind.MESSAGE.defaultDestination)
        assertEquals(Destination.OPENCLAW, ArtifactKind.MULTI_STEP.defaultDestination)
    }

    @Test
    fun `an explicit destination overrides the artifact default`() {
        val t = task(TaskState.AWAITING_RESULT_CONFIRMATION)
            .copy(artifact = artifact, destination = Destination.LARK)

        assertEquals(Destination.LARK, t.effectiveDestination)
    }

    @Test
    fun `a task with no artifact routes nowhere`() {
        assertEquals(Destination.NONE, task().effectiveDestination)
        assertFalse(task().adoptionNeedsSecondConfirmation)
    }

    // -- failure --------------------------------------------------------------

    @Test
    fun `a failed task can be retried but not adopted`() {
        val failed = TaskGate.fail(task(TaskState.EXECUTING), "skill timed out", 1)

        assertEquals(TaskState.FAILED, failed.state)
        assertEquals("skill timed out", failed.failureReason)
        assertEquals(TaskState.PLANNING, TaskGate.confirmGoal(failed, 2).state)
        assertThrows<IllegalTaskTransition> { TaskGate.adopt(failed, nowEpochMs = 2) }
    }
}
