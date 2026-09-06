package com.diting.domain.noise

import com.diting.domain.model.Intent
import com.diting.domain.scene.GlobalIgnoreRules
import com.diting.domain.scene.ResponseAction
import com.diting.domain.scene.Scene
import kotlinx.serialization.Serializable

/**
 * The four noise gates, in the order the design specifies:
 *
 * ```
 *   一  设备端 VAD          静音、环境音不进手机
 *   二  场景忽略规则         闲聊 / 口令「小谛别记」/ 私人时段
 *   三  "三无句"不入图谱     无人名、无动词、无时间的单次出现
 *   四  你的"不重要"        在观察卡上点忽略，回训阈值
 * ```
 *
 * Order matters: each gate is cheaper than the one after it, and gate 1 runs on
 * the device so the audio never reaches the phone at all. The counters this
 * produces are what the 记忆与遗忘 screen reports each week.
 */
@Serializable
enum class NoiseGate {
    /** On-device voice activity detection. */
    DEVICE_VAD,

    /** Scene ignore rules, the stop phrase, and private hours. */
    SCENE_RULES,

    /** Nothing to hang a memory on. */
    THREE_WITHOUT,

    /** The user said it did not matter. */
    USER_FEEDBACK,
}

/** Why a sentence was dropped, or null when it was kept. */
@Serializable
data class NoiseVerdict(
    val gate: NoiseGate?,
    val reason: String? = null,
) {
    val kept: Boolean get() = gate == null

    companion object {
        val Keep = NoiseVerdict(null)
    }
}

/**
 * Everything gate 3 needs to know about a sentence.
 *
 * These come from the NLP pass; the gate itself stays a pure predicate so it can
 * be tested and explained to a user.
 */
@Serializable
data class SentenceFeatures(
    val text: String,
    val hasPersonName: Boolean,
    val hasVerb: Boolean,
    val hasTimeReference: Boolean,
    /** How often this phrasing has been heard before. */
    val priorOccurrences: Int = 0,
    val intent: Intent? = null,
)

/**
 * Applies gates 2-4. Gate 1 lives in the device firmware, and is represented here
 * only so its statistics can be reported alongside the others.
 */
class NoiseFilter(
    private val globalRules: GlobalIgnoreRules = GlobalIgnoreRules(),
    /** Phrases the user marked "这不重要" — gate 4's learned set. */
    private val userIgnored: Set<String> = emptySet(),
) {

    /**
     * @param minutesSinceMidnight local time of the utterance, for private hours.
     * @return [NoiseVerdict.Keep] when the sentence should enter the graph.
     */
    fun evaluate(
        features: SentenceFeatures,
        scene: Scene?,
        minutesSinceMidnight: Int,
    ): NoiseVerdict {
        // Gate 2 — scene rules, stop phrase and private hours.
        if (features.text.contains(globalRules.stopPhrase)) {
            return NoiseVerdict(NoiseGate.SCENE_RULES, "口令「${globalRules.stopPhrase}」")
        }
        if (globalRules.isWithinPrivateHours(minutesSinceMidnight)) {
            return NoiseVerdict(NoiseGate.SCENE_RULES, "私人时段")
        }
        val intent = features.intent
        if (intent != null && scene?.actionFor(intent) == ResponseAction.IGNORE) {
            return NoiseVerdict(NoiseGate.SCENE_RULES, "${scene.name} · $intent 设为忽略")
        }

        // Gate 4 before gate 3: an explicit human "not important" outranks any
        // structural heuristic, and re-deriving the heuristic first would be waste.
        if (userIgnored.any { features.text.contains(it) }) {
            return NoiseVerdict(NoiseGate.USER_FEEDBACK, "你标记过「不重要」")
        }

        // Gate 3 — 三无句. A sentence with no person, no verb and no time, heard
        // only once, has nothing for the graph to attach to. Repetition rescues it:
        // something said twice is about something, even if we cannot parse what.
        if (!features.hasPersonName &&
            !features.hasVerb &&
            !features.hasTimeReference &&
            features.priorOccurrences == 0
        ) {
            return NoiseVerdict(NoiseGate.THREE_WITHOUT, "无人名 / 无动词 / 无时间，且首次出现")
        }

        return NoiseVerdict.Keep
    }
}

/** Weekly per-gate tallies, shown in 教小谛 › 记忆与遗忘. */
@Serializable
data class NoiseStats(
    /** Milliseconds of audio the device VAD suppressed. */
    val deviceVadSuppressedMs: Long = 0,
    val sceneRuleDrops: Int = 0,
    val threeWithoutDrops: Int = 0,
    val userFeedbackDrops: Int = 0,
) {
    val totalDrops: Int get() = sceneRuleDrops + threeWithoutDrops + userFeedbackDrops

    fun plus(verdict: NoiseVerdict): NoiseStats = when (verdict.gate) {
        NoiseGate.SCENE_RULES -> copy(sceneRuleDrops = sceneRuleDrops + 1)
        NoiseGate.THREE_WITHOUT -> copy(threeWithoutDrops = threeWithoutDrops + 1)
        NoiseGate.USER_FEEDBACK -> copy(userFeedbackDrops = userFeedbackDrops + 1)
        NoiseGate.DEVICE_VAD, null -> this
    }
}
