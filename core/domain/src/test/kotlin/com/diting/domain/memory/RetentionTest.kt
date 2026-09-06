package com.diting.domain.memory

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.days

class RetentionTest {

    private val evaluator = RetentionEvaluator()

    private fun days(n: Int) = n.days.inWholeMilliseconds

    // -- audio: 30 days -------------------------------------------------------

    @Test
    fun `audio survives 29 days and is deleted at 30`() {
        assertEquals(RetentionAction.KEEP, evaluator.forAudio(days(29), isCited = false))
        assertEquals(RetentionAction.DELETE_AUDIO, evaluator.forAudio(days(30), isCited = false))
        assertEquals(RetentionAction.DELETE_AUDIO, evaluator.forAudio(days(400), isCited = false))
    }

    // -- transcript: compress at 90 days, delete at a year ---------------------

    @Test
    fun `transcript is compressed at 90 days and deleted at a year`() {
        assertEquals(RetentionAction.KEEP, evaluator.forTranscript(days(89), isCited = false))
        assertEquals(
            RetentionAction.COMPRESS_TRANSCRIPT,
            evaluator.forTranscript(days(90), isCited = false),
        )
        assertEquals(
            RetentionAction.COMPRESS_TRANSCRIPT,
            evaluator.forTranscript(days(364), isCited = false),
        )
        assertEquals(
            RetentionAction.DELETE_TRANSCRIPT,
            evaluator.forTranscript(days(365), isCited = false),
        )
    }

    // -- graph: cold, never deleted -------------------------------------------

    @Test
    fun `a graph node goes cold at 180 days but is never deleted`() {
        assertEquals(RetentionAction.KEEP, evaluator.forGraphNode(days(179), isCited = false))
        assertEquals(
            RetentionAction.ARCHIVE_NODE,
            evaluator.forGraphNode(days(180), isCited = false),
        )
        // Even after years the worst outcome is archival — the node stays searchable.
        assertEquals(
            RetentionAction.ARCHIVE_NODE,
            evaluator.forGraphNode(days(2000), isCited = false),
        )
    }

    // -- the one exception ----------------------------------------------------

    @Test
    fun `anything cited is exempt from every tier`() {
        // "唯一例外：被任务 / 洞察 / 报告引用过的片段，永不过期。"
        assertEquals(RetentionAction.KEEP_FOREVER, evaluator.forAudio(days(9999), isCited = true))
        assertEquals(
            RetentionAction.KEEP_FOREVER,
            evaluator.forTranscript(days(9999), isCited = true),
        )
        assertEquals(
            RetentionAction.KEEP_FOREVER,
            evaluator.forGraphNode(days(9999), isCited = true),
        )
    }

    @Test
    fun `cited audio has no expiry date to show the user`() {
        assertNull(evaluator.audioExpiryEpochMs(0, isCited = true))
        assertEquals(days(30), evaluator.audioExpiryEpochMs(0, isCited = false))
    }

    @Test
    fun `the minimal policy shortens every tier`() {
        val minimal = RetentionEvaluator(RetentionPolicy.Minimal)
        assertEquals(RetentionAction.DELETE_AUDIO, minimal.forAudio(days(7), isCited = false))
        assertEquals(
            RetentionAction.COMPRESS_TRANSCRIPT,
            minimal.forTranscript(days(30), isCited = false),
        )
    }
}

class MemoryNodeTest {

    private fun node(
        lastSeenDaysAgo: Int,
        mentions: Int = 1,
        archived: Boolean = false,
        confidence: NodeConfidence = NodeConfidence.CONFIRMED,
    ) = MemoryNode(
        id = "n",
        type = MemoryNodeType.COMMITMENT,
        label = "把数据导出方案发给李总",
        firstSeenEpochMs = 0,
        lastSeenEpochMs = NOW - lastSeenDaysAgo.days.inWholeMilliseconds,
        mentionCount = mentions,
        archived = archived,
        confidence = confidence,
    )

    @Test
    fun `temperature falls as a node goes unmentioned`() {
        val fresh = node(lastSeenDaysAgo = 0).temperature(NOW)
        val stale = node(lastSeenDaysAgo = 90).temperature(NOW)
        val cold = node(lastSeenDaysAgo = 179).temperature(NOW)

        assertTrue(fresh > stale, "fresh=$fresh stale=$stale")
        assertTrue(stale > cold, "stale=$stale cold=$cold")
    }

    @Test
    fun `repetition raises temperature`() {
        val once = node(lastSeenDaysAgo = 30, mentions = 1).temperature(NOW)
        val thrice = node(lastSeenDaysAgo = 30, mentions = 3).temperature(NOW)
        val many = node(lastSeenDaysAgo = 30, mentions = 30).temperature(NOW)

        assertTrue(thrice > once, "once=$once thrice=$thrice")
        assertTrue(many > thrice, "thrice=$thrice many=$many")
    }

    @Test
    fun `each additional mention is worth less than the one before`() {
        // Compared at equal deltas: the marginal gain of one more mention has to
        // shrink, or a node mentioned 50 times would drown out everything else.
        fun temp(mentions: Int) = node(lastSeenDaysAgo = 30, mentions = mentions).temperature(NOW)

        val firstStep = temp(2) - temp(1)
        val laterStep = temp(6) - temp(5)

        assertTrue(laterStep < firstStep, "first=$firstStep later=$laterStep")
        assertTrue(laterStep >= 0f)
    }

    @Test
    fun `an archived node can never win a ranking`() {
        assertEquals(0f, node(lastSeenDaysAgo = 0, mentions = 50, archived = true).temperature(NOW))
    }

    @Test
    fun `only confirmed unarchived nodes may interrupt the user`() {
        // Principle 3: an AI guess must not quietly become a reminder.
        assertTrue(node(0, confidence = NodeConfidence.CONFIRMED).mayTriggerProactiveCard())
        assertFalse(node(0, confidence = NodeConfidence.PROPOSED).mayTriggerProactiveCard())
        assertFalse(node(0, confidence = NodeConfidence.DISPUTED).mayTriggerProactiveCard())
        assertFalse(
            node(0, confidence = NodeConfidence.CONFIRMED, archived = true)
                .mayTriggerProactiveCard()
        )
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
