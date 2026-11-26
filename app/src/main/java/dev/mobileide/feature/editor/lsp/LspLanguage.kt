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
 */
class LspLanguage(
    private val delegate: Language,
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

    /**
     * Two sources, published in the order they can answer.
     *
     * 1. The delegate's own completion runs first and answers immediately. It
     *    is the lexical completion C-like files had before any server existed,
     *    and it keeps working when none is installed.
     * 2. The server answers later on another thread, so this method never waits
     *    for it: Sora creates a thread per request and blocks nothing else.
     *
     * Sora invalidates the ContentReference as soon as a newer request starts.
     * Everything the late answer needs is therefore read now, and the reference
     * is validated again before publishing so a stale answer is dropped rather
     * than inserted at a position the user has already left.
     */
    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        runCatching { delegate.requireAutoComplete(content, position, publisher, extraArguments) }

        val prefix = runCatching { prefixLengthAt(content, position) }.getOrDefault(0)
        val text = runCatching { content.reference.toString() }.getOrDefault("")

        runCatching {
            source.completion(relativePath, position.line, position.column, text) { items ->
                if (items.isEmpty()) return@completion
                if (runCatching { content.validateAccess() }.isFailure) return@completion
                runCatching {
                    publisher.addItems(items.map { SoraLspCompletionItem(it, prefix) })
                    publisher.updateList()
                }
            }
        }
    }
}
