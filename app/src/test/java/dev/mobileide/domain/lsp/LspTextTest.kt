package dev.mobileide.domain.lsp

import org.junit.Assert.assertEquals
import org.junit.Test

class LspTextTest {

    @Test
    fun `removes numeric and named placeholders`() {
        assertEquals("foo()", LspText.stripSnippets("foo(\$0)"))
        assertEquals("name", LspText.stripSnippets("\${1:name}"))
        assertEquals("int x = 1;", LspText.stripSnippets("int x = \${1:1};"))
        assertEquals("plain text", LspText.stripSnippets("plain \$1text"))
        assertEquals("a b", LspText.stripSnippets("a \${2:b}"))
    }

    @Test
    fun `keeps escaped dollars and unknown templates`() {
        assertEquals("\$notAPlaceholder", LspText.stripSnippets("\\\$notAPlaceholder"))
        assertEquals("\${TM_FILENAME}", LspText.stripSnippets("\${TM_FILENAME}"))
    }

    @Test
    fun `picks the first choice of a choice placeholder`() {
        assertEquals("one", LspText.stripSnippets("\${1|one,two|}"))
        assertEquals("value = one;", LspText.stripSnippets("value = \${1|one,two|};"))
    }

    @Test
    fun `turns fenced markdown into text`() {
        assertEquals("int x;", LspText.markupToPlainText("```c\nint x;\n```"))
        assertEquals(
            "Signature\n\nint add(int a, int b);",
            LspText.markupToPlainText("### Signature\n\n```c\nint add(int a, int b);\n```")
        )
    }

    @Test
    fun `strips emphasis and links`() {
        assertEquals("bold and code", LspText.markupToPlainText("**bold** and `code`"))
        assertEquals(
            "see the docs for details",
            LspText.markupToPlainText("see [the docs](https://example.com) for details")
        )
    }

    @Test
    fun `collapses blank lines left by fences`() {
        assertEquals("a\n\nb", LspText.markupToPlainText("a\n\n\n```\n```\n\nb"))
    }
}
