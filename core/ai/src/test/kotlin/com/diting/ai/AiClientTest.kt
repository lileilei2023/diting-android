package com.diting.ai

import com.diting.ai.asr.OpenAiCompatibleAsrClient
import com.diting.ai.http.AiException
import com.diting.ai.http.HttpCaller
import com.diting.ai.http.RetryPolicy
import com.diting.ai.llm.ChatMessage
import com.diting.ai.llm.OpenAiCompatibleLlmClient
import com.diting.ai.understanding.UnderstandingService
import com.diting.domain.model.Segment
import com.diting.domain.scene.Scene
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files

class AiClientTest {

    private lateinit var server: MockWebServer
    private val okHttp = OkHttpClient()

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun endpoint(model: String = "qwen-plus", key: String = "sk-test") = AiEndpoint(
        vendor = AiVendor.QWEN,
        baseUrl = server.url("/v1").toString(),
        model = model,
        apiKey = key,
    )

    /** No back-off in tests: retry *behaviour* is asserted, wall-clock is not. */
    private fun llm(endpoint: AiEndpoint = endpoint()) = OpenAiCompatibleLlmClient(
        endpoint,
        okHttp,
        HttpCaller(okHttp, RetryPolicy(maxAttempts = 3, initialDelayMs = 1, multiplier = 1.0)),
    )

    private fun chatResponse(content: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"choices":[{"index":0,"message":{"role":"assistant","content":${quote(content)}},
             "finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
            """.trimIndent()
        )

    private fun quote(s: String) = buildString {
        append('"')
        s.forEach {
            when (it) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(it)
            }
        }
        append('"')
    }

    // -- request shape --------------------------------------------------------

    @Test
    fun `sends the model, the bearer token and the messages`() = runTest {
        server.enqueue(chatResponse("好的"))

        llm().complete(listOf(ChatMessage.user("你好")))

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"model\":\"qwen-plus\""), body)
        assertTrue(body.contains("\"你好\""), body)
    }

    @Test
    fun `json mode asks for a json object`() = runTest {
        server.enqueue(chatResponse("{}"))

        llm().complete(listOf(ChatMessage.user("x")), jsonMode = true)

        assertTrue(server.takeRequest().body.readUtf8().contains("\"json_object\""))
    }

    @Test
    fun `extra headers are sent`() = runTest {
        server.enqueue(chatResponse("ok"))

        llm(endpoint().copy(extraHeaders = mapOf("X-Tenant" to "acme")))
            .complete(listOf(ChatMessage.user("x")))

        assertEquals("acme", server.takeRequest().getHeader("X-Tenant"))
    }

    @Test
    fun `a trailing slash in the base url does not double up the path`() = runTest {
        server.enqueue(chatResponse("ok"))

        val trailing = endpoint().copy(baseUrl = server.url("/v1/").toString())
        llm(trailing).complete(listOf(ChatMessage.user("x")))

        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    // -- response handling ----------------------------------------------------

    @Test
    fun `reads content, usage and finish reason`() = runTest {
        server.enqueue(chatResponse("这是回答"))

        val result = llm().complete(listOf(ChatMessage.user("x")))

        assertEquals("这是回答", result.text)
        assertEquals(15, result.usage?.totalTokens)
        assertEquals("stop", result.finishReason)
        assertEquals(false, result.wasTruncated)
    }

    @Test
    fun `a length finish reason is surfaced as truncation`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"半句"},
                   "finish_reason":"length"}]}"""
            )
        )

        assertTrue(llm().complete(listOf(ChatMessage.user("x"))).wasTruncated)
    }

    @Test
    fun `unknown response fields are ignored`() = runTest {
        // Vendors add fields without warning; a strict parser would break on deploy.
        server.enqueue(
            MockResponse().setBody(
                """{"id":"x","created":1,"system_fingerprint":"fp","choices":
                   [{"index":0,"message":{"role":"assistant","content":"ok","reasoning":"…"},
                     "finish_reason":"stop","logprobs":null}]}"""
            )
        )

        assertEquals("ok", llm().complete(listOf(ChatMessage.user("x"))).text)
    }

    @Test
    fun `an empty choices array is a clear error, not a null pointer`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))

        val error = assertThrows<AiException.BadResponse> {
            llm().complete(listOf(ChatMessage.user("x")))
        }
        assertTrue(error.message!!.contains("空的 choices"))
    }

    // -- error mapping --------------------------------------------------------

    @Test
    fun `401 maps to Unauthorized and is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))

        assertThrows<AiException.Unauthorized> { llm().complete(listOf(ChatMessage.user("x"))) }
        // Retrying a bad key only delays telling the user to fix it.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `404 points at the endpoint so a stale base url is diagnosable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("model not found"))

        val error = assertThrows<AiException.EndpointNotFound> {
            llm().complete(listOf(ChatMessage.user("x")))
        }
        assertTrue(error.message!!.contains("chat/completions"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 is retried and then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        server.enqueue(chatResponse("终于成功"))

        assertEquals("终于成功", llm().complete(listOf(ChatMessage.user("x"))).text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a 5xx that never recovers is reported as unavailable`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("down")) }

        val error = assertThrows<AiException.Unavailable> {
            llm().complete(listOf(ChatMessage.user("x")))
        }
        assertEquals(503, error.status)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a 400 is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad request"))

        assertThrows<AiException.BadResponse> { llm().complete(listOf(ChatMessage.user("x"))) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a vendor endpoint with no key refuses before making a request`() = runTest {
        val unset = endpoint(key = "")

        assertThrows<AiException.NotConfigured> { llm(unset).complete(listOf(ChatMessage.user("x"))) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a self-hosted endpoint may legitimately have no key`() = runTest {
        server.enqueue(chatResponse("ok"))

        val selfHosted = AiEndpoint.custom(server.url("/v1").toString(), "local-model")
        OpenAiCompatibleLlmClient(selfHosted, okHttp).complete(listOf(ChatMessage.user("x")))

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    // -- ASR ------------------------------------------------------------------

    private fun audioFile(): File =
        Files.createTempFile("rec", ".mp3").toFile().apply { writeBytes(ByteArray(64) { 1 }) }

    private fun asr() = OpenAiCompatibleAsrClient(
        AsrEndpoint.selfHosted(server.url("/v1").toString(), "whisper-1", "sk-asr"),
        okHttp,
    )

    @Test
    fun `ASR uploads the file and asks for segment timestamps`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"language":"zh","duration":12.5,"segments":[
                   {"id":0,"start":0.0,"end":5.2,"text":"行，那就这么定","speaker":"A"},
                   {"id":1,"start":5.2,"end":12.5,"text":"小张周五前出对比表","speaker":"B"}]}"""
            )
        )

        val result = asr().transcribe(audioFile(), hotwords = listOf("OSS", "谛听"))

        val request = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", request.path)
        assertEquals("Bearer sk-asr", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("verbose_json"), "must ask for timestamps")
        assertTrue(body.contains("OSS、谛听"), "hotwords must bias the recogniser")

        assertEquals(2, result.segments.size)
        assertEquals(0L, result.segments[0].startMs)
        assertEquals(5200L, result.segments[0].endMs)
        assertEquals("A", result.segments[0].speakerLabel)
        assertEquals(12500L, result.durationMs)
        assertEquals(listOf("A", "B"), result.speakerLabels)
    }

    @Test
    fun `ASR falls back to one whole-file segment when the server ignores verbose_json`() =
        runTest {
            // Better an honest single span than invented sentence boundaries.
            server.enqueue(MockResponse().setBody("""{"text":"行，那就这么定"}"""))

            val result = asr().transcribe(audioFile())

            assertEquals(1, result.segments.size)
            assertEquals("行，那就这么定", result.fullText)
            assertNull(result.segments[0].speakerLabel)
        }

    @Test
    fun `an empty ASR result is an error, not an empty transcript`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":""}"""))

        assertThrows<AiException.BadResponse> { asr().transcribe(audioFile()) }
    }

    @Test
    fun `a missing audio file fails before the network call`() = runTest {
        assertThrows<AiException.BadResponse> {
            asr().transcribe(File("/nonexistent/nope.mp3"))
        }
        assertEquals(0, server.requestCount)
    }

    // -- understanding --------------------------------------------------------

    private fun segments(vararg texts: String) = texts.mapIndexed { i, text ->
        Segment(
            id = "s$i",
            sessionId = "sess",
            speakerLabel = if (i % 2 == 0) "A" else "B",
            startMs = i * 10_000L,
            endMs = (i + 1) * 10_000L,
            text = text,
        )
    }

    @Test
    fun `summary resolves segment indices into citations`() = runTest {
        server.enqueue(
            chatResponse(
                """{"title":"Q4 定价","one_line":"要不要改成按席位没吵完",
                   "points":[{"text":"账单波动","segment_index":0}],
                   "decisions":[{"text":"周五前出对比表","segment_index":1}],
                   "todos":[{"text":"输出三方案对比表","owner":"小张","due":"周五","segment_index":1}],
                   "risks":[]}"""
            )
        )
        val transcript = segments("现行按用量计价导致账单波动", "行，那就这么定")

        val summary = UnderstandingService(llm()).summarize(transcript)

        assertEquals("Q4 定价", summary.title)
        assertEquals(0L, summary.points[0].citation?.startMs)
        assertEquals(10_000L, summary.decisions[0].citation?.startMs)
        assertEquals("s1", summary.todos[0].citation?.segmentId)
        assertEquals("小张", summary.todos[0].owner)
    }

    @Test
    fun `an out-of-range segment index yields no citation rather than a wrong jump`() = runTest {
        // A "↩ 回到原声" chip that jumps to the wrong moment is worse than no chip.
        server.enqueue(
            chatResponse("""{"title":"t","one_line":"o","points":[{"text":"p","segment_index":99}]}""")
        )

        val summary = UnderstandingService(llm()).summarize(segments("只有一句"))

        assertEquals("p", summary.points[0].text)
        assertNull(summary.points[0].citation)
    }

    @Test
    fun `a missing segment index yields no citation`() = runTest {
        server.enqueue(chatResponse("""{"title":"t","one_line":"o","points":[{"text":"p"}]}"""))

        assertNull(UnderstandingService(llm()).summarize(segments("一句")).points[0].citation)
    }

    @Test
    fun `the scene prompt is appended, never substituted`() = runTest {
        server.enqueue(chatResponse("""{"title":"t","one_line":"o"}"""))
        val scene = Scene.builtIn("customer_call")!!.copy(customPrompt = "重点关注价格异议")

        UnderstandingService(llm()).summarize(segments("一句"), scene)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("重点关注价格异议"), "scene prompt missing")
        assertTrue(body.contains("客户通话"), "scene name missing")
        // The citation contract must survive any scene customisation.
        assertTrue(body.contains("segment_index"), "base prompt was replaced")
    }

    @Test
    fun `classification drops labels the domain does not know`() = runTest {
        server.enqueue(
            chatResponse(
                """{"sentences":[{"segment_index":0,"intent":"COMMITMENT"},
                   {"segment_index":1,"intent":"SOMETHING_NEW"},
                   {"segment_index":42,"intent":"DECISION"}]}"""
            )
        )

        val intents = UnderstandingService(llm()).classify(segments("我周五给你", "随便说说"))

        assertEquals(1, intents.size)
        assertEquals(com.diting.domain.model.Intent.COMMITMENT, intents["s0"])
    }

    @Test
    fun `hotword candidates that change nothing are discarded`() = runTest {
        server.enqueue(
            chatResponse(
                """{"candidates":[{"heard":"欧艾斯艾斯","suggestion":"OSS","segment_index":0},
                   {"heard":"周会","suggestion":"周会"},
                   {"heard":"","suggestion":"X"}]}"""
            )
        )

        val candidates = UnderstandingService(llm())
            .proposeHotwords(segments("欧艾斯艾斯的问题"), knownHotwords = listOf("谛听"))

        assertEquals(1, candidates.size)
        assertEquals("OSS", candidates[0].suggestion)
    }

    @Test
    fun `known hotwords are excluded from the prompt's ask`() = runTest {
        server.enqueue(chatResponse("""{"candidates":[]}"""))

        UnderstandingService(llm()).proposeHotwords(segments("一句"), listOf("谛听", "OSS"))

        assertTrue(server.takeRequest().body.readUtf8().contains("谛听、OSS"))
    }
}
