package dev.mobileide.feature.editor

import android.os.Bundle
import dev.mobileide.domain.editor.EditorLanguage
import dev.mobileide.domain.editor.EditorSnapshot
import dev.mobileide.domain.lsp.LspDiagnostic
import dev.mobileide.domain.lsp.LspSeverity
import dev.mobileide.domain.lsp.LspTextEdit
import dev.mobileide.feature.editor.lsp.offsetOf
import dev.mobileide.feature.editor.components.toSoraLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.SelectionMovement

/**
 * Thin command surface over Sora's CodeEditor.
 *
 * Every tab owns its own [Content]: Sora stores the text, the caret and the undo
 * stack inside that object, so switching tabs restores all three without
 * re-reading the file. The ViewModel never touches Sora types.
 */
class EditorController {

    private var editor: CodeEditor? = null
    private val contents = mutableMapOf<String, Content>()
    private var applyingDocument = false
    private var appliedKey: String? = null

    /** True while a document is being injected; edit events must be ignored. */
    val isApplyingDocument: Boolean
        get() = applyingDocument

    fun attach(editor: CodeEditor) {
        this.editor = editor
    }

    fun release() {
        editor?.release()
        editor = null
        contents.clear()
        appliedKey = null
    }

    /**
     * Shows [text] for [tabId]. A cached Content of the same text is reused so
     * undo history and the caret survive tab switches; external reloads pass a
     * different text and therefore get a fresh Content.
     */
    fun renderTab(
        tabId: String,
        revision: Long,
        text: String,
        language: EditorLanguage,
        cursorLine: Int,
        cursorColumn: Int,
        wrapLanguage: ((EditorLanguage, String) -> Language)? = null
    ) {
        val current = editor ?: return
        val key = "$tabId@$revision"
        if (appliedKey == key) return

        applyingDocument = true
        try {
            val soraLanguage = wrapLanguage?.invoke(language, tabId) ?: language.toSoraLanguage()
            current.setEditorLanguage(soraLanguage)
            val cached = contents[tabId]
            val content = if (cached != null &&
                cached.length == text.length &&
                cached.toString() == text
            ) {
                cached
            } else {
                Content(text).also { contents[tabId] = it }
            }
            current.setText(content, true, Bundle())

            val line = cursorLine.coerceIn(0, maxOf(0, content.lineCount - 1))
            val column = cursorColumn.coerceIn(0, content.getColumnCount(line))
            current.setSelection(line, column)
            current.ensurePositionVisible(line, column)
            appliedKey = key
        } finally {
            applyingDocument = false
        }
    }

    /** Drops cached Contents of tabs that are no longer open. */
    fun retainTabs(tabIds: Set<String>) {
        val iterator = contents.keys.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() !in tabIds) iterator.remove()
        }
    }

    fun snapshot(): EditorSnapshot {
        val current = editor ?: return EditorSnapshot("", 0, 0, 0)
        val cursor = current.cursor
        return EditorSnapshot(
            text = current.text.toString(),
            cursorLine = cursor.leftLine,
            cursorColumn = cursor.leftColumn,
            scrollY = current.scroller.currY
        )
    }

    fun currentText(): String = editor?.text?.toString().orEmpty()

    /** Zero-based caret position, matching LSP coordinates. */
    fun cursorPosition(): Pair<Int, Int> {
        val cursor = editor?.cursor ?: return 0 to 0
        return cursor.leftLine to cursor.leftColumn
    }

    /** Asks Sora to build a completion window from the current language. */
    fun requestCompletion() {
        editor?.getComponent(EditorAutoCompletion::class.java)?.requireCompletion()
    }

    fun applyTextEdits(edits: List<LspTextEdit>): Boolean {
        val current = editor ?: return false
        if (edits.isEmpty()) return false
        val content = current.text
        val resolved = edits.map { edit ->
            Triple(
                offsetOf(content, edit.range.start),
                offsetOf(content, edit.range.end),
                edit.newText
            )
        }.sortedByDescending { it.first }

        content.beginBatchEdit()
        try {
            for ((start, end, text) in resolved) {
                val safeStart = start.coerceIn(0, content.length)
                val safeEnd = end.coerceIn(safeStart, content.length)
                content.replace(safeStart, safeEnd, text)
            }
            val line = current.cursor.leftLine.coerceIn(0, maxOf(0, content.lineCount - 1))
            val column = current.cursor.leftColumn.coerceIn(0, content.getColumnCount(line))
            current.setSelection(line, column)
        } finally {
            content.endBatchEdit()
        }
        return true
    }

    /** Maps LSP ranges onto Sora's character offsets. */
    fun showDiagnostics(diagnostics: List<LspDiagnostic>) {
        val current = editor ?: return
        val content = current.text
        val container = DiagnosticsContainer()
        if (content.length > 0) {
            diagnostics.take(MAX_DIAGNOSTICS).forEachIndexed { index, diagnostic ->
                val start = offsetOf(content, diagnostic.range.start)
                if (start >= content.length) return@forEachIndexed
                var end = offsetOf(content, diagnostic.range.end)
                if (end <= start) end = (start + 1).coerceAtMost(content.length)
                val severity = when (diagnostic.severity) {
                    LspSeverity.ERROR -> DiagnosticRegion.SEVERITY_ERROR
                    LspSeverity.WARNING -> DiagnosticRegion.SEVERITY_WARNING
                    else -> DiagnosticRegion.SEVERITY_TYPO
                }
                container.addDiagnostic(
                    DiagnosticRegion(
                        start,
                        end,
                        severity,
                        index.toLong(),
                        DiagnosticDetail(diagnostic.message, diagnostic.message)
                    )
                )
            }
        }
        current.setDiagnostics(container)
    }

    fun clearDiagnostics() {
        editor?.setDiagnostics(DiagnosticsContainer())
    }

    /** Moves the caret to a zero-based position and scrolls it into view. */
    fun revealPosition(line: Int, column: Int) {
        val current = editor ?: return
        val content = current.text
        val safeLine = line.coerceIn(0, maxOf(0, content.lineCount - 1))
        val safeColumn = column.coerceIn(0, content.getColumnCount(safeLine))
        current.setSelection(safeLine, safeColumn)
        current.ensurePositionVisible(safeLine, safeColumn)
    }

    fun insert(text: String) {
        val current = editor ?: return
        current.commitText(text)
        focusEditor(current)
    }

    /** Inserts a pair and places the caret between the two characters. */
    fun insertPair(open: String, close: String) {
        val current = editor ?: return
        current.commitText(open + close)
        current.moveSelection(SelectionMovement.LEFT)
        focusEditor(current)
    }

    fun undo() {
        editor?.undo()
        editor?.let(::focusEditor)
    }

    fun redo() {
        editor?.redo()
        editor?.let(::focusEditor)
    }

    fun canUndo(): Boolean = editor?.canUndo() == true

    fun canRedo(): Boolean = editor?.canRedo() == true

    fun selectAll() {
        editor?.selectAll()
        editor?.let(::focusEditor)
    }

    fun copy() {
        editor?.copyText()
    }

    fun cut() {
        editor?.cutText()
        editor?.let(::focusEditor)
    }

    fun paste() {
        editor?.pasteText()
        editor?.let(::focusEditor)
    }

    fun move(movement: SelectionMovement) {
        val current = editor ?: return
        current.moveSelection(movement)
        focusEditor(current)
    }

    fun search(query: String) {
        val searcher = editor?.searcher ?: return
        if (query.isBlank()) {
            searcher.stopSearch()
        } else {
            searcher.search(
                query,
                EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, true)
            )
        }
    }

    fun findNext(): Boolean = editor?.searcher?.gotoNext() == true

    fun findPrevious(): Boolean = editor?.searcher?.gotoPrevious() == true

    fun stopSearch() {
        editor?.searcher?.stopSearch()
    }

    private fun focusEditor(editor: CodeEditor) {
        editor.requestFocus()
        editor.showSoftInput()
    }

    private companion object {
        const val MAX_DIAGNOSTICS = 500
    }
}
