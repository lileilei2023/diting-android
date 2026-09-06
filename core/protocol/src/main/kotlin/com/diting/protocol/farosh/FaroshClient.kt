package com.diting.protocol.farosh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

val FaroshJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
}

/** Fixed configuration from the 「全局配置与鉴权」 sheet. */
object FaroshConfig {
    const val BASE_URL = "https://newsmy.duiopen.com"

    /** Bearer-equivalent header; issued by `/business/auth/account/login`. */
    const val HEADER_TOKEN = "AIMT-TOKEN"

    /** Constant on every request per the spec. */
    const val HEADER_SOURCE_TYPE = "requestSourceType"
    const val SOURCE_TYPE_VALUE = "2"

    /** Optional device model header. */
    const val HEADER_DEVICE_MODEL = "deviceModel"

    /** Business header a large number of record endpoints key off. */
    const val HEADER_RECORD_ID = "recordId"

    /** Non-REST channels, for reference. */
    const val MQTT_URL = "tcp://iot-hub.duiopen.com:1883"
    const val WEBSOCKET_URL = "wss://newsmy.duiopen.com"

    /** MQTT publish topic; `<userId>` is substituted at connect time. */
    fun mqttPublishTopic(userId: String) = "service/v1/$userId"

    /**
     * Standard SPP UUID. The spreadsheet notes Farosh uses plain RFCOMM with no
     * custom framing — unlike the MR20, which has a documented command grammar.
     * The device-side frame format is **not** in the supplied protocol document,
     * so 谛听 talks to Farosh through this cloud API rather than over Bluetooth.
     */
    const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
}

class FaroshException(
    val status: Int?,
    val code: Int?,
    message: String,
) : Exception(message)

/** Holds the session token. Swap the implementation to persist it. */
interface FaroshTokenStore {
    var accessToken: String?

    companion object {
        fun inMemory(initial: String? = null): FaroshTokenStore = object : FaroshTokenStore {
            override var accessToken: String? = initial
        }
    }
}

/**
 * Client for the Farosh cloud API.
 *
 * Covers the endpoint table in 《Farosh_接口协议.xlsx》. Three conventions from the
 * 「全局配置与鉴权」 sheet are applied centrally rather than per call, because
 * forgetting any of them fails in a way that is tedious to diagnose:
 *
 *  * `AIMT-TOKEN` on every authenticated request;
 *  * `requestSourceType: 2` on *every* request;
 *  * `recordId` as a **header**, not a query parameter, on the many record
 *    endpoints that locate their subject that way.
 *
 * **Unverified against a live server.** This repository has no Farosh account, so
 * request shapes come from the spreadsheet alone. The envelope's success code and
 * the undocumented request-body fields are the two things most likely to need
 * correcting on first contact.
 */
class FaroshClient(
    private val okHttp: OkHttpClient,
    private val tokens: FaroshTokenStore = FaroshTokenStore.inMemory(),
    private val baseUrl: String = FaroshConfig.BASE_URL,
    private val deviceModel: String? = null,
) {

    val isAuthenticated: Boolean get() = !tokens.accessToken.isNullOrBlank()

    // ---- account & auth ----------------------------------------------------

    suspend fun login(account: String, password: String? = null, code: String? = null):
        AccessTokenEnvelope = post(
        "/business/auth/account/login",
        LoginBody(account, password, code),
        LoginBody.serializer(),
        AccessTokenEnvelope.serializer(),
        authenticated = false,
    ).also { tokens.accessToken = it.accessToken }

    suspend fun signup(account: String, password: String, code: String? = null): JsonElement =
        post(
            "/business/auth/account/register",
            SignupBody(account, password, code),
            SignupBody.serializer(),
            JsonElement.serializer(),
            authenticated = false,
        )

    suspend fun sendVerificationCode(phone: String, type: String? = null): String = post(
        "/business/auth/account/login/send/sms",
        VerificationCodeBody(phone, type),
        VerificationCodeBody.serializer(),
        String.serializer(),
        authenticated = false,
    )

    suspend fun forgotPassword(account: String, code: String, newPassword: String):
        AccessTokenEnvelope = post(
        "/business/auth/account/login/forget/pwd",
        ForgotPasswordBody(account, code, newPassword),
        ForgotPasswordBody.serializer(),
        AccessTokenEnvelope.serializer(),
        authenticated = false,
    ).also { tokens.accessToken = it.accessToken }

    suspend fun changePassword(oldPassword: String, newPassword: String): JsonElement = put(
        "/business/account/pwd/change",
        PasswordBody(oldPassword, newPassword),
        PasswordBody.serializer(),
        JsonElement.serializer(),
    )

    suspend fun setPassword(newPassword: String): JsonElement = put(
        "/business/account/pwd/set",
        PasswordBody(newPassword = newPassword),
        PasswordBody.serializer(),
        JsonElement.serializer(),
    )

    suspend fun qrLogin(qrCode: String): String = post(
        "/business/account/login/qr/bind",
        QrLoginBody(qrCode),
        QrLoginBody.serializer(),
        String.serializer(),
    )

    suspend fun accountInfo(): FaroshUser =
        get("/business/account/info", FaroshUser.serializer())

    suspend fun getUserInfo(): FaroshUser =
        get("/business/user/info/get", FaroshUser.serializer())

    suspend fun updateUserInfo(user: FaroshUser): String = put(
        "/business/user/info/update",
        user,
        FaroshUser.serializer(),
        String.serializer(),
    )

    suspend fun voiceDurationSum(): VoiceDurationSum =
        get("/business/user/voice/info", VoiceDurationSum.serializer())

    suspend fun submitFeedback(content: String, contact: String? = null): String = post(
        "/business/user/feedback",
        FeedbackBody(content, contact),
        FeedbackBody.serializer(),
        String.serializer(),
    )

    // ---- activation & device binding ---------------------------------------

    suspend fun activationBind(activationCode: String, deviceId: String? = null): String = post(
        "/business/activationCode/app/bind",
        BindBody(activationCode, deviceId),
        BindBody.serializer(),
        String.serializer(),
    )

    suspend fun activationVerify(): VerifyRes = post(
        "/business/activationCode/app/verify",
        body = null,
        bodySerializer = null,
        resultSerializer = VerifyRes.serializer(),
    )

    suspend fun deviceBind(body: DevBindBody): String = post(
        "/business/activationCode/app/dev/bind",
        body,
        DevBindBody.serializer(),
        String.serializer(),
    )

    suspend fun deviceBindVerify(params: BindVerifyParams): BindVerifyResult = post(
        "/business/activationCode/app/dev/bind/verify",
        params,
        BindVerifyParams.serializer(),
        BindVerifyResult.serializer(),
    )

    suspend fun unbindDevice(deviceId: String): String = post(
        "/business/user/device/unbind",
        UnbindDeviceParams(deviceId),
        UnbindDeviceParams.serializer(),
        String.serializer(),
    )

    suspend fun getDeviceConfig(): String =
        get("/business/user/device/config", String.serializer())

    suspend fun setDeviceConfig(config: String): String = put(
        "/business/user/device/config",
        DeviceConfigBody(config),
        DeviceConfigBody.serializer(),
        String.serializer(),
    )

    suspend fun devicePolling(deviceId: String, version: String, modelName: String):
        DeviceUpgradeMessage = get(
        "/business/ota/app/device/optional/polling",
        DeviceUpgradeMessage.serializer(),
        query = mapOf("deviceId" to deviceId, "version" to version, "modelName" to modelName),
    )

    // ---- recordings --------------------------------------------------------

    suspend fun recordList(query: Map<String, String> = emptyMap()): RecordEnvelope =
        get("/business/record/list", RecordEnvelope.serializer(), query)

    suspend fun recordSearch(query: Map<String, String>): RecordEnvelope =
        get("/business/record/search", RecordEnvelope.serializer(), query)

    suspend fun recordDetail(recordId: String): RecordDetail =
        get("/business/record/detail", RecordDetail.serializer(), recordId = recordId)

    suspend fun translateDetail(recordId: String): RecordDetail =
        get("/business/record/translate/detail", RecordDetail.serializer(), recordId = recordId)

    suspend fun deleteRecord(recordId: String): String =
        delete("/business/record", String.serializer(), recordId = recordId)

    suspend fun renameRecord(recordId: String, name: String): String = put(
        "/business/record/name",
        body = null,
        bodySerializer = null,
        resultSerializer = String.serializer(),
        query = mapOf("name" to name),
        recordId = recordId,
    )

    suspend fun getRecordNote(recordId: String): String =
        get("/business/record/note", String.serializer(), recordId = recordId)

    suspend fun setRecordNote(recordId: String, note: String): String = post(
        "/business/record/note",
        NoteBody(note),
        NoteBody.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun getRecordSummary(recordId: String): SummaryMessage =
        get("/business/record/summary", SummaryMessage.serializer(), recordId = recordId)

    suspend fun requestRecordSummary(recordId: String, body: SummaryBody): String = post(
        "/business/record/summary",
        body,
        SummaryBody.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun reviseRecord(recordId: String, revise: RecordRevise): String = put(
        "/business/record/revise",
        revise,
        RecordRevise.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun setSpeaker(recordId: String, speaker: String): String = put(
        "/business/record/speaker",
        body = null,
        bodySerializer = null,
        resultSerializer = String.serializer(),
        query = mapOf("speaker" to speaker),
        recordId = recordId,
    )

    suspend fun shareEncrypt(recordId: String, timeSeconds: Long): String = get(
        "/business/record/share/encrypt",
        String.serializer(),
        query = mapOf("time" to timeSeconds.toString()),
        recordId = recordId,
    )

    suspend fun uploadRecordPicture(file: File): String = multipart(
        "/business/record/picture",
        file,
        "file",
        String.serializer(),
    )

    // ---- record creation & lifecycle ---------------------------------------

    suspend fun createOnlineRecord(deviceType: String, recordName: String): CreateRecordRes = get(
        "/business/record/online",
        CreateRecordRes.serializer(),
        query = mapOf("deviceType" to deviceType, "recordName" to recordName),
    )

    suspend fun createOfflineRecord(
        deviceType: String,
        recordName: String,
        recordType: String,
    ): CreateRecordRes = get(
        "/business/record/offline",
        CreateRecordRes.serializer(),
        query = mapOf(
            "deviceType" to deviceType,
            "recordName" to recordName,
            "recordType" to recordType,
        ),
    )

    suspend fun createOtgOfflineRecord(deviceType: String, recordName: String): CreateRecordRes =
        get(
            "/business/record/offline/otg",
            CreateRecordRes.serializer(),
            query = mapOf("deviceType" to deviceType, "recordName" to recordName),
        )

    suspend fun createTranslateRecord(deviceType: String, recordName: String): CreateRecordRes =
        get(
            "/business/record/translate",
            CreateRecordRes.serializer(),
            query = mapOf("deviceType" to deviceType, "recordName" to recordName),
        )

    suspend fun onlineFinish(recordId: String, params: OnlineFinishParams): String = post(
        "/business/record/online/finish",
        params,
        OnlineFinishParams.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun onlineToOffline(recordId: String, deviceId: String, taskType: String): String =
        post(
            "/business/record/online/offline",
            body = null,
            bodySerializer = null,
            resultSerializer = String.serializer(),
            query = mapOf("deviceId" to deviceId, "taskType" to taskType),
            recordId = recordId,
        )

    suspend fun manualCheck(recordId: String): Boolean = get(
        "/business/record/online/offline/man/check",
        Boolean.serializer(),
        recordId = recordId,
    )

    suspend fun finishTranslateRecord(recordId: String, params: TranslateFinishParams): String =
        post(
            "/business/record/translate/finish",
            params,
            TranslateFinishParams.serializer(),
            String.serializer(),
            recordId = recordId,
        )

    // ---- offline ASR -------------------------------------------------------

    suspend fun startOfflineAsr(recordId: String, doa: DoaBody): String = post(
        "/business/record/offline/start",
        doa,
        DoaBody.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun startOtgOfflineAsr(recordId: String, doa: DoaBody): String = post(
        "/business/record/offline/otg/start",
        doa,
        DoaBody.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun offlineAsrStatus(recordId: String): String =
        get("/asr/offline/status", String.serializer(), recordId = recordId)

    suspend fun offlineTranslateProgress(recordId: String): OfflineTranslateMessage =
        get("/asr/offline/progress", OfflineTranslateMessage.serializer(), recordId = recordId)

    // ---- audio upload / playback -------------------------------------------

    /** Streams raw audio bytes to `/audio/stream/v1?objectId=…`. */
    suspend fun uploadVoiceChunk(objectId: String, bytes: ByteArray): UpdateVoiceFileRes {
        val url = urlFor("/audio/stream/v1", mapOf("objectId" to objectId))
        val request = requestBuilder(url, recordId = null)
            .post(bytes.toRequestBody(OCTET_STREAM))
            .build()
        return decode(execute(request), UpdateVoiceFileRes.serializer())
    }

    suspend fun uploadVoiceFinish(
        recordId: String,
        deviceModel: String,
        params: UpdateVoiceFinishParams,
    ): String = post(
        "/business/record/offline/upload/finish",
        params,
        UpdateVoiceFinishParams.serializer(),
        String.serializer(),
        query = mapOf("recordId" to recordId, "deviceModel" to deviceModel),
    )

    /** `GET /audio/wave/v1` returns a bare integer array, not an envelope. */
    suspend fun waveform(objectId: String): List<Int> {
        val url = urlFor("/audio/wave/v1", mapOf("objectId" to objectId))
        val body = execute(requestBuilder(url, null).get().build())
        return FaroshJson.decodeFromString(ListSerializer(Int.serializer()), body)
    }

    /** Playback URL for the media player; the server streams it directly. */
    fun audioStreamUrl(objectId: String): String =
        urlFor("/audio/stream/v1", mapOf("objectId" to objectId)).toString()

    // ---- to-dos ------------------------------------------------------------

    suspend fun getTodos(beginDate: String, endDate: String): List<SummaryTodo> = get(
        "/business/todo/list",
        ListSerializer(SummaryTodo.serializer()),
        query = mapOf("beginDate" to beginDate, "endDate" to endDate),
    )

    suspend fun getTodo(id: String): SummaryTodo =
        get("/business/todo", SummaryTodo.serializer(), query = mapOf("id" to id))

    suspend fun createTodo(todo: SummaryTodo): String = post(
        "/business/todo",
        todo,
        SummaryTodo.serializer(),
        String.serializer(),
    )

    suspend fun updateTodos(todos: List<SummaryTodo>): String = put(
        "/business/todo/batch",
        todos,
        ListSerializer(SummaryTodo.serializer()),
        String.serializer(),
    )

    suspend fun deleteTodo(id: String): String =
        delete("/business/todo", String.serializer(), query = mapOf("id" to id))

    // ---- sections (folders) ------------------------------------------------

    suspend fun listSections(): List<RecordSection> =
        get("/business/record/section", ListSerializer(RecordSection.serializer()))

    suspend fun createSection(section: RecordSection): Long = post(
        "/business/record/section",
        section,
        RecordSection.serializer(),
        Long.serializer(),
    )

    suspend fun renameSection(section: RecordSection): String = put(
        "/business/record/section",
        section,
        RecordSection.serializer(),
        String.serializer(),
    )

    suspend fun deleteSection(sectionId: Long): String = delete(
        "/business/record/section",
        String.serializer(),
        query = mapOf("sectionId" to sectionId.toString()),
    )

    suspend fun moveRecordToSection(recordId: String, sectionId: Long): String = put(
        "/business/record/section/move",
        body = null,
        bodySerializer = null,
        resultSerializer = String.serializer(),
        query = mapOf("sectionId" to sectionId.toString()),
        recordId = recordId,
    )

    suspend fun moveRecordsToSection(body: RecordCategoryBody): String = put(
        "/business/record/section/batch/move",
        body,
        RecordCategoryBody.serializer(),
        String.serializer(),
    )

    suspend fun reorderSections(order: List<Long>): String = put(
        "/business/record/section/order",
        order,
        ListSerializer(Long.serializer()),
        String.serializer(),
    )

    // ---- realtime control (MQTT-backed REST) -------------------------------

    suspend fun startRecord(params: StartRecordParams): String = post(
        "/business/mqtt/record/start",
        params,
        StartRecordParams.serializer(),
        String.serializer(),
    )

    suspend fun pauseRecord(recordId: String, params: StartRecordParams): String = post(
        "/business/mqtt/record/pause",
        params,
        StartRecordParams.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun continueRecord(recordId: String, params: StartRecordParams): String = post(
        "/business/mqtt/record/continue",
        params,
        StartRecordParams.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun restartRecord(recordId: String, params: StartRecordParams): String = post(
        "/business/mqtt/record/restart",
        params,
        StartRecordParams.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun finishRecord(recordId: String, params: StartRecordParams): String = post(
        "/business/mqtt/record/finish",
        params,
        StartRecordParams.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun markRecord(recordId: String, params: MarkRecordParams): String = post(
        "/business/mqtt/record/mark",
        params,
        MarkRecordParams.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    // ---- contacts & translation --------------------------------------------

    suspend fun getContacts(name: String? = null): List<FaroshContact> = get(
        "/business/contact/list",
        ListSerializer(FaroshContact.serializer()),
        query = name?.let { mapOf("name" to it) } ?: emptyMap(),
    )

    suspend fun updateContacts(names: List<String>): String = post(
        "/business/contact/list",
        names,
        ListSerializer(String.serializer()),
        String.serializer(),
    )

    /** Returns a bare result object, not an envelope. */
    suspend fun translate(param: TranslateParam): FaroshTranslateResult {
        val url = urlFor("/business/translation/translate", emptyMap())
        val request = requestBuilder(url, null)
            .post(
                FaroshJson.encodeToString(TranslateParam.serializer(), param)
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()
        return FaroshJson.decodeFromString(
            FaroshTranslateResult.serializer(),
            execute(request),
        )
    }

    // ---- collaboration -----------------------------------------------------

    suspend fun cooperationList(recordId: String, page: Int, size: Int): CooperationMessage = get(
        "/business/cooperate/user/list",
        CooperationMessage.serializer(),
        query = mapOf("page" to page.toString(), "size" to size.toString()),
        recordId = recordId,
    )

    suspend fun recentCooperationList(recordId: String, page: Int, size: Int): CooperationMessage =
        get(
            "/business/cooperate/user/recent/list",
            CooperationMessage.serializer(),
            query = mapOf("page" to page.toString(), "size" to size.toString()),
            recordId = recordId,
        )

    suspend fun searchCooperation(recordId: String, key: String): CooperationMessage = get(
        "/business/cooperate/user/search",
        CooperationMessage.serializer(),
        query = mapOf("key" to key),
        recordId = recordId,
    )

    suspend fun inviteCooperation(recordId: String, body: InviteCooperationBody): String = post(
        "/business/cooperate/user/invite",
        body,
        InviteCooperationBody.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun addCooperation(recordId: String, body: ChangeLimitBody): String = post(
        "/business/cooperate/user",
        body,
        ChangeLimitBody.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun changeCooperationLimit(recordId: String, body: ChangeLimitBody): String = put(
        "/business/cooperate/user",
        body,
        ChangeLimitBody.serializer(),
        String.serializer(),
        recordId = recordId,
    )

    suspend fun deleteCooperation(recordId: String, userId: String): String = delete(
        "/business/cooperate/user",
        String.serializer(),
        query = mapOf("userId" to userId),
        recordId = recordId,
    )

    suspend fun exitCooperation(recordId: String): String =
        delete("/business/cooperate/user/leave", String.serializer(), recordId = recordId)

    // ---- plumbing ----------------------------------------------------------

    private fun urlFor(path: String, query: Map<String, String>): HttpUrl =
        (baseUrl.trimEnd('/') + path).toHttpUrl().newBuilder()
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()

    /**
     * Applies the three global header conventions. [recordId] goes in a header
     * rather than the query string — the spreadsheet is explicit about that, and
     * putting it in the query silently returns the wrong record.
     */
    private fun requestBuilder(
        url: HttpUrl,
        recordId: String?,
        authenticated: Boolean = true,
    ): Request.Builder = Request.Builder()
        .url(url)
        .header(FaroshConfig.HEADER_SOURCE_TYPE, FaroshConfig.SOURCE_TYPE_VALUE)
        .apply {
            if (authenticated) {
                val token = tokens.accessToken
                    ?: throw FaroshException(null, null, "尚未登录 Farosh 账号")
                header(FaroshConfig.HEADER_TOKEN, token)
            }
            recordId?.let { header(FaroshConfig.HEADER_RECORD_ID, it) }
            deviceModel?.let { header(FaroshConfig.HEADER_DEVICE_MODEL, it) }
        }

    private suspend fun <R> get(
        path: String,
        resultSerializer: KSerializer<R>,
        query: Map<String, String> = emptyMap(),
        recordId: String? = null,
    ): R = decode(
        execute(requestBuilder(urlFor(path, query), recordId).get().build()),
        resultSerializer,
    )

    private suspend fun <B, R> post(
        path: String,
        body: B?,
        bodySerializer: KSerializer<B>?,
        resultSerializer: KSerializer<R>,
        query: Map<String, String> = emptyMap(),
        recordId: String? = null,
        authenticated: Boolean = true,
    ): R = decode(
        execute(
            requestBuilder(urlFor(path, query), recordId, authenticated)
                .post(jsonBody(body, bodySerializer))
                .build()
        ),
        resultSerializer,
    )

    private suspend fun <B, R> put(
        path: String,
        body: B?,
        bodySerializer: KSerializer<B>?,
        resultSerializer: KSerializer<R>,
        query: Map<String, String> = emptyMap(),
        recordId: String? = null,
    ): R = decode(
        execute(
            requestBuilder(urlFor(path, query), recordId)
                .put(jsonBody(body, bodySerializer))
                .build()
        ),
        resultSerializer,
    )

    private suspend fun <R> delete(
        path: String,
        resultSerializer: KSerializer<R>,
        query: Map<String, String> = emptyMap(),
        recordId: String? = null,
    ): R = decode(
        execute(requestBuilder(urlFor(path, query), recordId).delete().build()),
        resultSerializer,
    )

    private suspend fun <R> multipart(
        path: String,
        file: File,
        partName: String,
        resultSerializer: KSerializer<R>,
    ): R {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(partName, file.name, file.asRequestBody(OCTET_STREAM))
            .build()
        return decode(
            execute(requestBuilder(urlFor(path, emptyMap()), null).post(body).build()),
            resultSerializer,
        )
    }

    private fun <B> jsonBody(body: B?, serializer: KSerializer<B>?): RequestBody =
        if (body != null && serializer != null) {
            FaroshJson.encodeToString(serializer, body).toRequestBody(JSON_MEDIA_TYPE)
        } else {
            EMPTY_JSON.toRequestBody(JSON_MEDIA_TYPE)
        }

    /** Unwraps [FaroshEnvelope] and turns a business error code into an exception. */
    private fun <R> decode(raw: String, serializer: KSerializer<R>): R {
        val envelope = runCatching {
            FaroshJson.decodeFromString(FaroshEnvelope.serializer(serializer), raw)
        }.getOrElse {
            throw FaroshException(null, null, "无法解析响应：${raw.take(300)}")
        }

        if (!envelope.isSuccess) {
            throw FaroshException(
                status = null,
                code = envelope.code,
                message = envelope.message ?: "Farosh 返回错误码 ${envelope.code}",
            )
        }
        return envelope.data
            ?: throw FaroshException(null, envelope.code, "响应缺少 data：${raw.take(300)}")
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = suspendCoroutine<Response> { continuation ->
            okHttp.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) =
                    continuation.resumeWithException(e)

                override fun onResponse(call: okhttp3.Call, response: Response) =
                    continuation.resume(response)
            })
        }
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw FaroshException(it.code, null, "HTTP ${it.code}：${body.take(300)}")
            }
            body
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        const val EMPTY_JSON = "{}"
    }
}
