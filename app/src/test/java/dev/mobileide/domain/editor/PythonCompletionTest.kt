package dev.mobileide.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonCompletionTest {

    /** Puts [prefix] on a new last line and asks for completion at its caret. */
    private fun suggestIn(body: String, prefix: String): List<PythonSuggestion> {
        val text = body + prefix
        val line = text.count { it == '\n' }
        return PythonCompletion.suggest(text, line, prefix.length)
    }

    @Test
    fun `keywords and builtins are offered with no server and no rootfs`() {
        assertTrue(suggestIn("", "pri").any {
            it.label == "print" && it.kind == PythonSuggestionKind.BUILTIN
        })
        assertTrue(suggestIn("", "de").any {
            it.label == "def" && it.kind == PythonSuggestionKind.KEYWORD
        })
    }

    @Test
    fun `names defined in the file keep their category`() {
        val body = "import os\ndef greet(name):\n    return name\n" +
            "class Thing:\n    pass\ncount = 1\n"

        assertTrue(suggestIn(body, "gre").any {
            it.label == "greet" && it.kind == PythonSuggestionKind.FUNCTION
        })
        assertTrue(suggestIn(body, "Thi").any {
            it.label == "Thing" && it.kind == PythonSuggestionKind.CLASS
        })
        assertTrue(suggestIn(body, "cou").any {
            it.label == "count" && it.kind == PythonSuggestionKind.VARIABLE
        })
        assertTrue(suggestIn(body, "o").any {
            it.label == "os" && it.kind == PythonSuggestionKind.MODULE
        })
    }

    @Test
    fun `words inside strings and comments are not harvested`() {
        val body = "x = \"hidden_word\"\n# hidden_comment\n"
        val items = suggestIn(body, "hid")
        assertEquals(emptyList<String>(), items.map { it.label }
            .filter { it == "hidden_word" || it == "hidden_comment" })
    }

    @Test
    fun `the word being typed is not offered as its own completion`() {
        val items = suggestIn("value = 1\n", "val")
        assertTrue(items.any { it.label == "value" })
        assertTrue(items.none { it.label == "val" })
    }

    @Test
    fun `empty or unmatched prefixes produce nothing`() {
        assertEquals(emptyList<PythonSuggestion>(), PythonCompletion.suggest("print(", 0, 6))
        assertEquals(emptyList<PythonSuggestion>(), suggestIn("", "zzz"))
    }

    @Test
    fun `keywords rank ahead of file words`() {
        val items = suggestIn("definite = 1\n", "de")
        assertTrue(items.first().kind == PythonSuggestionKind.KEYWORD)
    }

    @Test
    fun `suggestions are capped`() {
        val body = (1..400).joinToString("\n") { "prefixed_name_$it = $it" } + "\n"
        assertTrue(suggestIn(body, "prefixed").size <= 100)
    }
}
