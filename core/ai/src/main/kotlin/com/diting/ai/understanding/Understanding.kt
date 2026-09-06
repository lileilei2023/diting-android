package com.diting.ai.understanding

import com.diting.ai.llm.ChatMessage
import com.diting.ai.llm.LlmClient
import com.diting.ai.parse.StructuredOutput
import com.diting.domain.model.Citation
import com.diting.domain.model.Intent
import com.diting.domain.model.Segment
import com.diting.domain.scene.Scene
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// -- what the model is asked to return ------------------------------------------

@Serializable
data class MinutesPoint(
    val text: String,
    /** Index into the segment list this point came from. */
    @SerialName("segment_index") val segmentIndex: Int? = null,
)

@Serializable
data class ExtractedTodo(
    val text: String,
    val owner: String? = null,
    val due: String? = null,
    @SerialName("segment_index") val segmentIndex: Int? = null,
)

@Serializable
data class SummaryPayload(
    val title: String = "",
    @SerialName("one_line") val oneLine: String = "",
    val points: List<MinutesPoint> = emptyList(),
    val decisions: List<MinutesPoint> = emptyList(),
    val todos: List<ExtractedTodo> = emptyList(),
    val risks: List<MinutesPoint> = emptyList(),
)

@Serializable
data class ClassifiedSentence(
    @SerialName("segment_index") val segmentIndex: Int,
    val intent: String,
)

@Serializable
data class ClassificationPayload(
    val sentences: List<ClassifiedSentence> = emptyList(),
)

@Serializable
data class HotwordCandidate(
    val heard: String,
    val suggestion: String,
    @SerialName("segment_index") val segmentIndex: Int? = null,
)

@Serializable
data class HotwordPayload(
    val candidates: List<HotwordCandidate> = emptyList(),
)

// -- resolved, citation-carrying results ----------------------------------------

/** A summary whose every line can be traced back to audio. */
data class SessionSummary(
    val title: String,
    val oneLine: String,
    val points: List<CitedLine>,
    val decisions: List<CitedLine>,
    val todos: List<CitedTodo>,
    val risks: List<CitedLine>,
)

data class CitedLine(val text: String, val citation: Citation?)

data class CitedTodo(
    val text: String,
    val owner: String?,
    val due: String?,
    val citation: Citation?,
)

/**
 * Turns a transcript into the things the app shows: 总结, 待办, 意图分类, 热词候选.
 *
 * Two decisions shape this class:
 *
 *  1. **The model returns segment *indices*, not timestamps.** Asking a model to
 *     copy timestamps back invites it to hallucinate plausible-looking ones, and a
 *     wrong "↩ 回到原声" that jumps to the wrong moment is worse than none. An index
 *     is checkable — anything out of range is dropped rather than shown.
 *  2. **The scene's own prompt is appended, not substituted.** 教小谛 lets a user
 *     add instructions per scene; letting those replace the base prompt would let a
 *     stray edit disable citations entirely.
 */
class UnderstandingService(private val llm: LlmClient) {

    suspend fun summarize(
        segments: List<Segment>,
        scene: Scene? = null,
        extraPrompt: String? = null,
    ): SessionSummary {
        require(segments.isNotEmpty()) { "cannot summarize an empty transcript" }

        val result = llm.complete(
            messages = listOf(
                ChatMessage.system(summarySystemPrompt(scene, extraPrompt)),
                ChatMessage.user(renderTranscript(segments)),
            ),
            jsonMode = true,
        )
        val payload = StructuredOutput.decode(result.text, SummaryPayload.serializer())

        return SessionSummary(
            title = payload.title.ifBlank { segments.first().text.take(20) },
            oneLine = payload.oneLine,
            points = payload.points.map { it.resolve(segments) },
            decisions = payload.decisions.map { it.resolve(segments) },
            risks = payload.risks.map { it.resolve(segments) },
            todos = payload.todos.map { todo ->
                CitedTodo(
                    text = todo.text,
                    owner = todo.owner,
                    due = todo.due,
                    citation = segments.citationAt(todo.segmentIndex),
                )
            },
        )
    }

    /**
     * Labels each sentence so the scene response rules have something to act on.
     * Unknown labels are dropped rather than guessed at.
     */
    suspend fun classify(segments: List<Segment>): Map<String, Intent> {
        if (segments.isEmpty()) return emptyMap()

        val result = llm.complete(
            messages = listOf(
                ChatMessage.system(CLASSIFY_PROMPT),
                ChatMessage.user(renderTranscript(segments)),
            ),
            jsonMode = true,
        )
        val payload = StructuredOutput.decode(result.text, ClassificationPayload.serializer())

        return payload.sentences.mapNotNull { classified ->
            val segment = segments.getOrNull(classified.segmentIndex) ?: return@mapNotNull null
            val intent = Intent.entries.firstOrNull { it.name == classified.intent.uppercase() }
                ?: return@mapNotNull null
            segment.id to intent
        }.toMap()
    }

    /**
     * Proposes hotword corrections — the "随手教" loop, but found automatically.
     *
     * These are *candidates*: they show up in 热词与专名 for the user to accept, and
     * are never applied to a transcript on their own.
     */
    suspend fun proposeHotwords(
        segments: List<Segment>,
        knownHotwords: List<String>,
    ): List<HotwordCandidate> {
        if (segments.isEmpty()) return emptyList()

        val result = llm.complete(
            messages = listOf(
                ChatMessage.system(hotwordPrompt(knownHotwords)),
                ChatMessage.user(renderTranscript(segments)),
            ),
            jsonMode = true,
        )
        return StructuredOutput.decode(result.text, HotwordPayload.serializer())
            .candidates
            .filter { it.heard.isNotBlank() && it.suggestion.isNotBlank() }
            .filter { it.heard != it.suggestion }
    }

    // -- prompts ------------------------------------------------------------

    private fun summarySystemPrompt(scene: Scene?, extra: String?): String = buildString {
        appendLine(BASE_SUMMARY_PROMPT)
        scene?.let {
            appendLine()
            appendLine("当前场景：${it.name}。")
            it.customPrompt?.takeIf(String::isNotBlank)?.let { p ->
                appendLine("场景补充要求：$p")
            }
        }
        extra?.takeIf(String::isNotBlank)?.let {
            appendLine()
            appendLine("用户补充要求：$it")
        }
    }

    private fun hotwordPrompt(known: List<String>) = buildString {
        appendLine(BASE_HOTWORD_PROMPT)
        if (known.isNotEmpty()) {
            appendLine()
            appendLine("已有热词（不要重复提出）：${known.joinToString("、")}")
        }
    }

    /**
     * Renders the transcript with explicit indices, which is what the model cites
     * back. Speaker labels are included so it can attribute commitments.
     */
    private fun renderTranscript(segments: List<Segment>): String =
        segments.mapIndexed { index, segment ->
            "[$index] ${segment.speakerLabel} ${formatMs(segment.startMs)}｜${segment.text}"
        }.joinToString("\n")

    private fun formatMs(ms: Long): String {
        val totalSeconds = ms / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun MinutesPoint.resolve(segments: List<Segment>) =
        CitedLine(text, segments.citationAt(segmentIndex))

    /** Null for an out-of-range index — a wrong jump is worse than no jump. */
    private fun List<Segment>.citationAt(index: Int?): Citation? =
        index?.let { getOrNull(it) }?.citation

    private companion object {
        val BASE_SUMMARY_PROMPT = """
            你是谛听的理解引擎。输入是一段带序号的会话转写，每行格式为
            `[序号] 说话人 时间｜内容`。

            请只输出一个 JSON 对象，字段如下：
            {
              "title": "不超过 20 字的标题",
              "one_line": "一句话说清这场会话最重要的事",
              "points": [{"text": "要点", "segment_index": 0}],
              "decisions": [{"text": "已经拍板的决定", "segment_index": 0}],
              "todos": [{"text": "要做的事", "owner": "谁", "due": "什么时候", "segment_index": 0}],
              "risks": [{"text": "风险或未决分歧", "segment_index": 0}]
            }

            硬性要求：
            1. segment_index 必须是输入里真实出现过的序号，用来定位原声。
               宁可省略 segment_index，也不要编造一个。
            2. 只写转写里出现过的内容，不要补充你的推断作为事实。
            3. decisions 只放已经拍板的；还在争论的放 risks。
            4. todos 里 owner 和 due 没有说到就留 null，不要猜。
        """.trimIndent()

        val CLASSIFY_PROMPT = """
            你是谛听的意图分类器。输入是带序号的转写，每行 `[序号] 说话人 时间｜内容`。

            为每一行判断意图，只输出 JSON：
            {"sentences": [{"segment_index": 0, "intent": "COMMITMENT"}]}

            intent 只能取以下之一：
            COMMITMENT（承诺句，说话人答应做某事）
            IMPERATIVE（祈使句，要求别人或小谛做某事）
            DECISION（拍板的决定）
            RECURRING_ASK（反复提出的诉求）
            RISK（风险、担忧、未决分歧）
            SMALL_TALK（闲聊、寒暄）
            FOREIGN_LANGUAGE（外语）

            判断不了的行直接不要出现在结果里，不要硬套一个。
        """.trimIndent()

        val BASE_HOTWORD_PROMPT = """
            你是谛听的热词发现器。输入是带序号的语音转写，里面可能有同音字错误，
            典型情况是专有名词、产品名、人名、英文缩写被转写成了读音相近的中文。

            找出这些疑似错误，只输出 JSON：
            {"candidates": [{"heard": "欧艾斯艾斯", "suggestion": "OSS", "segment_index": 3}]}

            要求：
            1. 只提你有把握的；正常的中文词不要提。
            2. heard 必须是转写里原样出现的字符串。
            3. 找不到就返回 {"candidates": []}。
        """.trimIndent()
    }
}
