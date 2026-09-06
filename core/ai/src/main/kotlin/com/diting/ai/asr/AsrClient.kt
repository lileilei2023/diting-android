package com.diting.ai.asr

import com.diting.ai.AsrEndpoint
import com.diting.ai.http.AiException
import com.diting.ai.http.AiJson
import com.diting.ai.http.HttpCaller
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/** One transcribed span. Timestamps are what make "↩ 回到原声" possible. */
data class AsrSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Diarisation label when the service provides one. */
    val speakerLabel: String? = null,
)

data class AsrResult(
    val segments: List<AsrSegment>,
    val language: String? = null,
) {
    val fullText: String get() = segments.joinToString("") { it.text }
    val durationMs: Long get() = segments.maxOfOrNull { it.endMs } ?: 0
    val speakerLabels: List<String> get() = segments.mapNotNull { it.speakerLabel }.distinct()
}

interface AsrClient {
    /**
     * Transcribes a local audio file.
     *
     * @param hotwords terms from 教小谛 › 热词与专名. Passed as a biasing prompt
     *   where the service supports it — this is what makes 「欧艾斯艾斯」 come back
     *   as 「OSS」 without the user having to correct it every time.
     */
    suspend fun transcribe(audio: File, hotwords: List<String> = emptyList()): AsrResult
}

// -- OpenAI-compatible upload ---------------------------------------------------

@Serializable
private data class VerboseTranscription(
    val text: String = "",
    val language: String? = null,
    val duration: Double? = null,
    val segments: List<VerboseSegment> = emptyList(),
)

@Serializable
private data class VerboseSegment(
    val id: Int = 0,
    val start: Double = 0.0,
    val end: Double = 0.0,
    val text: String = "",
    /** Non-standard but widely added by FunASR / WhisperX style servers. */
    val speaker: String? = null,
)

/** Fallback shape: `{"text": "…"}` with no timestamps. */
@Serializable
private data class PlainTranscription(val text: String = "")

/**
 * Talks to any server exposing `POST /audio/transcriptions` — self-hosted Whisper,
 * FunASR, WhisperX, vLLM-style deployments.
 *
 * This is the transcription path that actually works for a recorder app: the
 * audio is uploaded as bytes, so nothing has to be publicly reachable on the
 * internet first.
 */
class OpenAiCompatibleAsrClient(
    private val endpoint: AsrEndpoint,
    okHttp: OkHttpClient,
    private val caller: HttpCaller = HttpCaller(okHttp),
) : AsrClient {

    override suspend fun transcribe(audio: File, hotwords: List<String>): AsrResult {
        if (!audio.exists()) throw AiException.BadResponse("音频文件不存在：${audio.path}")

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", endpoint.model)
            .addFormDataPart("language", endpoint.language)
            .apply {
                // verbose_json is what carries per-segment timestamps. Without it
                // there is nothing to anchor "回到原声" to.
                if (endpoint.wantTimestamps) {
                    addFormDataPart("response_format", "verbose_json")
                    addFormDataPart("timestamp_granularities[]", "segment")
                }
                if (hotwords.isNotEmpty()) {
                    addFormDataPart("prompt", hotwords.joinToString("、"))
                }
                addFormDataPart(
                    "file",
                    audio.name,
                    audio.asRequestBody(mediaTypeFor(audio)),
                )
            }
            .build()

        val request = Request.Builder()
            .url("${endpoint.normalizedBaseUrl}/audio/transcriptions")
            .post(body)
            .apply {
                if (endpoint.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${endpoint.apiKey}")
                }
                endpoint.extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()

        return parse(caller.execute(request))
    }

    private fun parse(raw: String): AsrResult {
        val verbose = runCatching {
            AiJson.decodeFromString(VerboseTranscription.serializer(), raw)
        }.getOrNull()

        if (verbose != null && verbose.segments.isNotEmpty()) {
            return AsrResult(
                segments = verbose.segments.map {
                    AsrSegment(
                        startMs = (it.start * 1000).toLong(),
                        endMs = (it.end * 1000).toLong(),
                        text = it.text.trim(),
                        speakerLabel = it.speaker,
                    )
                },
                language = verbose.language,
            )
        }

        // A server that ignored response_format still gives us usable text; we
        // just cannot offer timestamps for it, and one whole-file segment says so
        // honestly rather than inventing per-sentence boundaries.
        val text = verbose?.text?.takeIf { it.isNotBlank() }
            ?: runCatching {
                AiJson.decodeFromString(PlainTranscription.serializer(), raw).text
            }.getOrNull().orEmpty()

        if (text.isBlank()) {
            throw AiException.BadResponse("转写服务返回了空结果：${raw.take(300)}")
        }

        val durationMs = ((verbose?.duration ?: 0.0) * 1000).toLong()
        return AsrResult(
            segments = listOf(AsrSegment(0, durationMs, text.trim())),
            language = verbose?.language,
        )
    }

    private fun mediaTypeFor(file: File) = when (file.extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }.toMediaType()
}

// -- async job by URL -----------------------------------------------------------

@Serializable
private data class JobSubmitResponse(
    @SerialName("output") val output: JobOutput? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable
private data class JobOutput(
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("task_status") val taskStatus: String? = null,
    val results: List<JobResult> = emptyList(),
)

@Serializable
private data class JobResult(
    @SerialName("transcription_url") val transcriptionUrl: String? = null,
    @SerialName("subtask_status") val subtaskStatus: String? = null,
)

/**
 * The submit-then-poll shape used by DashScope's file transcription.
 *
 * Kept behind the same [AsrClient] interface, but note the constraint that makes
 * it awkward for this app: the request carries a **URL** to the audio, not its
 * bytes, so the recording has to be uploaded somewhere publicly reachable first.
 * [OpenAiCompatibleAsrClient] is the better default for phone recordings; this
 * exists for deployments that already have object storage in the loop.
 */
class AsyncJobAsrClient(
    private val endpoint: AsrEndpoint,
    okHttp: OkHttpClient,
    private val caller: HttpCaller = HttpCaller(okHttp),
    private val pollIntervalMs: Long = 2_000,
    private val maxPolls: Int = 150,
    /** Uploads the file and returns a URL the ASR service can fetch. */
    private val uploadAudio: suspend (File) -> String,
    /** Fetches the transcription document the job points at. */
    private val fetchTranscript: suspend (String) -> String,
) : AsrClient {

    override suspend fun transcribe(audio: File, hotwords: List<String>): AsrResult {
        val audioUrl = uploadAudio(audio)
        val taskId = submit(audioUrl, hotwords)

        repeat(maxPolls) {
            val output = poll(taskId)
            when (output?.taskStatus?.uppercase()) {
                "SUCCEEDED" -> {
                    val url = output.results.firstNotNullOfOrNull { it.transcriptionUrl }
                        ?: throw AiException.BadResponse("转写任务完成但没有结果地址")
                    return DashScopeTranscriptParser.parse(fetchTranscript(url))
                }

                "FAILED", "CANCELED" ->
                    throw AiException.BadResponse("转写任务失败：${output.taskStatus}")

                else -> kotlinx.coroutines.delay(pollIntervalMs)
            }
        }
        throw AiException.BadResponse(
            "转写任务在 ${(maxPolls * pollIntervalMs) / 1000}s 内没有完成"
        )
    }

    private suspend fun submit(audioUrl: String, hotwords: List<String>): String {
        val payload = buildString {
            append("""{"model":"${endpoint.model}",""")
            append(""""input":{"file_urls":["$audioUrl"]},""")
            append(""""parameters":{"language_hints":["${endpoint.language}"]""")
            if (hotwords.isNotEmpty()) {
                append(""","vocabulary":[${hotwords.joinToString(",") { "\"$it\"" }}]""")
            }
            append("}}")
        }

        val request = Request.Builder()
            .url("${endpoint.normalizedBaseUrl}/services/audio/asr/transcription")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            // Tells the service to return a task handle rather than blocking.
            .header("X-DashScope-Async", "enable")
            .apply { endpoint.extraHeaders.forEach { (k, v) -> header(k, v) } }
            .build()

        val response = AiJson.decodeFromString(
            JobSubmitResponse.serializer(),
            caller.execute(request),
        )
        return response.output?.taskId
            ?: throw AiException.BadResponse("转写任务没有返回 task_id")
    }

    private suspend fun poll(taskId: String): JobOutput? {
        val request = Request.Builder()
            .url("${endpoint.normalizedBaseUrl}/tasks/$taskId")
            .get()
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .build()
        return AiJson.decodeFromString(
            JobSubmitResponse.serializer(),
            caller.execute(request),
        ).output
    }
}

/** Parses the transcript document an async job points at. */
internal object DashScopeTranscriptParser {

    @Serializable
    private data class Doc(val transcripts: List<Transcript> = emptyList())

    @Serializable
    private data class Transcript(
        val text: String = "",
        val sentences: List<Sentence> = emptyList(),
    )

    @Serializable
    private data class Sentence(
        @SerialName("begin_time") val beginTime: Long = 0,
        @SerialName("end_time") val endTime: Long = 0,
        val text: String = "",
        @SerialName("speaker_id") val speakerId: String? = null,
    )

    fun parse(raw: String): AsrResult {
        val doc = AiJson.decodeFromString(Doc.serializer(), raw)
        val sentences = doc.transcripts.flatMap { it.sentences }
        if (sentences.isEmpty()) {
            val text = doc.transcripts.joinToString("") { it.text }
            if (text.isBlank()) throw AiException.BadResponse("转写结果为空")
            return AsrResult(listOf(AsrSegment(0, 0, text)))
        }
        return AsrResult(
            sentences.map {
                AsrSegment(it.beginTime, it.endTime, it.text.trim(), it.speakerId)
            }
        )
    }
}
