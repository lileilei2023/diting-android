package com.diting.protocol.farosh

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

/**
 * Covers the header conventions and envelope handling from the
 * 「全局配置与鉴权」 sheet, which is where a hand-written client of this API goes
 * wrong. The endpoint URLs are asserted against the spreadsheet's table.
 */
class FaroshClientTest {

    private lateinit var server: MockWebServer
    private val okHttp = OkHttpClient()

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun client(token: String? = "tok-123", deviceModel: String? = null) = FaroshClient(
        okHttp = okHttp,
        tokens = FaroshTokenStore.inMemory(token),
        baseUrl = server.url("/").toString().trimEnd('/'),
        deviceModel = deviceModel,
    )

    private fun ok(data: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"code":0,"message":"ok","data":$data}""")

    // -- global headers -------------------------------------------------------

    @Test
    fun `every request carries requestSourceType 2`() = runTest {
        server.enqueue(ok("""{"userId":"u1"}"""))

        client().accountInfo()

        assertEquals("2", server.takeRequest().getHeader("requestSourceType"))
    }

    @Test
    fun `authenticated requests carry the AIMT-TOKEN header`() = runTest {
        server.enqueue(ok("""{"userId":"u1"}"""))

        client().accountInfo()

        assertEquals("tok-123", server.takeRequest().getHeader("AIMT-TOKEN"))
    }

    @Test
    fun `login does not require a token and stores the one it receives`() = runTest {
        server.enqueue(ok("""{"accessToken":"new-token","userId":"u1"}"""))
        val store = FaroshTokenStore.inMemory(null)
        val fresh = FaroshClient(okHttp, store, server.url("/").toString().trimEnd('/'))

        val result = fresh.login("13800000000", password = "pw")

        assertNull(server.takeRequest().getHeader("AIMT-TOKEN"))
        assertEquals("new-token", result.accessToken)
        assertEquals("new-token", store.accessToken)
        assertTrue(fresh.isAuthenticated)
    }

    @Test
    fun `an authenticated call without a token fails before touching the network`() = runTest {
        val anonymous = client(token = null)

        assertThrows<FaroshException> { anonymous.accountInfo() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `deviceModel is sent when configured`() = runTest {
        server.enqueue(ok("""{"userId":"u1"}"""))

        client(deviceModel = "FAROSH-A1").accountInfo()

        assertEquals("FAROSH-A1", server.takeRequest().getHeader("deviceModel"))
    }

    // -- recordId is a header, not a query parameter --------------------------

    @Test
    fun `recordId travels as a header`() = runTest {
        // The spreadsheet is explicit: "recordId: <录音记录ID> (大量录音相关接口靠它定位)".
        // Sending it as a query parameter silently returns the wrong record.
        server.enqueue(ok("""{"recordId":"r1","name":"周会"}"""))

        client().recordDetail("r1")

        val request = server.takeRequest()
        assertEquals("r1", request.getHeader("recordId"))
        assertEquals("/business/record/detail", request.path)
    }

    @Test
    fun `rename sends the name as a query parameter and the record as a header`() = runTest {
        server.enqueue(ok(""""ok""""))

        client().renameRecord("r1", "Q4 定价周会")

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("r1", request.getHeader("recordId"))
        assertTrue(request.path!!.startsWith("/business/record/name?name="))
    }

    // -- envelope handling ----------------------------------------------------

    @Test
    fun `a business error code becomes an exception, not a null payload`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"code":40101,"message":"token 已过期","data":null}""")
        )

        val error = assertThrows<FaroshException> { client().accountInfo() }

        assertEquals(40101, error.code)
        assertEquals("token 已过期", error.message)
    }

    @Test
    fun `an HTTP error is reported with its status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val error = assertThrows<FaroshException> { client().accountInfo() }

        assertEquals(500, error.status)
    }

    @Test
    fun `unknown envelope fields are tolerated`() = runTest {
        // The vendor's envelope carries fields this client does not model.
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"message":"ok","traceId":"abc","ts":1,"data":{"userId":"u1","extra":9}}"""
            )
        )

        assertEquals("u1", client().accountInfo().userId)
    }

    // -- endpoint URLs match the spreadsheet ----------------------------------

    @Test
    fun `record list, summary and todo endpoints match the documented paths`() = runTest {
        server.enqueue(ok("""{"total":0,"list":[]}"""))
        client().recordList(mapOf("page" to "1"))
        assertTrue(server.takeRequest().path!!.startsWith("/business/record/list"))

        server.enqueue(ok("""{"summary":"…","keywords":[],"todos":[]}"""))
        client().getRecordSummary("r1")
        assertEquals("/business/record/summary", server.takeRequest().path)

        server.enqueue(ok("""[]"""))
        client().getTodos("2026-09-01", "2026-09-07")
        assertTrue(server.takeRequest().path!!.startsWith("/business/todo/list?beginDate="))
    }

    @Test
    fun `creating an online record passes deviceType and recordName as query parameters`() =
        runTest {
            server.enqueue(ok("""{"recordId":"r9","objectId":"o9"}"""))

            val created = client().createOnlineRecord("MR20", "周会")

            val path = server.takeRequest().path!!
            assertTrue(path.startsWith("/business/record/online?"))
            assertTrue(path.contains("deviceType=MR20"))
            assertEquals("r9", created.recordId)
        }

    @Test
    fun `delete uses the DELETE verb`() = runTest {
        server.enqueue(ok(""""deleted""""))

        client().deleteRecord("r1")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("r1", request.getHeader("recordId"))
    }

    // -- non-enveloped endpoints ----------------------------------------------

    @Test
    fun `the waveform endpoint returns a bare array, not an envelope`() = runTest {
        server.enqueue(MockResponse().setBody("[0,3,7,2]"))

        assertEquals(listOf(0, 3, 7, 2), client().waveform("obj-1"))
    }

    @Test
    fun `translate returns a bare result object`() = runTest {
        server.enqueue(MockResponse().setBody("""{"result":"Hello"}"""))

        val result = client().translate(TranslateParam(text = "你好", to = "en"))

        assertEquals("Hello", result.result)
    }

    @Test
    fun `the audio stream URL is built for the player rather than fetched`() {
        val url = client().audioStreamUrl("obj-1")

        assertTrue(url.contains("/audio/stream/v1"))
        assertTrue(url.contains("objectId=obj-1"))
    }

    // -- upload ---------------------------------------------------------------

    @Test
    fun `voice chunks are posted as raw bytes against the objectId`() = runTest {
        server.enqueue(ok("""{"objectId":"obj-1","size":1024}"""))

        val result = client().uploadVoiceChunk("obj-1", ByteArray(1024) { 7 })

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("objectId=obj-1"))
        assertEquals(1024, request.bodySize.toInt())
        assertEquals(1024L, result.size)
    }

    // -- config constants -----------------------------------------------------

    @Test
    fun `the documented non-REST channels are recorded`() {
        assertEquals("tcp://iot-hub.duiopen.com:1883", FaroshConfig.MQTT_URL)
        assertEquals("service/v1/u42", FaroshConfig.mqttPublishTopic("u42"))
        assertEquals("wss://newsmy.duiopen.com", FaroshConfig.WEBSOCKET_URL)
        assertEquals("00001101-0000-1000-8000-00805F9B34FB", FaroshConfig.SPP_UUID)
    }
}
