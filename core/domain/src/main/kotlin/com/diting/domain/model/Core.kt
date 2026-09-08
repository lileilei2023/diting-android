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

    /**
     * 技术决策 — a decision about how something gets built: an interface, a
     * protocol, a migration, a rollout order.
     *
     * Separate from [DECISION] because it routes differently. An ordinary
     * decision belongs in the minutes; a technical one can be handed to a
     * development agent that opens the issue or drafts the RFC — and that is a
     * destination with a much narrower set of permitted actions.
     */
    TECH_DECISION,

    /** 反复诉求 — tracked for frequency, becomes an observation. */
    RECURRING_ASK,

    /** 风险 — flagged. */
    RISK,

    /** 闲聊 — ignored. */
    SMALL_TALK,

    /** 外语 — translated. */
    FOREIGN_LANGUAGE,
}

/**
 * What the record key was asked to do.
 *
 * Purely a client-side distinction — the three modes produce the same kind of
 * [Session] and go through the same understanding pipeline afterwards. The
 * enum exists so the recording screen and the session list can say which one a
 * recording came from.
 */
@Serializable
enum class SessionKind {
    /** 会议模式 — long press. The default. */
    MEETING,

    /** 快速捕捉 — short press. Does not open the realtime channel. */
    QUICK_CAPTURE,

    /**
     * 同传 — live two-way interpretation.
     *
     * Runs off the phone microphone rather than the recorder, and is metered
     * against the realtime budget separately from ordinary transcription
     * because it holds the channel open for the whole conversation.
     *
     * Not stored unless the user asks for it at the end; see [Session.source].
     */
    INTERPRETATION,
}

/** Where a session's audio came from. */
@Serializable
enum class SessionSource {
    /** Synced off a paired recorder over BLE or its Wi-Fi AP. */
    DEVICE_SYNC,

    /** Captured on the phone's own microphone. */
    PHONE_CAPTURE,

    /**
     * Imported from the Farosh cloud, transcript and summary included.
     *
     * Farosh is an import adapter, not a second recorder: 谛听 pulls finished
     * recordings out of the Newsmy account rather than talking to the hardware.
     * The practical consequence is [needsTranscription] — re-running ASR over
     * an import would burn the user's quota to reproduce text they already have.
     */
    FAROSH_IMPORT,
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
    /** Why transcription failed, in the user's words; null unless [transcriptState] is FAILED. */
    val transcriptError: String? = null,
    /** Set when the user overrode the auto-detected scene with the recording chip. */
    val sceneOverridden: Boolean = false,
    val kind: SessionKind = SessionKind.MEETING,
    val source: SessionSource = SessionSource.DEVICE_SYNC,
) {
    /**
     * Whether the transcription worker should pick this session up.
     *
     * An import arrives with its transcript already written, so it is DONE from
     * the moment it lands and must never be queued — that is the whole point of
     * treating Farosh as an import adapter rather than a device.
     */
    val needsTranscription: Boolean
        get() = source != SessionSource.FAROSH_IMPORT &&
            transcriptState == TranscriptState.PENDING
}

@Serializable
enum class TranscriptState {
    /** Audio synced, not transcribed yet. */
    PENDING,
    RUNNING,
    DONE,
    FAILED,
}
