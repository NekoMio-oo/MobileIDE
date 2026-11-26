package dev.mobileide.feature.editor.lsp

import android.os.Bundle
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch

/**
 * Sora language that keeps the phase-4 syntax highlighting and adds
 * server-driven completion.
 *
 * Plain delegation is not used because Kotlin generates a final member for a
 * delegated method, which cannot then be replaced by the LSP implementation.
 *
 * Sora calls [requireAutoComplete] on its own completion thread, so a short
 * blocking round-trip to the language server is allowed here; the editor's
 * input path never waits on it. Empty results simply close the popup.
 */
class LspLanguage(
    private val delegate: Language,
    private val projectId: Long,
    private val relativePath: String,
    private val source: LspCompletionSource
) : Language {

    override fun getAnalyzeManager(): AnalyzeManager = delegate.analyzeManager

    override fun getInterruptionLevel(): Int = delegate.interruptionLevel

    override fun getFormatter(): Formatter = delegate.formatter

    override fun getSymbolPairs(): SymbolPairMatch = delegate.symbolPairs

    @Suppress("UNCHECKED_CAST")
    override fun getNewlineHandlers(): Array<NewlineHandler> =
        delegate.newlineHandlers as Array<NewlineHandler>

    override fun useTab(): Boolean = delegate.useTab()

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int =
        delegate.getIndentAdvance(content, line, column)

    override fun destroy() {
        delegate.destroy()
    }

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        val items = runCatching {
            source.completion(projectId, relativePath, position.line, position.column)
        }.getOrDefault(emptyList())
        if (items.isEmpty()) {
            publisher.updateList()
            return
        }
        val prefix = prefixLengthAt(content, position)
        publisher.addItems(items.map { SoraLspCompletionItem(it, prefix) })
        publisher.updateList()
    }
}
