package com.diting.app.brain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire shapes of the neuroagi-core-v2 ("大脑") backend, ported from the Echo
 * bridge app that was verified against the deployed server and a real MR20.
 * Field names mirror brain/server.py, integrations/console_api.py and
 * integrations/echo_account_ext.py — do not "tidy" them.
 */

/** `POST /auth/login|register` response. */
@Serializable
data class AuthResult(val token: String, val subject: String, val nick: String = "")

/** `GET /auth/me`. */
@Serializable
data class Me(val subject: String, val nick: String = "", val phone: String = "")

/** One speaker turn parsed from an `/upload` transcript line ("说话人：文本"). */
@Serializable
data class Turn(val speaker: String, val text: String)

/**
 * Uplink frame on `WS /channel/pendant-<SN>`.
 *
 * Every field the kernel does not know is passed through into the memory body
 * (`extra`), so device-side context such as loudness or place survives.
 */
@Serializable
data class AsrFrame(
    val modality: String = "asr",
    val text: String,
    val speaker: String = "说话人1",
    val session: String,
    val confidence: Double = 0.9,
    /** Idempotency key: replaying the same frame returns the same memory id. */
    val idem: String,
    val tags: List<String> = listOf("device:pendant"),
    val salience: Double = 0.5,
    /** True start time of the segment (epoch seconds). Without it the server stamps ingest time. */
    val at: Long? = null,
    val loudness: Float? = null,
    val place: String? = null,
    val placeName: String? = null,
)

/** Downlink: the brain speaking, live or replayed from its offline backlog. */
@Serializable
data class Say(val text: String, val importance: Double = 0.5)

sealed interface ChannelState {
    data object Disconnected : ChannelState
    data object Opening : ChannelState

    /** `hello` received; [backlog] is how many queued `say`s were flushed on attach. */
    data class Live(val backlog: Int) : ChannelState

    /** Close code 1008: token or device key rejected. No automatic reconnect. */
    data class AuthFailed(val reason: String) : ChannelState

    /** Nothing to connect with yet (not logged in, or device not adopted). */
    data object NotConfigured : ChannelState
}

class AuthExpiredException(message: String = "token expired (401)") : Exception(message)
class ChannelOfflineException(message: String = "pendant channel not live") : Exception(message)
class BrainHttpException(val code: Int, message: String) : Exception("HTTP $code: $message")

/** `/upload` with `store_raw=true, extract=true`: the full ingest pipeline. */
@Serializable
data class UploadReport(
    val frames: Int = 0,
    val summary: String = "",
    val title: String = "",
    @SerialName("facts_new") val factsNew: Int = 0,
    /** Speaker turns as "说话人：文本" lines; a leading "⚠️ " marks a flagged line. */
    val transcript: List<String> = emptyList(),
    /** Action items the brain extracted. */
    val items: List<UploadAction> = emptyList(),
    /**
     * Durable facts, as text. The server sends these as `[subject, relation,
     * object]` triples (sometimes plain strings); [BrainApi] flattens them so a
     * shape change on the server can never fail an upload that succeeded.
     */
    val facts: List<String> = emptyList(),
) {
    /** [transcript] parsed into turns. */
    val turns: List<Turn>
        get() = transcript.mapNotNull { raw ->
            val line = raw.removePrefix("⚠️ ").trim()
            if (line.isBlank()) return@mapNotNull null
            val i = line.indexOf('：')
            if (i > 0) Turn(line.substring(0, i), line.substring(i + 1).trim())
            else Turn("说话人1", line)
        }.filter { it.text.isNotBlank() }
}

@Serializable
data class UploadAction(
    val owner: String = "",
    val task: String = "",
    @SerialName("due_days") val dueDays: Int? = null,
)

/** One row of `GET /memories`, loosely parsed for display. */
data class MemView(
    val id: Long,
    val kind: String,
    val text: String,
    val title: String,
    val speaker: String,
    val category: String,
    val tags: List<String>,
    /** Epoch seconds. */
    val happenedAt: Double,
)

/** One row of `GET /pending`: a proposed action waiting for the user. */
data class PendingItem(
    val id: Long,
    val title: String,
    val reason: String,
    val evidence: List<String>,
    val capability: String,
    val action: String,
)

/** `POST /ask`. [degraded] means the server had no LLM and only returned sources. */
data class AskResult(
    val answer: String,
    val degraded: Boolean,
    val sources: List<MemView>,
)
