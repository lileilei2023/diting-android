package com.diting.protocol.farosh

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Data types for the Newsmy Farosh cloud (`newsmy.duiopen.com`).
 *
 * Transcribed from 《Farosh_接口协议.xlsx》. The spreadsheet documents the Java
 * method signature, HTTP verb, URL, and which parameters are query vs header vs
 * body — but not the field-level shape of most request and response bodies. Where
 * a body's fields are not documented, the type here keeps the fields 谛听 actually
 * needs and tolerates everything else (see [FaroshJson]'s `ignoreUnknownKeys`),
 * rather than guessing at a complete schema that would break on first contact.
 *
 * Fields marked "unverified" have not been checked against a live server — this
 * repository has no Farosh credentials.
 */

/**
 * The envelope every business endpoint wraps its payload in
 * (`ResponseEnvelope<T>` in the vendor's client).
 *
 * Field names are the common convention for this shape; if a live response
 * disagrees, this is the first type to correct.
 */
@Serializable
data class FaroshEnvelope<T>(
    val code: Int? = null,
    val message: String? = null,
    val data: T? = null,
) {
    /** Vendors differ on the success code; 0 and 200 are both common. */
    val isSuccess: Boolean get() = code == null || code == 0 || code == 200
}

@Serializable
data class AccessTokenEnvelope(
    @SerialName("accessToken") val accessToken: String? = null,
    @SerialName("refreshToken") val refreshToken: String? = null,
    @SerialName("expiresIn") val expiresIn: Long? = null,
    val userId: String? = null,
)

@Serializable
data class FaroshUser(
    val userId: String? = null,
    val nickname: String? = null,
    val avatar: String? = null,
    val phone: String? = null,
    val email: String? = null,
)

/** One cloud recording. */
@Serializable
data class FaroshRecord(
    val recordId: String? = null,
    val name: String? = null,
    val createTime: Long? = null,
    val duration: Long? = null,
    val deviceType: String? = null,
    val recordType: String? = null,
    /** Object id used by `/audio/stream/v1` and `/audio/wave/v1`. */
    val objectId: String? = null,
    val sectionId: Long? = null,
    val asrStatus: String? = null,
)

@Serializable
data class RecordEnvelope(
    val total: Int = 0,
    val page: Int = 0,
    val size: Int = 0,
    val list: List<FaroshRecord> = emptyList(),
)

/** Full detail, including the transcript. */
@Serializable
data class RecordDetail(
    val recordId: String? = null,
    val name: String? = null,
    val duration: Long? = null,
    val objectId: String? = null,
    val paragraphs: List<FaroshParagraph> = emptyList(),
)

@Serializable
data class FaroshParagraph(
    @SerialName("beginTime") val beginTimeMs: Long = 0,
    @SerialName("endTime") val endTimeMs: Long = 0,
    val text: String = "",
    val speaker: String? = null,
)

@Serializable
data class SummaryMessage(
    val summary: String? = null,
    val keywords: List<String> = emptyList(),
    val todos: List<SummaryTodo> = emptyList(),
    /** Anything the vendor adds that we do not model yet. */
    val extra: JsonElement? = null,
)

@Serializable
data class SummaryTodo(
    val id: Long? = null,
    val recordId: String? = null,
    val content: String? = null,
    val owner: String? = null,
    @SerialName("deadline") val deadlineEpochMs: Long? = null,
    val finished: Boolean = false,
)

@Serializable
data class CreateRecordRes(
    val recordId: String? = null,
    val objectId: String? = null,
    /** Present for OTG / offline flows that need an upload target. */
    val uploadUrl: String? = null,
)

@Serializable
data class RecordSection(
    val sectionId: Long? = null,
    val name: String? = null,
    val order: Int = 0,
)

@Serializable
data class FaroshContact(
    val id: String? = null,
    val name: String? = null,
    val phone: String? = null,
)

@Serializable
data class DeviceUpgradeMessage(
    val version: String? = null,
    val url: String? = null,
    val forced: Boolean = false,
    val description: String? = null,
)

@Serializable
data class OfflineTranslateMessage(
    val progress: Int = 0,
    val status: String? = null,
)

@Serializable
data class VoiceDurationSum(
    val totalDuration: Long = 0,
    val usedDuration: Long = 0,
    val remainDuration: Long = 0,
)

@Serializable
data class BindVerifyResult(
    val bound: Boolean = false,
    val userId: String? = null,
    val message: String? = null,
)

@Serializable
data class VerifyRes(
    val valid: Boolean = false,
    val expireTime: Long? = null,
)

@Serializable
data class UpdateVoiceFileRes(
    val objectId: String? = null,
    val size: Long = 0,
)

@Serializable
data class CooperationMessage(
    val total: Int = 0,
    val list: List<JsonElement> = emptyList(),
)

// -- request bodies -------------------------------------------------------------

@Serializable
data class LoginBody(
    val account: String,
    val password: String? = null,
    val code: String? = null,
)

@Serializable
data class SignupBody(
    val account: String,
    val password: String,
    val code: String? = null,
)

@Serializable
data class VerificationCodeBody(val phone: String, val type: String? = null)

@Serializable
data class PasswordBody(
    @SerialName("oldPwd") val oldPassword: String? = null,
    @SerialName("newPwd") val newPassword: String,
)

@Serializable
data class ForgotPasswordBody(
    val account: String,
    val code: String,
    @SerialName("newPwd") val newPassword: String,
)

@Serializable
data class BindBody(val activationCode: String, val deviceId: String? = null)

@Serializable
data class DevBindBody(
    val deviceId: String,
    val deviceModel: String? = null,
    val activationCode: String? = null,
)

@Serializable
data class BindVerifyParams(val deviceId: String, val deviceModel: String? = null)

@Serializable
data class StartRecordParams(
    val deviceId: String? = null,
    val recordName: String? = null,
    val deviceType: String? = null,
)

@Serializable
data class OnlineFinishParams(val duration: Long? = null)

@Serializable
data class TranslateFinishParams(val duration: Long? = null)

@Serializable
data class MarkRecordParams(@SerialName("time") val timeMs: Long, val note: String? = null)

@Serializable
data class NoteBody(val note: String)

@Serializable
data class SummaryBody(val type: String? = null, val prompt: String? = null)

@Serializable
data class RecordRevise(
    @SerialName("beginTime") val beginTimeMs: Long,
    val text: String,
    val speaker: String? = null,
)

@Serializable
data class DoaBody(val doa: Int? = null, val channels: Int? = null)

@Serializable
data class UpdateVoiceFinishParams(
    val objectId: String? = null,
    val size: Long? = null,
    val duration: Long? = null,
)

@Serializable
data class UnbindDeviceParams(val deviceId: String)

@Serializable
data class DeviceConfigBody(val config: String)

@Serializable
data class FeedbackBody(val content: String, val contact: String? = null)

@Serializable
data class TranslateParam(
    val text: String,
    val from: String? = null,
    val to: String,
)

@Serializable
data class FaroshTranslateResult(val result: String? = null)

@Serializable
data class RecordCategoryBody(val recordIds: List<String>, val sectionId: Long)

@Serializable
data class InviteCooperationBody(val userIds: List<String>, val limit: Int? = null)

@Serializable
data class ChangeLimitBody(val userId: String? = null, val limit: Int)

@Serializable
data class QrLoginBody(val qrCode: String)
