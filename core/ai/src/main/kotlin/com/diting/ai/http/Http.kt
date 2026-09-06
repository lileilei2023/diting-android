package com.diting.ai.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Lenient on purpose: model vendors add response fields without warning. */
val AiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
}

/** Everything the AI layer can fail with, in terms a UI can act on. */
sealed class AiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** No API key configured. The UI should route to 教小谛 › 模型与 Skill. */
    class NotConfigured(slot: String) :
        AiException("$slot 还没有配置模型，请到「教小谛 › 模型与 Skill」填写")

    /** 401/403 — the key is wrong or lacks access to the model. */
    class Unauthorized(val status: Int, body: String) :
        AiException("鉴权失败（HTTP $status）：${body.take(200)}")

    /** 404 / "model not found" — base URL or model name is stale. */
    class EndpointNotFound(url: String, body: String) :
        AiException("接口或模型不存在：$url\n${body.take(200)}")

    /** 429 or 5xx after all retries. */
    class Unavailable(val status: Int, body: String) :
        AiException("服务暂时不可用（HTTP $status）：${body.take(200)}")

    class Network(cause: Throwable) :
        AiException("网络请求失败：${cause.message}", cause)

    /** The model answered, but not with what we asked for. */
    class BadResponse(message: String) : AiException(message)
}

/** Retry policy for transient failures. */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 500,
    val multiplier: Double = 2.0,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
    }

    fun delayForAttempt(attempt: Int): Long =
        (initialDelayMs * Math.pow(multiplier, (attempt - 1).toDouble())).toLong()
}

/**
 * Thin async wrapper over OkHttp with the retry and error mapping every AI call
 * in this app needs.
 *
 * Retries only what is worth retrying: 429 and 5xx are transient, 4xx is not.
 * Retrying a 401 just burns the user's battery and delays a message that tells
 * them to fix their key.
 */
class HttpCaller(
    private val client: OkHttpClient,
    private val retry: RetryPolicy = RetryPolicy(),
) {

    suspend fun execute(request: Request): String {
        var lastError: AiException? = null

        repeat(retry.maxAttempts) { index ->
            val attempt = index + 1
            val outcome = runCatching { await(request) }

            outcome.getOrNull()?.use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> return body

                    response.code == 401 || response.code == 403 ->
                        throw AiException.Unauthorized(response.code, body)

                    response.code == 404 ->
                        throw AiException.EndpointNotFound(request.url.toString(), body)

                    response.code == 429 || response.code >= 500 ->
                        lastError = AiException.Unavailable(response.code, body)

                    else -> throw AiException.BadResponse(
                        "HTTP ${response.code}: ${body.take(300)}"
                    )
                }
            }

            outcome.exceptionOrNull()?.let { cause ->
                if (cause is AiException) throw cause
                lastError = AiException.Network(cause)
            }

            if (attempt < retry.maxAttempts) delay(retry.delayForAttempt(attempt))
        }

        throw lastError ?: AiException.BadResponse("request failed with no diagnosis")
    }

    private suspend fun await(request: Request): Response = withContext(Dispatchers.IO) {
        suspendCoroutine { continuation ->
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) =
                    continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) =
                    continuation.resume(response)
            })
        }
    }
}
