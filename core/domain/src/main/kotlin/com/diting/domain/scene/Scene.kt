package com.diting.domain.scene

import com.diting.domain.model.Intent
import kotlinx.serialization.Serializable

/**
 * What 谛听 does when it hears a given kind of sentence.
 *
 * The key restructuring from the design: **response rules hang off a scene, not
 * off a global switch**. "客户通话" and "家人闲聊" want opposite behaviour from the
 * same sentence, and a global toggle can only ever be wrong for one of them.
 */
@Serializable
enum class ResponseAction {
    /** Create a to-do. */
    CREATE_TODO,

    /** Create a task at gate ①. */
    CREATE_TASK,

    /** Record in the minutes. */
    RECORD_IN_MINUTES,

    /** Count towards a recurring-ask observation. */
    TRACK_AS_OBSERVATION,

    /** Translate inline. */
    TRANSLATE,

    /** Drop it: not transcribed into the graph. */
    IGNORE,
}

/** One "听到 → 做" switch in the scene editor. */
@Serializable
data class ResponseRule(
    val intent: Intent,
    val action: ResponseAction,
    val enabled: Boolean = true,
)

/**
 * A named situation with its own listening behaviour and prompts.
 *
 * [isBuiltIn] scenes ship with the app; users add their own in
 * 教小谛 › 场景与响应规则.
 */
@Serializable
data class Scene(
    val id: String,
    val name: String,
    val rules: List<ResponseRule> = emptyList(),
    /** Diarise speakers. Expensive, so it is per-scene rather than always on. */
    val speakerSeparation: Boolean = true,
    /** Scene-specific prompt appended to the summary/todo/insight prompts. */
    val customPrompt: String? = null,
    val isBuiltIn: Boolean = false,
    /** Words that suggest this scene during auto-detection. */
    val cues: List<String> = emptyList(),
) {
    fun actionFor(intent: Intent): ResponseAction? =
        rules.firstOrNull { it.intent == intent && it.enabled }?.action

    companion object {
        /** The scenes offered during onboarding ("再选 2–4 个常用场景"). */
        val BuiltIns: List<Scene> = listOf(
            Scene(
                id = "customer_call",
                name = "客户通话",
                isBuiltIn = true,
                speakerSeparation = true,
                cues = listOf("报价", "合同", "续费", "方案", "预算"),
                rules = listOf(
                    ResponseRule(Intent.COMMITMENT, ResponseAction.CREATE_TODO),
                    ResponseRule(Intent.IMPERATIVE, ResponseAction.CREATE_TASK),
                    ResponseRule(Intent.DECISION, ResponseAction.RECORD_IN_MINUTES),
                    ResponseRule(Intent.RECURRING_ASK, ResponseAction.TRACK_AS_OBSERVATION),
                    ResponseRule(Intent.RISK, ResponseAction.RECORD_IN_MINUTES),
                    ResponseRule(Intent.SMALL_TALK, ResponseAction.IGNORE),
                    ResponseRule(Intent.FOREIGN_LANGUAGE, ResponseAction.TRANSLATE),
                ),
            ),
            Scene(
                id = "internal_meeting",
                name = "内部会议",
                isBuiltIn = true,
                speakerSeparation = true,
                cues = listOf("周会", "评审", "排期", "对齐", "复盘"),
                rules = listOf(
                    ResponseRule(Intent.COMMITMENT, ResponseAction.CREATE_TODO),
                    ResponseRule(Intent.DECISION, ResponseAction.RECORD_IN_MINUTES),
                    ResponseRule(Intent.IMPERATIVE, ResponseAction.CREATE_TASK),
                    ResponseRule(Intent.RISK, ResponseAction.RECORD_IN_MINUTES),
                    ResponseRule(Intent.SMALL_TALK, ResponseAction.IGNORE),
                ),
            ),
            Scene(
                id = "one_on_one",
                name = "一对一",
                isBuiltIn = true,
                speakerSeparation = true,
                cues = listOf("反馈", "成长", "绩效", "想法"),
                rules = listOf(
                    ResponseRule(Intent.COMMITMENT, ResponseAction.CREATE_TODO),
                    ResponseRule(Intent.RECURRING_ASK, ResponseAction.TRACK_AS_OBSERVATION),
                    ResponseRule(Intent.DECISION, ResponseAction.RECORD_IN_MINUTES),
                    ResponseRule(Intent.SMALL_TALK, ResponseAction.IGNORE),
                ),
            ),
            Scene(
                id = "solo_thinking",
                name = "自己碎念",
                isBuiltIn = true,
                // One speaker; diarisation is wasted work.
                speakerSeparation = false,
                cues = listOf("想到", "记一下", "别忘了"),
                rules = listOf(
                    ResponseRule(Intent.IMPERATIVE, ResponseAction.CREATE_TASK),
                    ResponseRule(Intent.COMMITMENT, ResponseAction.CREATE_TODO),
                    ResponseRule(Intent.RECURRING_ASK, ResponseAction.TRACK_AS_OBSERVATION),
                ),
            ),
            Scene(
                id = "lecture",
                name = "讲座 / 培训",
                isBuiltIn = true,
                speakerSeparation = false,
                cues = listOf("分享", "课程", "培训", "演讲"),
                rules = listOf(
                    ResponseRule(Intent.DECISION, ResponseAction.RECORD_IN_MINUTES),
                    ResponseRule(Intent.FOREIGN_LANGUAGE, ResponseAction.TRANSLATE),
                    ResponseRule(Intent.SMALL_TALK, ResponseAction.IGNORE),
                ),
            ),
        )

        fun builtIn(id: String): Scene? = BuiltIns.firstOrNull { it.id == id }
    }
}

/**
 * Rules that apply no matter which scene is active.
 *
 * The design keeps this list deliberately short — "全局只留忽略规则" — because
 * anything else belongs to a scene.
 */
@Serializable
data class GlobalIgnoreRules(
    /** Spoken command that stops capture, e.g. 「小谛别记」. */
    val stopPhrase: String = "小谛别记",
    /** Local-time windows during which nothing is kept. */
    val privateHours: List<TimeWindow> = emptyList(),
    /** Redact numbers that look like cards, IDs and phone numbers. */
    val redactSensitiveNumbers: Boolean = true,
) {
    fun isWithinPrivateHours(minutesSinceMidnight: Int): Boolean =
        privateHours.any { it.contains(minutesSinceMidnight) }
}

/** A local-time window, minutes since midnight. Wraps across midnight. */
@Serializable
data class TimeWindow(val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0..1439 && endMinute in 0..1439) {
            "time window bounds must be minutes since midnight"
        }
    }

    fun contains(minute: Int): Boolean =
        if (startMinute <= endMinute) minute in startMinute until endMinute
        // 22:00–07:00 style windows straddle midnight.
        else minute >= startMinute || minute < endMinute
}

/**
 * Picks a scene for a recording from its cue words.
 *
 * Auto-detection is a suggestion, never a lock: the recording screen shows a chip
 * that overrides it ("这场按 X 处理"), and [Scene] rules only ever run against the
 * scene actually in effect.
 */
class SceneDetector(private val scenes: List<Scene> = Scene.BuiltIns) {

    /** @return the best-matching scene, or null when nothing scores. */
    fun detect(text: String): Scene? = scenes
        .map { scene -> scene to scene.cues.count { text.contains(it) } }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }
        ?.first
}
