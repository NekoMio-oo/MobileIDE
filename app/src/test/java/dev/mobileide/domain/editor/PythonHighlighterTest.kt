package dev.mobileide.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonHighlighterTest {

    private data class Span(val line: Int, val column: Int, val kind: PythonTokenKind?)

    private fun scan(text: String): Pair<Int, List<Span>> {
        val spans = mutableListOf<Span>()
        val lines = PythonHighlighter.tokenize(text) { line, column, kind ->
            spans += Span(line, column, kind)
        }
        return lines to spans
    }

    @Test
    fun `keywords, builtins, numbers, strings and comments get their own style`() {
        val (lines, spans) = scan("def greet(name):\n    print(\"hi\", 42)  # note\n")

        assertEquals(3, lines)                                                    // trailing empty line
        assertTrue(spans.contains(Span(0, 0, PythonTokenKind.KEYWORD)))           // def
        assertTrue(spans.contains(Span(0, 4, PythonTokenKind.FUNCTION)))          // greet
        assertTrue(spans.contains(Span(1, 4, PythonTokenKind.BUILTIN)))           // print
        assertTrue(spans.contains(Span(1, 10, PythonTokenKind.STRING)))           // "hi"
        assertTrue(spans.contains(Span(1, 16, PythonTokenKind.NUMBER)))           // 42
        assertTrue(spans.contains(Span(1, 21, PythonTokenKind.COMMENT)))          // # note
    }

    @Test
    fun `hashes inside strings are not comments`() {
        val (_, spans) = scan("x = \"# not a comment\"\n")
        assertEquals(emptyList<Span>(), spans.filter { it.kind == PythonTokenKind.COMMENT })
    }

    @Test
    fun `decorators and definitions are recognised`() {
        val (_, spans) = scan("@staticmethod\nclass Thing:\n")
        assertTrue(spans.contains(Span(0, 0, PythonTokenKind.DECORATOR)))
        assertTrue(spans.contains(Span(1, 0, PythonTokenKind.KEYWORD)))
        assertTrue(spans.contains(Span(1, 6, PythonTokenKind.FUNCTION)))
    }

    @Test
    fun `triple quoted strings carry their colour across lines and close`() {
        val (lines, spans) = scan("s = \"\"\"first\n\nlast\"\"\"\nx = 1\n")
        assertEquals(5, lines)
        assertTrue(spans.contains(Span(0, 4, PythonTokenKind.STRING)))
        assertTrue(spans.contains(Span(2, 0, PythonTokenKind.STRING)))
        // The closing line resets to normal before ordinary code continues.
        assertEquals(null, spans.last { it.line == 2 }.kind)
        assertTrue(spans.any { it.line == 3 && it.kind == PythonTokenKind.NUMBER })
    }

    @Test
    fun `unterminated triple quote keeps colour until the end of the file`() {
        val (lines, spans) = scan("s = \"\"\"start\nnext\n")
        assertEquals(3, lines)
        assertTrue(spans.contains(Span(0, 4, PythonTokenKind.STRING)))
        assertTrue(spans.contains(Span(1, 0, PythonTokenKind.STRING)))
        // No normal reset on the last content line: the style continues.
        assertEquals(PythonTokenKind.STRING, spans.last { it.line == 1 }.kind)
        assertTrue(spans.none { it.line == 2 })
    }

    @Test
    fun `empty document still has one line`() {
        assertEquals(1, scan("").first)
        assertEquals(2, scan("\n").first)
        assertEquals(3, scan("a\n\n").first)
    }

    @Test
    fun `spans are emitted in document order`() {
        val (_, spans) = scan("a = 1\nb = 2\nc = 3\n")
        val positions = spans.map { it.line * 1000 + it.column }
        assertEquals(positions.sorted(), positions)
    }
}
