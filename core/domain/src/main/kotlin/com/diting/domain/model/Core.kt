package com.diting.domain.model

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Which hardware produced a recording. */
@Serializable
enum class DeviceKind {
    /** BLE recorder speaking the `AA_BLE&…` protocol. */
    MR20,

    /** Newsmy Farosh, whose recordings live in its own cloud. */
    FAROSH,

    /** The phone's own microphone (quick capture). */
    PHONE,
}

/**
 * A stable pointer back to a moment in a recording.
 *
 * Principle 1 of the design: the memory graph is the only source of truth, and
 * *every* generated artefact carries a way back to the audio it came from. That
 * is what this type is for — a summary bullet, an insight, a task and a report
 * paragraph all hold [Citation]s, and the "↩ 回到原声" affordance is just
 * navigating one.
 */
@Serializable
data class Citation(
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
    /** The segment this was taken from, when the span maps to exactly one. */
    val segmentId: String? = null,
) {
    init {
        require(startMs >= 0) { "citation start must be >= 0" }
        require(endMs >= startMs) { "citation end must be >= start" }
    }

    val duration: Duration get() = (endMs - startMs).milliseconds

    /** `09:41` style label used on the "回到原声" chips. */
    fun timeLabel(): String {
        val totalSeconds = startMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hours = minutes / 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes % 60, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }
}

/** Anything the app generated that must be traceable back to audio. */
interface Cited {
    val citations: List<Citation>
}

/** A speaker within one session. */
@Serializable
data class Speaker(
    /** Diarisation label, e.g. `A`, `B`. */
    val label: String,
    /** Resolved identity once the voiceprint or the user says who this is. */
    val personId: String? = null,
    val displayName: String? = null,
    /** True for the device owner — established by the onboarding voiceprint. */
    val isOwner: Boolean = false,
) {
    val name: String get() = displayName ?: "说话人 $label"
}

/** One diarised, transcribed span of audio. */
@Serializable
data class Segment(
    val id: String,
    val sessionId: String,
    val speakerLabel: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Set when the user fixed the ASR output; the original is kept for retraining. */
    val correctedFrom: String? = null,
    /** Filled by the scene rules — see [com.diting.domain.scene.SceneRules]. */
    val intent: Intent? = null,
    /** True once a task, insight or report cites this segment. */
    val isCited: Boolean = false,
) {
    val citation: Citation get() = Citation(sessionId, startMs, endMs, id)
    val wasCorrected: Boolean get() = correctedFrom != null
}

/**
 * What a sentence *does*, which is what the scene response rules key off.
 *
 * From the design: "客户通话" = 说话人分离 + 承诺→待办 + 祈使→建任务 + 闲聊→忽略.
 */
@Serializable
enum class Intent {
    /** 承诺句 — "我周五之前给你". Becomes a to-do. */
    COMMITMENT,

    /** 祈使句 — "帮我整理一份…". Becomes a task. */
    IMPERATIVE,

    /** 决策 — "那就这么定". Goes into the minutes. */
    DECISION,

    /** 反复诉求 — tracked for frequency, becomes an observation. */
    RECURRING_ASK,

    /** 风险 — flagged. */
    RISK,

    /** 闲聊 — ignored. */
    SMALL_TALK,

    /** 外语 — translated. */
    FOREIGN_LANGUAGE,
}

/** One recording and everything derived from it. */
@Serializable
data class Session(
    val id: String,
    val title: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val device: DeviceKind,
    /** Path/URI of the local audio file, null once the 30-day audio window lapses. */
    val audioPath: String? = null,
    /** Device-side identity, e.g. `2025-08-13/REC0001.MP3` on an MR20. */
    val deviceFilePath: String? = null,
    val sceneId: String? = null,
    val speakers: List<Speaker> = emptyList(),
    val transcriptState: TranscriptState = TranscriptState.PENDING,
    /** Set when the user overrode the auto-detected scene with the recording chip. */
    val sceneOverridden: Boolean = false,
)

@Serializable
enum class TranscriptState {
    /** Audio synced, not transcribed yet. */
    PENDING,
    RUNNING,
    DONE,
    FAILED,
}
