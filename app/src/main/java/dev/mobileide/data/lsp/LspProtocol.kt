package dev.mobileide.data.lsp

import dev.mobileide.domain.lsp.LspCapabilities
import dev.mobileide.domain.lsp.LspCompletionItem
import dev.mobileide.domain.lsp.LspDiagnostic
import dev.mobileide.domain.lsp.LspDocumentSymbol
import dev.mobileide.domain.lsp.LspHover
import dev.mobileide.domain.lsp.LspPosition
import dev.mobileide.domain.lsp.LspRange
import dev.mobileide.domain.lsp.LspSeverity
import dev.mobileide.domain.lsp.LspText
import dev.mobileide.domain.lsp.LspTextEdit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure translation between LSP JSON and the app's value objects.
 *
 * Keeping this free of processes and coroutines makes the tricky parts
 * (completion text edits, hierarchy vs flat symbols, markup) unit-testable.
 */
object LspProtocol {

    fun positionParams(uri: String, line: Int, column: Int): JSONObject = JSONObject()
        .put("textDocument", JSONObject().put("uri", uri))
        .put(
            "position",
            JSONObject()
                .put("line", line.coerceAtLeast(0))
                .put("character", column.coerceAtLeast(0))
        )

    fun textDocumentPositionParams(uri: String, line: Int, column: Int): JSONObject =
        positionParams(uri, line, column)

    fun parseCapabilities(result: JSONObject?): LspCapabilities {
        val capabilities = result?.optJSONObject("capabilities") ?: return LspCapabilities()
        return LspCapabilities(
            completion = capabilities.has("completionProvider") &&
                !capabilities.isNull("completionProvider"),
            hover = enabled(capabilities.opt("hoverProvider")),
            definition = enabled(capabilities.opt("definitionProvider")),
            references = enabled(capabilities.opt("referencesProvider")),
            documentSymbol = enabled(capabilities.opt("documentSymbolProvider")),
            formatting = enabled(capabilities.opt("documentFormattingProvider"))
        )
    }

    private fun enabled(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> false
        is Boolean -> value
        is JSONObject -> true
        else -> false
    }

    // ---- diagnostics ------------------------------------------------------

    data class DiagnosticsPayload(
        val uri: String,
        val diagnostics: List<LspDiagnostic>,
        val version: Int?
    )

    fun parseDiagnostics(params: JSONObject?): DiagnosticsPayload? {
        val uri = params?.optString("uri")?.takeIf { it.isNotEmpty() } ?: return null
        val array = params.optJSONArray("diagnostics") ?: JSONArray()
        val list = ArrayList<LspDiagnostic>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val range = parseRange(item.optJSONObject("range")) ?: continue
            val message = item.optString("message")
            if (message.isEmpty()) continue
            list += LspDiagnostic(
                range = range,
                severity = when (item.optInt("severity", 3)) {
                    1 -> LspSeverity.ERROR
                    2 -> LspSeverity.WARNING
                    4 -> LspSeverity.HINT
                    else -> LspSeverity.INFO
                },
                message = message,
                source = item.optString("source").takeIf { it.isNotEmpty() },
                code = item.opt("code")?.let { code ->
                    when (code) {
                        is String -> code
                        is Number -> code.toString()
                        else -> null
                    }
                }
            )
        }
        val version = if (params.has("version") && !params.isNull("version")) {
            params.optInt("version")
        } else {
            null
        }
        return DiagnosticsPayload(uri, list, version)
    }

    // ---- range / edit -----------------------------------------------------

    fun parseRange(value: JSONObject?): LspRange? {
        val start = parsePosition(value?.optJSONObject("start")) ?: return null
        val end = parsePosition(value?.optJSONObject("end")) ?: return null
        return LspRange(start, end)
    }

    fun parsePosition(value: JSONObject?): LspPosition? {
        if (value == null) return null
        return LspPosition(
            line = value.optInt("line", -1).coerceAtLeast(0),
            character = value.optInt("character", 0).coerceAtLeast(0)
        )
    }

    fun parseTextEdits(value: Any?): List<LspTextEdit> {
        val array = value as? JSONArray ?: return emptyList()
        val edits = ArrayList<LspTextEdit>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val range = parseRange(item.optJSONObject("range")) ?: continue
            edits += LspTextEdit(range, item.optString("newText"))
        }
        return edits
    }

    // ---- completion -------------------------------------------------------

    fun parseCompletion(result: Any?): List<LspCompletionItem> {
        val array = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("items")
            else -> null
        } ?: return emptyList()
        val items = ArrayList<LspCompletionItem>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val label = item.optString("label").takeIf { it.isNotEmpty() } ?: continue
            val documentation = documentationText(item.opt("documentation"))
            val deprecated = item.optBoolean("deprecated", false) ||
                (item.optJSONArray("tags")?.let { tags ->
                    (0 until tags.length()).any { tags.optInt(it) == 1 }
                } == true)
            items += LspCompletionItem(
                label = label,
                detail = item.optString("detail").takeIf { it.isNotEmpty() },
                kind = item.optInt("kind", 1),
                insertText = item.optString("insertText").takeIf { it.isNotEmpty() },
                textEdit = parseCompletionEdit(item.optJSONObject("textEdit")),
                additionalEdits = parseTextEdits(item.opt("additionalTextEdits")),
                sortText = item.optString("sortText").takeIf { it.isNotEmpty() },
                filterText = item.optString("filterText").takeIf { it.isNotEmpty() },
                documentation = documentation,
                deprecated = deprecated
            )
        }
        return items
    }

    /** Handles both TextEdit and InsertReplaceEdit. */
    private fun parseCompletionEdit(value: JSONObject?): LspTextEdit? {
        if (value == null) return null
        val range = parseRange(value.optJSONObject("range"))
            ?: parseRange(value.optJSONObject("insert"))
            ?: return null
        return LspTextEdit(range, value.optString("newText"))
    }

    fun documentationText(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value.takeIf { it.isNotBlank() }
        is JSONObject -> value.optString("value").takeIf { it.isNotBlank() }
        else -> null
    }

    // ---- hover ------------------------------------------------------------

    fun parseHover(result: Any?): LspHover? {
        val root = result as? JSONObject ?: return null
        val contents = root.opt("contents")
        val text = when (contents) {
            null, JSONObject.NULL -> return null
            is String -> contents
            is JSONObject -> contents.optString("value")
            is JSONArray -> {
                val parts = ArrayList<String>(contents.length())
                for (index in 0 until contents.length()) {
                    val part = contents.opt(index)
                    when (part) {
                        is String -> parts += part
                        is JSONObject -> parts += part.optString("value")
                    }
                }
                parts.joinToString("\n\n")
            }

            else -> return null
        }
        if (text.isBlank()) return null
        return LspHover(markupToPlainText(text), parseRange(root.optJSONObject("range")))
    }

    // ---- locations --------------------------------------------------------

    /** Raw uri + range pairs; the repository resolves them against the project. */
    fun parseLocationPairs(result: Any?): List<Pair<String, LspRange>> {
        val array = when (result) {
            is JSONArray -> result
            is JSONObject -> JSONArray().put(result)
            else -> return emptyList()
        }
        val locations = ArrayList<Pair<String, LspRange>>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val targetUri = item.optString("targetUri").takeIf { it.isNotEmpty() }
            if (targetUri != null) {
                val range = parseRange(item.optJSONObject("targetSelectionRange"))
                    ?: parseRange(item.optJSONObject("targetRange"))
                    ?: continue
                locations += targetUri to range
            } else {
                val uri = item.optString("uri").takeIf { it.isNotEmpty() } ?: continue
                val range = parseRange(item.optJSONObject("range")) ?: continue
                locations += uri to range
            }
        }
        return locations
    }

    // ---- symbols ----------------------------------------------------------

    fun parseDocumentSymbols(result: Any?): List<LspDocumentSymbol> {
        val array = result as? JSONArray ?: return emptyList()
        return parseSymbolArray(array)
    }

    private fun parseSymbolArray(array: JSONArray): List<LspDocumentSymbol> {
        val symbols = ArrayList<LspDocumentSymbol>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").takeIf { it.isNotEmpty() } ?: continue
            val flatLocation = item.optJSONObject("location")
            if (flatLocation != null) {
                val range = parseRange(flatLocation.optJSONObject("range")) ?: continue
                symbols += LspDocumentSymbol(
                    name = name,
                    detail = item.optString("containerName").takeIf { it.isNotEmpty() },
                    kind = item.optInt("kind", 1),
                    range = range,
                    selectionRange = range
                )
                continue
            }
            val range = parseRange(item.optJSONObject("range")) ?: continue
            val selection = parseRange(item.optJSONObject("selectionRange")) ?: range
            val children = item.optJSONArray("children")?.let { parseSymbolArray(it) }.orEmpty()
            symbols += LspDocumentSymbol(
                name = name,
                detail = item.optString("detail").takeIf { it.isNotEmpty() },
                kind = item.optInt("kind", 1),
                range = range,
                selectionRange = selection,
                children = children
            )
        }
        return symbols
    }

    // ---- markup -----------------------------------------------------------

    /**
     * Minimal markdown-to-text conversion for hover cards. Full markdown is not
     * rendered: signatures and doc comments stay readable without a parser.
     */
    fun markupToPlainText(markup: String): String = LspText.markupToPlainText(markup)

    /**
     * Removes snippet placeholders. The client advertises no snippet support,
     * so this is only a safety net for servers that send `$1` anyway.
     */
    fun stripSnippets(text: String): String = LspText.stripSnippets(text)

}
