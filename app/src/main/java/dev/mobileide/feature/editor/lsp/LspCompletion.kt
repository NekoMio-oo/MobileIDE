package dev.mobileide.feature.editor.lsp

import dev.mobileide.domain.lsp.LspCompletionItem
import dev.mobileide.domain.lsp.LspPosition
import dev.mobileide.domain.lsp.LspText
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor

/** Synchronous bridge call used from Sora's completion thread. */
fun interface LspCompletionSource {
    fun completion(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): List<LspCompletionItem>
}

/**
 * Applies an LSP completion item exactly where the server asked, instead of
 * appending at the end of the file. `textEdit` ranges win; otherwise the
 * already-typed prefix is replaced.
 */
internal class SoraLspCompletionItem(
    private val item: LspCompletionItem,
    private val typedPrefixLength: Int
) : CompletionItem(item.label) {

    init {
        desc = item.detail
        kind = soraKind(item.kind)
        prefixLength = typedPrefixLength
        sortText = item.sortText
    }

    override fun performCompletion(
        editor: CodeEditor,
        content: Content,
        start: Int,
        end: Int
    ) {
        val mainText = LspText.stripSnippets(
            item.textEdit?.newText ?: item.insertText ?: item.label
        )
        // Sora 0.23.5 passes the original line/column (not offsets) here.
        val line = start.coerceIn(0, maxOf(0, content.lineCount - 1))
        val column = end.coerceIn(0, content.getColumnCount(line))
        val cursor = content.getCharIndex(line, column)
        var start = (cursor - typedPrefixLength).coerceAtLeast(0)
        var end = cursor
        item.textEdit?.let { edit ->
            start = offsetOf(content, edit.range.start)
            end = offsetOf(content, edit.range.end)
        }

        // Import edits and other extras come first, from the bottom up, so the
        // main range stays valid while the text above it grows.
        val extras = item.additionalEdits
            .map { edit ->
                Triple(
                    offsetOf(content, edit.range.start),
                    offsetOf(content, edit.range.end),
                    LspText.stripSnippets(edit.newText)
                )
            }
            .sortedByDescending { it.first }

        content.beginBatchEdit()
        try {
            for ((extraStart, extraEnd, text) in extras) {
                val safeStart = extraStart.coerceIn(0, content.length)
                val safeEnd = extraEnd.coerceIn(safeStart, content.length)
                content.replace(safeStart, safeEnd, text)
                if (safeEnd <= start) {
                    val delta = text.length - (safeEnd - safeStart)
                    start += delta
                    end += delta
                }
            }
            val safeStart = start.coerceIn(0, content.length)
            val safeEnd = end.coerceIn(safeStart, content.length)
            content.replace(safeStart, safeEnd, mainText)
            val target = safeStart + mainText.length
            editor.setSelection(
                content.indexer.getCharLine(target),
                content.indexer.getCharColumn(target)
            )
        } finally {
            content.endBatchEdit()
        }
    }
}

internal fun offsetOf(content: Content, position: LspPosition): Int {
    val line = position.line.coerceIn(0, maxOf(0, content.lineCount - 1))
    val column = position.character.coerceIn(0, content.getColumnCount(line))
    return content.getCharIndex(line, column)
}

/** Number of identifier characters immediately before the caret. */
internal fun prefixLengthAt(content: ContentReference, position: CharPosition): Int {
    val line = content.getLine(position.line)
    var index = position.column.coerceIn(0, line.length)
    var length = 0
    while (index > 0 && isIdentifierChar(line[index - 1])) {
        index--
        length++
    }
    return length
}

private fun isIdentifierChar(char: Char): Boolean =
    char.isLetterOrDigit() || char == '_' || char == '$'

private fun soraKind(kind: Int): CompletionItemKind = when (kind) {
    2 -> CompletionItemKind.Method
    3 -> CompletionItemKind.Function
    4 -> CompletionItemKind.Constructor
    5 -> CompletionItemKind.Field
    6 -> CompletionItemKind.Variable
    7 -> CompletionItemKind.Class
    8 -> CompletionItemKind.Interface
    9 -> CompletionItemKind.Module
    10 -> CompletionItemKind.Property
    11 -> CompletionItemKind.Unit
    12 -> CompletionItemKind.Value
    13 -> CompletionItemKind.Enum
    14 -> CompletionItemKind.Keyword
    15 -> CompletionItemKind.Snippet
    16 -> CompletionItemKind.Color
    17 -> CompletionItemKind.File
    18 -> CompletionItemKind.Reference
    19 -> CompletionItemKind.Folder
    20 -> CompletionItemKind.EnumMember
    21 -> CompletionItemKind.Constant
    22 -> CompletionItemKind.Struct
    else -> CompletionItemKind.Text
}
