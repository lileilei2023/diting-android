package com.diting.ai.llm

import com.diting.ai.AiEndpoint
import com.diting.ai.http.AiException
import com.diting.ai.http.AiJson
import com.diting.ai.http.HttpCaller
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
    }
}

@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
)

@Serializable
internal data class ResponseFormat(val type: String) {
    companion object {
        val Json = ResponseFormat("json_object")
    }
}

@Serializable
internal data class ChatResponse(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
internal data class Choice(
    val index: Int = 0,
    val message: ChatMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

/** What a completion produced. */
data class LlmResult(
    val text: String,
    val usage: Usage? = null,
    val finishReason: String? = null,
) {
    /** True when the model stopped because it hit the token cap, not because it was done. */
    val wasTruncated: Boolean get() = finishReason == "length"
}

interface LlmClient {
    suspend fun complete(
        messages: List<ChatMessage>,
        /** Ask for a JSON object back. Not every deployment honours it. */
        jsonMode: Boolean = false,
    ): LlmResult
}

/**
 * One client for 千问, 豆包 and any self-hosted OpenAI-compatible server.
 *
 * They differ in base URL and model naming, both of which live in [AiEndpoint],
 * so there is nothing vendor-specific left to branch on here. Where they *do*
 * differ in behaviour — Ark ignores `response_format` on some models, DashScope
 * accepts it — the difference is absorbed by [com.diting.ai.parse.StructuredOutput],
 * which never assumes the response is bare JSON.
 */
class OpenAiCompatibleLlmClient(
    private val endpoint: AiEndpoint,
    okHttp: OkHttpClient,
    private val caller: HttpCaller = HttpCaller(okHttp),
) : LlmClient {

    override suspend fun complete(messages: List<ChatMessage>, jsonMode: Boolean): LlmResult {
        if (!endpoint.isConfigured && endpoint.vendor != com.diting.ai.AiVendor.OPENAI_COMPATIBLE) {
            throw AiException.NotConfigured(endpoint.vendor.displayName)
        }

        val payload = ChatRequest(
            model = endpoint.model,
            messages = messages,
            temperature = endpoint.temperature,
            maxTokens = endpoint.maxTokens,
            responseFormat = if (jsonMode) ResponseFormat.Json else null,
        )

        val request = Request.Builder()
            .url(endpoint.chatCompletionsUrl())
            .post(
                AiJson.encodeToString(ChatRequest.serializer(), payload)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .apply {
                if (endpoint.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${endpoint.apiKey}")
                }
                endpoint.extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()

        val body = caller.execute(request)
        val parsed = runCatching { AiJson.decodeFromString(ChatResponse.serializer(), body) }
            .getOrElse { throw AiException.BadResponse("无法解析模型响应：${body.take(300)}") }

        val choice = parsed.choices.firstOrNull()
            ?: throw AiException.BadResponse("模型返回了空的 choices：${body.take(300)}")
        val text = choice.message?.content
            ?: throw AiException.BadResponse("模型返回的 choice 没有 content：${body.take(300)}")

        return LlmResult(text = text, usage = parsed.usage, finishReason = choice.finishReason)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
