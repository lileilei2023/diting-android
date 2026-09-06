package com.diting.ai.parse

import com.diting.ai.http.AiException
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Each case here is a shape a real model has returned when asked for JSON.
 * Recovering from them is cheaper — in latency and in the user's money — than
 * re-prompting.
 */
class StructuredOutputTest {

    @Serializable
    data class Payload(val title: String = "", val count: Int = 0)

    private fun decode(raw: String) = StructuredOutput.decode(raw, Payload.serializer())

    @Test
    fun `bare JSON`() {
        assertEquals(Payload("周会", 3), decode("""{"title":"周会","count":3}"""))
    }

    @Test
    fun `fenced json block`() {
        val raw = """
            ```json
            {"title":"周会","count":3}
            ```
        """.trimIndent()
        assertEquals(Payload("周会", 3), decode(raw))
    }

    @Test
    fun `unlabelled fence`() {
        assertEquals(Payload("周会", 3), decode("```\n{\"title\":\"周会\",\"count\":3}\n```"))
    }

    @Test
    fun `chinese preamble before the JSON`() {
        val raw = """好的，以下是整理结果：
            {"title":"周会","count":3}"""
        assertEquals(Payload("周会", 3), decode(raw))
    }

    @Test
    fun `explanation after the JSON`() {
        val raw = """{"title":"周会","count":3}
            以上就是本次会议的要点整理。"""
        assertEquals(Payload("周会", 3), decode(raw))
    }

    @Test
    fun `trailing comma is repaired`() {
        assertEquals(Payload("周会", 3), decode("""{"title":"周会","count":3,}"""))
    }

    @Test
    fun `braces inside a string value do not truncate the match`() {
        // Naive "find the last }" scanning breaks on this.
        val raw = """{"title":"用 {占位符} 表示","count":1}"""
        assertEquals("用 {占位符} 表示", decode(raw).title)
    }

    @Test
    fun `an escaped quote inside a string does not confuse the scanner`() {
        val raw = """{"title":"他说\"好\"","count":1}"""
        assertEquals("""他说"好"""", decode(raw).title)
    }

    @Test
    fun `chinese typographic quotes inside a value survive untouched`() {
        // Deliberately not "repaired": rewriting these would corrupt real content.
        val raw = """{"title":"他说“好的”","count":1}"""
        assertEquals("他说“好的”", decode(raw).title)
    }

    @Test
    fun `an array response is extracted too`() {
        assertEquals("[1,2,3]", StructuredOutput.extractJson("结果如下：[1,2,3] 完毕"))
    }

    @Test
    fun `no JSON at all fails loudly`() {
        val error = assertThrows<AiException.BadResponse> { decode("我不太确定这段会议的内容。") }
        assertEquals(true, error.message!!.contains("没有返回 JSON"))
    }

    @Test
    fun `unterminated JSON is not silently accepted`() {
        assertNull(StructuredOutput.extractJson("""{"title":"周会","count":3"""))
    }

    @Test
    fun `malformed JSON that cannot be repaired fails loudly`() {
        assertThrows<AiException.BadResponse> { decode("""{"title": }""") }
    }
}
