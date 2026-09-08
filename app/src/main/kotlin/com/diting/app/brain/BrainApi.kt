package com.diting.app.brain

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * REST client for the brain (neuroagi-core-v2 with the Echo extensions).
 *
 * Base URL and bearer token are read from [BrainStore] on every call, so a
 * login or a server change takes effect immediately. Any 401 raises
 * [AuthExpiredException]; the caller clears the token and the UI drops back to
 * the login card, which is how the Web console behaves too.
 */
@Singleton
class BrainApi @Inject constructor(
    private val store: BrainStore,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val TAG = "BrainApi"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(path: String) = store.current.baseUrl.trimEnd('/') + path

    private fun Request.Builder.authed(): Request.Builder = apply {
        store.current.token?.let { header("Authorization", "Bearer $it") }
    }

    private suspend fun post(path: String, body: JsonObject, authed: Boolean = true): JsonObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url(path))
                .post(body.toString().toRequestBody(jsonType))
                .apply { if (authed) authed() }
                .build()
            Log.i(TAG, "POST $path (${body.toString().length} bytes)")
            val resp = try {
                http.newCall(req).execute()
            } catch (e: Exception) {
                Log.w(TAG, "POST $path failed: ${e.message}")
                throw e
            }
            resp.use {
                val text = it.body?.string() ?: ""
                Log.i(TAG, "POST $path -> ${it.code}")
                if (it.code == 401) throw AuthExpiredException()
                if (!it.isSuccessful) throw BrainHttpException(it.code, text.take(500))
                json.parseToJsonElement(text.ifBlank { "{}" }).jsonObject
            }
        }

    private suspend fun getRaw(path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url(path)).authed().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 401) throw AuthExpiredException()
            if (!resp.isSuccessful) throw BrainHttpException(resp.code, text.take(500))
            text
        }
    }

    private suspend fun get(path: String): JsonObject =
        json.parseToJsonElement(getRaw(path).ifBlank { "{}" }).jsonObject

    // ---- account -----------------------------------------------------------

    suspend fun login(phone: String, password: String): AuthResult {
        val r = post("/auth/login", buildJsonObject {
            put("phone", phone); put("password", password)
        }, authed = false)
        return json.decodeFromJsonElement(AuthResult.serializer(), r).also(store::setAuth)
    }

    suspend fun register(phone: String, password: String, nick: String?, adoptCode: String?): AuthResult {
        val r = post("/auth/register", buildJsonObject {
            put("phone", phone); put("password", password)
            nick?.takeIf { it.isNotBlank() }?.let { put("nick", it) }
            adoptCode?.takeIf { it.isNotBlank() }?.let { put("adopt_code", it) }
        }, authed = false)
        return json.decodeFromJsonElement(AuthResult.serializer(), r).also(store::setAuth)
    }

    suspend fun me(): Me = json.decodeFromJsonElement(Me.serializer(), get("/auth/me"))

    suspend fun logout() {
        runCatching { post("/auth/logout", buildJsonObject { }) }
        store.clearAuth()
    }

    /** Health without auth: is there a brain at this URL at all? */
    suspend fun health(): Boolean = runCatching {
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url("/health")).build()).execute()
                .use { it.isSuccessful }
        }
    }.getOrDefault(false)

    /** Auth probe, same as the Web console (`/stats` is the cheapest authed call). */
    suspend fun probe(): Boolean = runCatching { get("/stats"); true }.getOrElse {
        if (it is AuthExpiredException) throw it
        false
    }

    // ---- device adoption ---------------------------------------------------

    /**
     * Registers `device:pendant-<sn>` and mints its handshake key.
     *
     * Registering again re-mints: the previous key (for example one held by an
     * older bridge app) stops working the moment this returns.
     */
    suspend fun mintDeviceKey(sn: String): String {
        val r = post("/capabilities/register", buildJsonObject {
            put("name", "device:pendant-$sn")
            put("mint_key", true)
            put("kind", "local")
            put("manifest", buildJsonObject {
                put("description", "谛听录音卡 $sn（Android）")
                put("device", true)
            })
        })
        return r["key"]?.jsonPrimitive?.content
            ?: throw BrainHttpException(500, "no key in register response: $r")
    }

    // ---- upload ------------------------------------------------------------

    /** Transcribe only (`store_raw=false, extract=false`); nothing is stored. */
    suspend fun transcribe(wav: File, context: String = ""): List<Turn> {
        val b64 = withContext(Dispatchers.IO) { Base64.encodeToString(wav.readBytes(), Base64.NO_WRAP) }
        val r = post("/upload", buildJsonObject {
            put("kind", "audio"); put("audio_base64", b64); put("format", "wav")
            put("store_raw", false); put("extract", false)
            if (context.isNotBlank()) put("context", context.takeLast(400))
        })
        val lines = r["transcript"]?.jsonArray ?: return emptyList()
        return lines.mapNotNull { el ->
            val line = el.jsonPrimitive.content.removePrefix("⚠️ ").trim()
            if (line.isBlank()) return@mapNotNull null
            val i = line.indexOf('：')
            if (i > 0) Turn(line.substring(0, i), line.substring(i + 1))
            else Turn("说话人1", line)
        }.filter { it.text.isNotBlank() }
    }

    /**
     * Full pipeline: ASR → store raw → extract facts/todos → dispatch.
     *
     * @param atEpochSec when the recording was made. The server stamps ingest
     *   time otherwise, and a backlog synced tonight would all land on "today".
     */
    suspend fun uploadFull(audio: File, format: String, atEpochSec: Long): UploadReport {
        val b64 = withContext(Dispatchers.IO) { Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP) }
        val r = post("/upload", buildJsonObject {
            put("kind", "audio"); put("audio_base64", b64); put("format", format)
            put("filename", audio.name); put("at", atEpochSec)
            put("store_raw", true); put("extract", true)
        })
        return parseUploadReport(r)
    }

    /**
     * Hand-parsed on purpose: the report's optional sections (`facts`, `items`)
     * vary in shape between server versions, and a strict decoder once turned a
     * successful 40-minute upload into a "failed" one over a nested array.
     */
    private fun parseUploadReport(r: JsonObject): UploadReport {
        fun JsonElement?.asText(): String = when (this) {
            null, is JsonNull -> ""
            is JsonPrimitive -> content
            is JsonArray -> mapNotNull { it.asText().takeIf(String::isNotBlank) }.joinToString(" · ")
            is JsonObject -> (this["text"] ?: this["fact"] ?: this["value"]).asText()
                .ifBlank { values.joinToString(" · ") { it.asText() } }
        }
        val items = (r["items"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            UploadAction(
                owner = o["owner"].asText(),
                task = o["task"].asText().ifBlank { o["title"].asText() },
                dueDays = (o["due_days"] as? JsonPrimitive)?.content?.toIntOrNull(),
            )
        } ?: emptyList()
        return UploadReport(
            frames = (r["frames"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            summary = r["summary"].asText(),
            title = r["title"].asText(),
            factsNew = (r["facts_new"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            transcript = (r["transcript"] as? JsonArray)?.map { it.asText() }?.filter { it.isNotBlank() } ?: emptyList(),
            items = items,
            facts = (r["facts"] as? JsonArray)?.map { it.asText() }?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }

    // ---- read side (今日 / 待办 / 问一问) -----------------------------------

    private fun JsonObject.str(vararg keys: String): String {
        for (k in keys) {
            val p = this[k] as? JsonPrimitive ?: continue
            if (p.isString && p.content.isNotBlank()) return p.content
        }
        return ""
    }

    private fun memView(o: JsonObject): MemView {
        val body = o["body"] as? JsonObject ?: JsonObject(emptyMap())
        return MemView(
            id = o["id"]?.jsonPrimitive?.long ?: -1,
            kind = o.str("kind"),
            text = body.str("text", "summary", "value", "title"),
            title = body.str("title"),
            speaker = body.str("speaker"),
            category = body.str("category"),
            tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
            happenedAt = o["happened_at"]?.jsonPrimitive?.doubleOrNull
                ?: o["created_at"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }

    suspend fun memoriesSince(sinceEpochSec: Long, limit: Int = 200): List<MemView> =
        json.parseToJsonElement(getRaw("/memories?since=$sinceEpochSec&limit=$limit&slim=1").ifBlank { "[]" })
            .jsonArray
            .mapNotNull { (it as? JsonObject)?.let(::memView) }
            .sortedByDescending { it.happenedAt }

    suspend fun pending(): List<PendingItem> =
        json.parseToJsonElement(getRaw("/pending").ifBlank { "[]" }).jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val args = o["args"] as? JsonObject ?: JsonObject(emptyMap())
            PendingItem(
                id = o["id"]?.jsonPrimitive?.long ?: return@mapNotNull null,
                title = args.str("title").ifBlank { o.str("title", "summary") },
                reason = o.str("reason"),
                evidence = (o["evidence"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.str("text") }?.filter { it.isNotBlank() }
                    ?: emptyList(),
                capability = o.str("capability", "cap", "name"),
                action = o.str("action"),
            )
        }

    suspend fun confirm(pendingId: Long, approve: Boolean) {
        post("/confirm/$pendingId?approve=$approve", buildJsonObject { })
    }

    // ---- capabilities on the bus (`POST /invoke`) -------------------------------

    suspend fun invoke(capability: String, action: String, args: JsonObject = buildJsonObject { }): JsonObject =
        post("/invoke", buildJsonObject { put("name", capability); put("action", action); put("args", args) })

    /**
     * Sends the phone's hotword list to the brain, which biases every later
     * transcription with it (`correct.apply` merges and de-duplicates server-side).
     */
    suspend fun pushHotwords(words: List<String>) {
        if (words.isEmpty()) return
        invoke("correct", "apply", buildJsonObject {
            put("ops", buildJsonArray {
                add(buildJsonObject { put("type", "hotword"); put("words", buildJsonArray { words.forEach { add(it) } }) })
            })
        })
    }

    /** 口音校准 ①: industries → terms the ASR is likely to mishear. */
    suspend fun calibrateTerms(industries: List<String>, note: String): List<String> {
        val r = invoke("calibrate", "hotwords", buildJsonObject {
            put("industries", buildJsonArray { industries.forEach { add(it) } })
            put("industry", industries.firstOrNull() ?: "other")
            put("note", note)
        })
        if ((r["ok"] as? JsonPrimitive)?.content == "false") throw IllegalStateException(r.str("error").ifBlank { "生成术语失败" })
        return (r["terms"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
    }

    /** 口音校准 ②: terms → a short first-person passage to read aloud. */
    suspend fun calibrateScript(terms: List<String>, industries: List<String>): String {
        val r = invoke("calibrate", "script", buildJsonObject {
            put("terms", buildJsonArray { terms.forEach { add(it) } })
            put("industries", buildJsonArray { industries.forEach { add(it) } })
        })
        val script = r.str("script")
        if (script.isBlank()) throw IllegalStateException(r.str("error").ifBlank { "朗读稿生成失败" })
        return script
    }

    data class CalibrationPair(val wrong: String, val right: String)
    data class CalibrationResult(val pairs: List<CalibrationPair>, val saved: Int, val warnings: List<String>)

    /** 口音校准 ③: known text vs what the ASR heard → confusion pairs, saved as aliases + hotwords. */
    suspend fun calibrateDiff(script: String, heard: String, seconds: Double, terms: List<String>, industries: List<String>, note: String): CalibrationResult {
        val r = invoke("calibrate", "diff", buildJsonObject {
            put("script", script); put("heard", heard); put("seconds", seconds); put("note", note)
            put("terms", buildJsonArray { terms.forEach { add(it) } })
            put("industries", buildJsonArray { industries.forEach { add(it) } })
            put("at", System.currentTimeMillis() / 1000)
        })
        return CalibrationResult(
            pairs = (r["pairs"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                CalibrationPair(o.str("wrong"), o.str("right")).takeIf { it.wrong.isNotBlank() && it.right.isNotBlank() }
            } ?: emptyList(),
            saved = (r["saved"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            warnings = (r["warnings"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList(),
        )
    }

    /** Raw transcription — no hotword bias, nothing stored — so calibration sees the real mishearings. */
    suspend fun transcribeRaw(audio: File, format: String): String {
        val b64 = withContext(Dispatchers.IO) { Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP) }
        val r = post("/upload", buildJsonObject {
            put("kind", "audio"); put("audio_base64", b64); put("format", format); put("filename", audio.name)
            put("raw", true); put("store_raw", false); put("extract", false)
        })
        return parseUploadReport(r).turns.joinToString("") { it.text }
    }

    suspend fun ask(question: String): AskResult {
        val r = post("/ask", buildJsonObject { put("question", question); put("limit", 8) })
        return AskResult(
            answer = (r["answer"] as? JsonPrimitive)?.content ?: "",
            degraded = (r["degraded"] as? JsonPrimitive)?.content?.toBoolean() ?: false,
            sources = (r["sources"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::memView) }
                ?: emptyList(),
        )
    }
}
