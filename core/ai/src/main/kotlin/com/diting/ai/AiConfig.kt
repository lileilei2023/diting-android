package com.diting.ai

import kotlinx.serialization.Serializable

/**
 * Which vendor an [AiEndpoint] talks to.
 *
 * All three speak the OpenAI chat-completions shape, which is why one client
 * covers them. They differ in base URL, model naming and — for ASR — quite a lot
 * more, so ASR gets its own abstraction.
 */
@Serializable
enum class AiVendor(val displayName: String) {
    /** 阿里云百炼 / 通义千问 (DashScope), OpenAI-compatible mode. */
    QWEN("千问 · 阿里云百炼"),

    /** 火山方舟 / 豆包 (Volcengine Ark). */
    DOUBAO("豆包 · 火山方舟"),

    /**
     * Anything else exposing an OpenAI-compatible API: a self-hosted vLLM,
     * Xinference, FunASR or Whisper server, or another vendor entirely.
     */
    OPENAI_COMPATIBLE("自建 / OpenAI 兼容"),
}

/**
 * One configured model endpoint.
 *
 * **Every field is user-editable in 教小谛 › 模型与 Skill.** The defaults below are
 * a starting point, not a guarantee: vendors move base URLs and rename models,
 * and an app that hard-codes them becomes unusable the day that happens. If a
 * request fails with 404 or "model not found", the fix is to correct these
 * fields, not to ship a new build.
 */
@Serializable
data class AiEndpoint(
    val vendor: AiVendor,
    /** Base URL up to but not including `/chat/completions`. */
    val baseUrl: String,
    val model: String,
    /** Sent as `Authorization: Bearer <apiKey>`. Stored encrypted on-device. */
    val apiKey: String,
    /** Extra headers some deployments require. */
    val extraHeaders: Map<String, String> = emptyMap(),
    val temperature: Double = 0.3,
    val maxTokens: Int? = null,
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
    }

    /** Base URL with any trailing slash removed, so path joins stay predictable. */
    val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')

    fun chatCompletionsUrl(): String = "$normalizedBaseUrl/chat/completions"

    /** Used by [com.diting.ai.asr.OpenAiCompatibleAsrClient]. */
    fun transcriptionsUrl(): String = "$normalizedBaseUrl/audio/transcriptions"

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    companion object {
        /**
         * 千问 via DashScope's OpenAI-compatible mode.
         *
         * Verify against the current 阿里云百炼 docs before shipping — the
         * `compatible-mode/v1` path and model names are the vendor's to change.
         */
        fun qwen(apiKey: String, model: String = "qwen-plus") = AiEndpoint(
            vendor = AiVendor.QWEN,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = model,
            apiKey = apiKey,
        )

        /**
         * 豆包 via 火山方舟.
         *
         * Ark addresses models by *endpoint ID* (`ep-…`) rather than a friendly
         * name in many configurations, so [model] is very likely something the
         * user must paste from the console rather than a value we can default.
         */
        fun doubao(apiKey: String, model: String) = AiEndpoint(
            vendor = AiVendor.DOUBAO,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            model = model,
            apiKey = apiKey,
        )

        /** A self-hosted or third-party OpenAI-compatible server. */
        fun custom(baseUrl: String, model: String, apiKey: String = "") = AiEndpoint(
            vendor = AiVendor.OPENAI_COMPATIBLE,
            baseUrl = baseUrl,
            model = model,
            apiKey = apiKey,
        )
    }
}

/**
 * The three model slots the design exposes: 转写 / 理解 / Agent.
 *
 * Keeping them separate is what lets a user run a cheap local ASR and an
 * expensive reasoning model without paying for the expensive one on every minute
 * of audio.
 */
@Serializable
data class AiSettings(
    /** ASR. Null means transcription is unavailable and sessions stay PENDING. */
    val transcription: AsrEndpoint? = null,
    /** Summaries, to-do extraction, insights. */
    val understanding: AiEndpoint? = null,
    /** Task planning and execution. Falls back to [understanding] when unset. */
    val agent: AiEndpoint? = null,
) {
    val agentOrUnderstanding: AiEndpoint? get() = agent ?: understanding

    val isUsable: Boolean get() = transcription != null && understanding != null
}

/** How to reach a speech-recognition service. */
@Serializable
data class AsrEndpoint(
    val vendor: AiVendor,
    val kind: AsrKind,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val language: String = "zh",
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Ask the service for per-segment timestamps. Required for "回到原声". */
    val wantTimestamps: Boolean = true,
    /** Ask for speaker labels when the scene wants diarisation. */
    val wantSpeakerLabels: Boolean = true,
) {
    val normalizedBaseUrl: String get() = baseUrl.trimEnd('/')

    companion object {
        /**
         * A self-deployed ASR server exposing `POST /audio/transcriptions`
         * (Whisper, FunASR and vLLM-style servers all do). This is the shape the
         * user described as "云端模型部署了 asr 模型后的直接识别".
         */
        fun selfHosted(baseUrl: String, model: String = "whisper-1", apiKey: String = "") =
            AsrEndpoint(
                vendor = AiVendor.OPENAI_COMPATIBLE,
                kind = AsrKind.OPENAI_COMPATIBLE_UPLOAD,
                baseUrl = baseUrl,
                model = model,
                apiKey = apiKey,
            )
    }
}

@Serializable
enum class AsrKind {
    /**
     * Multipart upload to `/audio/transcriptions`, answer in one response.
     * Simplest and the only one that works without the audio being reachable
     * from the internet.
     */
    OPENAI_COMPATIBLE_UPLOAD,

    /**
     * Submit-then-poll against a job API, where the request carries a *URL* to
     * the audio rather than its bytes. Needs the file to be publicly reachable,
     * which for a phone recording means uploading it somewhere first.
     */
    ASYNC_JOB_BY_URL,
}
