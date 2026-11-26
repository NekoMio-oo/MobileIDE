package dev.mobileide.feature.editor.components

import android.os.Bundle
import dev.mobileide.domain.editor.PythonCompletion
import dev.mobileide.domain.editor.PythonHighlighter
import dev.mobileide.domain.editor.PythonSuggestionKind
import dev.mobileide.domain.editor.PythonTokenKind
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Python highlighting and offline completion without a third-party grammar.
 *
 * Sora 0.23.5 no longer ships a Python language module, so the scanner in
 * [PythonHighlighter] feeds Sora's span model directly. Completion has a local
 * word-based source ([PythonCompletion]) that works with no server at all;
 * when pylsp is READY, [dev.mobileide.feature.editor.lsp.LspLanguage] appends
 * its semantic results to the same popup.
 */
class PythonLanguage : EmptyLanguage() {

    private val analyzer = PythonAnalyzer()

    override fun getAnalyzeManager(): AnalyzeManager = analyzer

    override fun useTab(): Boolean = false

    /**
     * Sora adds this value to the current line's indentation, so it is a delta,
     * not an absolute column: a block header ending in ':' indents one level.
     */
    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
        val text = runCatching { content.getLine(line) }.getOrDefault("")
        val before = text.take(column.coerceIn(0, text.length)).trimEnd()
        return if (before.endsWith(':')) 4 else 0
    }

    /**
     * Publishes the local word-based list immediately. This runs on Sora's
     * completion thread, so it must stay synchronous and cheap: no server, no
     * PRoot, no suspension. [SimpleCompletionItem] replaces the typed prefix
     * on commit.
     */
    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        val text = runCatching { content.reference.toString() }.getOrNull() ?: return
        val prefix = runCatching { prefixAt(content, position) }.getOrDefault("")
        val items = PythonCompletion.suggest(text, position.line, position.column).map { suggestion ->
            SimpleCompletionItem(
                suggestion.label,
                suggestion.detail,
                prefix.length,
                suggestion.label
            ).apply { kind = soraKind(suggestion.kind) }
        }
        if (items.isEmpty()) return
        // A newer keystroke cancels the publisher; addItems then throws.
        runCatching {
            publisher.addItems(items)
            publisher.updateList()
        }
    }
}

private fun prefixAt(content: ContentReference, position: CharPosition): String {
    val line = runCatching { content.getLine(position.line) }.getOrDefault("")
    val end = position.column.coerceIn(0, line.length)
    var start = end
    while (start > 0) {
        val char = line[start - 1]
        if (char.isLetterOrDigit() || char == '_') start-- else break
    }
    return line.substring(start, end)
}

private fun soraKind(kind: PythonSuggestionKind): CompletionItemKind = when (kind) {
    PythonSuggestionKind.KEYWORD -> CompletionItemKind.Keyword
    PythonSuggestionKind.BUILTIN,
    PythonSuggestionKind.FUNCTION -> CompletionItemKind.Function
    PythonSuggestionKind.CLASS -> CompletionItemKind.Class
    PythonSuggestionKind.MODULE -> CompletionItemKind.Module
    PythonSuggestionKind.VARIABLE -> CompletionItemKind.Variable
    PythonSuggestionKind.IDENTIFIER -> CompletionItemKind.Identifier
}

/**
 * Sora's own analysis base class owns the background thread, reruns after every
 * edit and discards results whose request id is no longer current. Building
 * spans through [MappedSpans.Builder] keeps the line count in sync with the
 * document (including empty and trailing lines) and lets a triple-quoted string
 * carry its colour across lines.
 */
private class PythonAnalyzer : SimpleAnalyzeManager<Unit>() {

    override fun analyze(
        text: StringBuilder,
        delegate: SimpleAnalyzeManager<Unit>.Delegate<Unit>
    ): Styles {
        val builder = MappedSpans.Builder()
        val lineCount = if (text.length <= MAX_HIGHLIGHT_CHARS) {
            PythonHighlighter.tokenize(text) { line, column, kind ->
                builder.addIfNeeded(line, column, if (kind == null) NORMAL_STYLE else styleOf(kind))
            }
        } else {
            // Huge files stay plain instead of paying a full scan per keystroke.
            1 + text.count { it == '\n' }
        }
        // Lines without a single token still need a span at column 0.
        builder.determine(lineCount - 1)
        builder.addNormalIfNull()
        return Styles(builder.build(), true)
    }
}

private fun styleOf(kind: PythonTokenKind): Long = when (kind) {
    PythonTokenKind.KEYWORD -> TextStyle.makeStyle(EditorColorScheme.KEYWORD)
    PythonTokenKind.STRING -> TextStyle.makeStyle(EditorColorScheme.LITERAL)
    PythonTokenKind.COMMENT -> TextStyle.makeStyle(EditorColorScheme.COMMENT)
    PythonTokenKind.NUMBER -> TextStyle.makeStyle(EditorColorScheme.LITERAL)
    PythonTokenKind.DECORATOR -> TextStyle.makeStyle(EditorColorScheme.ANNOTATION)
    PythonTokenKind.FUNCTION,
    PythonTokenKind.BUILTIN -> TextStyle.makeStyle(EditorColorScheme.FUNCTION_NAME)
}

private val NORMAL_STYLE: Long = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)

private const val MAX_HIGHLIGHT_CHARS = 300_000
