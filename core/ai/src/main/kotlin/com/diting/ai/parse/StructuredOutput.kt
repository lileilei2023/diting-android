package com.diting.ai.parse

import com.diting.ai.http.AiException
import com.diting.ai.http.AiJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement

/**
 * Pulls a JSON value out of whatever a model actually returned.
 *
 * Asking for JSON does not reliably get you *only* JSON. Across 千问, 豆包 and
 * self-hosted servers the same prompt comes back as:
 *
 *  * bare JSON (the good case);
 *  * a ```json fenced block;
 *  * a sentence of Chinese preamble, then the JSON;
 *  * JSON followed by an explanation;
 *  * JSON with a trailing comma, or with `“smart quotes”` around keys.
 *
 * Re-prompting on each of those wastes a round trip and the user's money. This
 * recovers the value instead, and only fails when there is genuinely no JSON in
 * the response.
 */
object StructuredOutput {

    /** Decodes [raw] into [T], recovering from the wrappers listed above. */
    fun <T> decode(raw: String, serializer: KSerializer<T>): T {
        val candidate = extractJson(raw)
            ?: throw AiException.BadResponse("模型没有返回 JSON：${raw.take(300)}")

        return runCatching { AiJson.decodeFromString(serializer, candidate) }
            .getOrElse { first ->
                // One repair pass for the two malformations that actually occur.
                val repaired = repair(candidate)
                runCatching { AiJson.decodeFromString(serializer, repaired) }
                    .getOrElse {
                        throw AiException.BadResponse(
                            "模型返回的 JSON 无法解析（${first.message}）：${candidate.take(300)}"
                        )
                    }
            }
    }

    fun decodeElement(raw: String): JsonElement =
        decode(raw, JsonElement.serializer())

    /**
     * Finds the outermost JSON object or array in [raw].
     *
     * Scans with a brace/bracket depth counter that ignores delimiters inside
     * string literals, so prose containing `{` — or a JSON string value
     * containing `}` — does not truncate the match.
     */
    fun extractJson(raw: String): String? {
        val text = stripFences(raw).trim()
        if (text.isEmpty()) return null

        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null

        val opener = text[start]
        val closer = if (opener == '{') '}' else ']'

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == opener -> depth++
                c == closer -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Removes ``` / ```json fences, keeping the block contents. */
    private fun stripFences(raw: String): String {
        val fence = FENCE.find(raw) ?: return raw
        return fence.groupValues[1]
    }

    /**
     * Removes trailing commas before a closing brace or bracket.
     *
     * That is the only repair applied. Typographic quotes are deliberately *not*
     * normalised: Chinese output legitimately contains 「他说“好的”」 inside JSON
     * string values, and rewriting those to ASCII quotes would corrupt real
     * content to fix a rarer malformation. A model that emits smart quotes around
     * its *keys* fails loudly instead, which is the right outcome.
     */
    private fun repair(json: String): String {
        val sb = StringBuilder(json.length)
        var inString = false
        var escaped = false

        for (i in json.indices) {
            val c = json[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
            }

            if (!inString && c == ',' && closesContainerNext(json, i + 1)) continue
            sb.append(c)
        }
        return sb.toString()
    }

    /** True when the first non-space character at or after [from] closes a container. */
    private fun closesContainerNext(json: String, from: Int): Boolean {
        var i = from
        while (i < json.length && json[i].isWhitespace()) i++
        return i < json.length && (json[i] == '}' || json[i] == ']')
    }

    private val FENCE = Regex("```(?:json|JSON)?\\s*\\n?([\\s\\S]*?)```")
}
