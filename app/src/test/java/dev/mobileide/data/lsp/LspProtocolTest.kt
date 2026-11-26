package dev.mobileide.data.lsp

import dev.mobileide.domain.lsp.LspSeverity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LspProtocolTest {

    @Test
    fun `reads advertised capabilities`() {
        val result = JSONObject().put(
            "capabilities",
            JSONObject()
                .put("completionProvider", JSONObject().put("triggerCharacters", JSONArray()))
                .put("hoverProvider", true)
                .put("definitionProvider", JSONObject())
                .put("referencesProvider", false)
                .put("documentFormattingProvider", true)
        )

        val capabilities = LspProtocol.parseCapabilities(result)

        assertTrue(capabilities.completion)
        assertTrue(capabilities.hover)
        assertTrue(capabilities.definition)
        assertFalse(capabilities.references)
        assertFalse(capabilities.documentSymbol)
        assertTrue(capabilities.formatting)
    }

    @Test
    fun `parses diagnostics including severity and version`() {
        val params = JSONObject()
            .put("uri", "file:///workspace/src/main.c")
            .put("version", 4)
            .put(
                "diagnostics",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("range", range(1, 2, 1, 6))
                            .put("severity", 1)
                            .put("message", "expected ';'")
                            .put("source", "clang")
                            .put("code", "expected_semi")
                    )
                    .put(
                        JSONObject()
                            .put("range", range(3, 0, 3, 4))
                            .put("message", "unused variable")
                    )
            )

        val payload = LspProtocol.parseDiagnostics(params)!!

        assertEquals("file:///workspace/src/main.c", payload.uri)
        assertEquals(4, payload.version)
        assertEquals(2, payload.diagnostics.size)
        assertEquals(LspSeverity.ERROR, payload.diagnostics[0].severity)
        assertEquals("expected ';'", payload.diagnostics[0].message)
        assertEquals("clang", payload.diagnostics[0].source)
        assertEquals("expected_semi", payload.diagnostics[0].code)
        assertEquals(LspSeverity.INFO, payload.diagnostics[1].severity)
        assertEquals(3, payload.diagnostics[1].range.start.line)
        assertEquals(4, payload.diagnostics[1].range.end.character)
    }

    @Test
    fun `parses completion lists and plain arrays`() {
        val list = JSONObject().put(
            "items",
            JSONArray().put(
                JSONObject()
                    .put("label", "printf")
                    .put("kind", 3)
                    .put("detail", "int printf(const char*, ...)")
                    .put("insertText", "printf")
                    .put("textEdit", JSONObject().put("range", range(0, 0, 0, 3)).put("newText", "printf"))
                    .put("sortText", "0001")
                    .put("documentation", JSONObject().put("kind", "markdown").put("value", "Prints"))
                    .put("tags", JSONArray().put(1))
            )
        )

        val items = LspProtocol.parseCompletion(list)
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("printf", item.label)
        assertEquals(3, item.kind)
        assertEquals("printf", item.textEdit?.newText)
        assertEquals(3, item.textEdit?.range?.end?.character)
        assertEquals("Prints", item.documentation)
        assertTrue(item.deprecated)

        val plain = JSONArray().put(JSONObject().put("label", "main"))
        assertEquals(1, LspProtocol.parseCompletion(plain).size)
        assertTrue(LspProtocol.parseCompletion(null).isEmpty())
    }

    @Test
    fun `parses insert replace edits`() {
        val item = JSONObject()
            .put("label", "value")
            .put(
                "textEdit",
                JSONObject()
                    .put("insert", range(1, 1, 1, 3))
                    .put("replace", range(1, 1, 1, 9))
                    .put("newText", "value")
            )

        val parsed = LspProtocol.parseCompletion(JSONArray().put(item)).first()
        assertNotNull(parsed.textEdit)
        assertEquals(3, parsed.textEdit?.range?.end?.character)
    }

    @Test
    fun `parses hover in all three encodings`() {
        val markup = LspProtocol.parseHover(
            JSONObject().put(
                "contents",
                JSONObject().put("kind", "markdown").put("value", "```c\nint x;\n```")
            )
        )
        assertEquals("int x;", markup?.contents)

        val string = LspProtocol.parseHover(JSONObject().put("contents", "  plain  "))
        assertEquals("plain", string?.contents)

        val array = LspProtocol.parseHover(
            JSONObject().put(
                "contents",
                JSONArray().put("first").put(JSONObject().put("value", "second"))
            )
        )
        assertEquals("first\n\nsecond", array?.contents)

        assertNull(LspProtocol.parseHover(JSONObject()))
    }

    @Test
    fun `parses locations and location links`() {
        val locations = JSONArray()
            .put(
                JSONObject()
                    .put("uri", "file:///workspace/a.c")
                    .put("range", range(2, 1, 2, 5))
            )
            .put(
                JSONObject()
                    .put("targetUri", "file:///workspace/b.c")
                    .put("targetRange", range(0, 0, 0, 1))
                    .put("targetSelectionRange", range(4, 2, 4, 8))
            )

        val pairs = LspProtocol.parseLocationPairs(locations)

        assertEquals(2, pairs.size)
        assertEquals("file:///workspace/a.c", pairs[0].first)
        assertEquals(1, pairs[0].second.start.character)
        assertEquals("file:///workspace/b.c", pairs[1].first)
        assertEquals(4, pairs[1].second.start.line)
    }

    @Test
    fun `parses hierarchical and flat symbols`() {
        val hierarchical = JSONArray().put(
            JSONObject()
                .put("name", "main")
                .put("kind", 12)
                .put("range", range(0, 0, 9, 1))
                .put("selectionRange", range(0, 4, 0, 8))
                .put(
                    "children",
                    JSONArray().put(
                        JSONObject()
                            .put("name", "loop")
                            .put("kind", 12)
                            .put("range", range(3, 4, 6, 5))
                            .put("selectionRange", range(3, 8, 3, 12))
                    )
                )
        )
        val symbols = LspProtocol.parseDocumentSymbols(hierarchical)
        assertEquals("main", symbols.first().name)
        assertEquals(1, symbols.first().children.size)
        assertEquals("loop", symbols.first().children.first().name)

        val flat = JSONArray().put(
            JSONObject()
                .put("name", "main")
                .put("kind", 12)
                .put(
                    "location",
                    JSONObject()
                        .put("uri", "file:///workspace/a.c")
                        .put("range", range(7, 0, 7, 4))
                )
        )
        val flatSymbols = LspProtocol.parseDocumentSymbols(flat)
        assertEquals(7, flatSymbols.first().range.start.line)
    }

    @Test
    fun `parses formatting edits`() {
        val edits = LspProtocol.parseTextEdits(
            JSONArray().put(
                JSONObject()
                    .put("range", range(0, 0, 1, 0))
                    .put("newText", "int main() {\n")
            )
        )
        assertEquals(1, edits.size)
        assertEquals("int main() {\n", edits.first().newText)
        assertTrue(LspProtocol.parseTextEdits(null).isEmpty())
    }

    private fun range(
        startLine: Int,
        startChar: Int,
        endLine: Int,
        endChar: Int
    ): JSONObject = JSONObject()
        .put("start", JSONObject().put("line", startLine).put("character", startChar))
        .put("end", JSONObject().put("line", endLine).put("character", endChar))
}
