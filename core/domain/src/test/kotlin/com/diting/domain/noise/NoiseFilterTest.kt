package com.diting.domain.noise

import com.diting.domain.model.Intent
import com.diting.domain.scene.GlobalIgnoreRules
import com.diting.domain.scene.Scene
import com.diting.domain.scene.SceneDetector
import com.diting.domain.scene.TimeWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoiseFilterTest {

    private val customerCall = Scene.builtIn("customer_call")!!

    private fun features(
        text: String = "小张周五前出三种方案的对比表",
        person: Boolean = true,
        verb: Boolean = true,
        time: Boolean = true,
        prior: Int = 0,
        intent: Intent? = null,
    ) = SentenceFeatures(text, person, verb, time, prior, intent)

    // -- gate 2: scene rules --------------------------------------------------

    @Test
    fun `the stop phrase drops the sentence`() {
        val verdict = NoiseFilter().evaluate(
            features(text = "小谛别记，这段是私事"),
            customerCall,
            minutesSinceMidnight = 600,
        )

        assertEquals(NoiseGate.SCENE_RULES, verdict.gate)
        assertFalse(verdict.kept)
    }

    @Test
    fun `private hours drop everything, including across midnight`() {
        val filter = NoiseFilter(
            GlobalIgnoreRules(privateHours = listOf(TimeWindow(22 * 60, 7 * 60)))
        )

        // 23:30 and 06:00 are both inside a 22:00-07:00 window.
        assertEquals(
            NoiseGate.SCENE_RULES,
            filter.evaluate(features(), customerCall, 23 * 60 + 30).gate,
        )
        assertEquals(NoiseGate.SCENE_RULES, filter.evaluate(features(), customerCall, 6 * 60).gate)
        // 12:00 is not.
        assertTrue(filter.evaluate(features(), customerCall, 12 * 60).kept)
    }

    @Test
    fun `small talk is dropped in a customer call`() {
        val verdict = NoiseFilter().evaluate(
            features(text = "今天天气不错", intent = Intent.SMALL_TALK),
            customerCall,
            600,
        )

        assertEquals(NoiseGate.SCENE_RULES, verdict.gate)
        assertTrue(verdict.reason!!.contains("客户通话"))
    }

    @Test
    fun `the same sentence survives in a scene with no ignore rule for it`() {
        // This is the whole reason response rules hang off scenes rather than a
        // global switch.
        val lecture = Scene.builtIn("solo_thinking")!!

        val verdict = NoiseFilter().evaluate(
            features(text = "今天天气不错", intent = Intent.SMALL_TALK),
            lecture,
            600,
        )

        assertTrue(verdict.kept)
    }

    // -- gate 3: 三无句 -------------------------------------------------------

    @Test
    fun `a sentence with no person, verb or time heard once is dropped`() {
        val verdict = NoiseFilter().evaluate(
            features(text = "嗯，那个", person = false, verb = false, time = false, prior = 0),
            customerCall,
            600,
        )

        assertEquals(NoiseGate.THREE_WITHOUT, verdict.gate)
    }

    @Test
    fun `repetition rescues a 三无句`() {
        // Something said twice is about something, even if we cannot parse what.
        val verdict = NoiseFilter().evaluate(
            features(text = "那个方案", person = false, verb = false, time = false, prior = 1),
            customerCall,
            600,
        )

        assertTrue(verdict.kept)
    }

    @Test
    fun `any one of person, verb or time is enough to keep it`() {
        val filter = NoiseFilter()
        assertTrue(filter.evaluate(features(person = true, verb = false, time = false), customerCall, 600).kept)
        assertTrue(filter.evaluate(features(person = false, verb = true, time = false), customerCall, 600).kept)
        assertTrue(filter.evaluate(features(person = false, verb = false, time = true), customerCall, 600).kept)
    }

    // -- gate 4: the user's own judgement -------------------------------------

    @Test
    fun `a phrase the user marked unimportant is dropped`() {
        val filter = NoiseFilter(userIgnored = setOf("续杯"))

        val verdict = filter.evaluate(features(text = "帮我续杯咖啡"), customerCall, 600)

        assertEquals(NoiseGate.USER_FEEDBACK, verdict.gate)
    }

    @Test
    fun `an explicit human judgement outranks the structural heuristic`() {
        // The reason reported to the user must be the one they will recognise.
        val filter = NoiseFilter(userIgnored = setOf("续杯"))

        val verdict = filter.evaluate(
            features(text = "续杯", person = false, verb = false, time = false),
            customerCall,
            600,
        )

        assertEquals(NoiseGate.USER_FEEDBACK, verdict.gate)
    }

    // -- stats ----------------------------------------------------------------

    @Test
    fun `stats tally per gate`() {
        var stats = NoiseStats()
        stats = stats.plus(NoiseVerdict(NoiseGate.SCENE_RULES))
        stats = stats.plus(NoiseVerdict(NoiseGate.SCENE_RULES))
        stats = stats.plus(NoiseVerdict(NoiseGate.THREE_WITHOUT))
        stats = stats.plus(NoiseVerdict.Keep)

        assertEquals(2, stats.sceneRuleDrops)
        assertEquals(1, stats.threeWithoutDrops)
        assertEquals(0, stats.userFeedbackDrops)
        assertEquals(3, stats.totalDrops)
    }
}

class SceneTest {

    @Test
    fun `the customer call scene turns commitments into todos and imperatives into tasks`() {
        val scene = Scene.builtIn("customer_call")!!

        assertEquals(
            com.diting.domain.scene.ResponseAction.CREATE_TODO,
            scene.actionFor(Intent.COMMITMENT),
        )
        assertEquals(
            com.diting.domain.scene.ResponseAction.CREATE_TASK,
            scene.actionFor(Intent.IMPERATIVE),
        )
        assertEquals(
            com.diting.domain.scene.ResponseAction.IGNORE,
            scene.actionFor(Intent.SMALL_TALK),
        )
    }

    @Test
    fun `a disabled rule stops applying`() {
        val scene = Scene.builtIn("customer_call")!!.let { s ->
            s.copy(rules = s.rules.map { if (it.intent == Intent.SMALL_TALK) it.copy(enabled = false) else it })
        }

        assertNull(scene.actionFor(Intent.SMALL_TALK))
    }

    @Test
    fun `solo thinking skips speaker separation`() {
        // One speaker; diarisation is wasted battery.
        assertFalse(Scene.builtIn("solo_thinking")!!.speakerSeparation)
        assertTrue(Scene.builtIn("customer_call")!!.speakerSeparation)
    }

    @Test
    fun `scene detection picks the best-scoring cue match`() {
        val detector = SceneDetector()

        assertEquals("customer_call", detector.detect("李总问报价和续费的合同怎么算")?.id)
        assertEquals("internal_meeting", detector.detect("周会先对齐一下排期")?.id)
        assertNull(detector.detect("随便说两句"))
    }

    @Test
    fun `a time window that does not wrap behaves normally`() {
        val window = TimeWindow(9 * 60, 18 * 60)

        assertTrue(window.contains(9 * 60))
        assertTrue(window.contains(17 * 60))
        assertFalse(window.contains(18 * 60))
        assertFalse(window.contains(8 * 60))
    }
}
