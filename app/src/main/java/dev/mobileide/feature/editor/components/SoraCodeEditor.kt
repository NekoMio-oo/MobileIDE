package dev.mobileide.feature.editor.components

import android.graphics.Typeface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.mobileide.domain.editor.EditorLanguage
import dev.mobileide.feature.editor.EditorController
import dev.mobileide.feature.editor.model.EditorRenderDocument
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

private const val EDITOR_TEXT_SIZE_SP = 14.5f
private const val EDITOR_TAB_WIDTH = 4

/**
 * Compose host for Sora's CodeEditor.
 *
 * One editor view serves every tab; [EditorController] swaps the underlying
 * Content per tab, keeping text, caret and undo history. The view is created
 * once and only recoloured on recomposition.
 */
@Composable
fun SoraCodeEditor(
    controller: EditorController,
    document: EditorRenderDocument?,
    colorScheme: EditorColorScheme,
    onContentChanged: () -> Unit,
    onCursorChanged: (line: Int, column: Int) -> Unit,
    wrapLanguage: (EditorLanguage, String) -> Language,
    modifier: Modifier = Modifier
) {
    val currentDocument by rememberUpdatedState(document)
    val currentOnContentChanged by rememberUpdatedState(onContentChanged)
    val currentOnCursorChanged by rememberUpdatedState(onCursorChanged)
    val currentWrapLanguage by rememberUpdatedState(wrapLanguage)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            CodeEditor(context).apply {
                setTypefaceText(Typeface.MONOSPACE)
                setTypefaceLineNumber(Typeface.MONOSPACE)
                setTextSize(EDITOR_TEXT_SIZE_SP)
                setTabWidth(EDITOR_TAB_WIDTH)
                setLineNumberEnabled(true)
                setHighlightCurrentLine(true)
                setHighlightCurrentBlock(true)
                setWordwrap(false)
                setScrollBarEnabled(true)
                setColorScheme(colorScheme)
                setText("")
                setSelection(0, 0)
                controller.attach(this)

                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                    if (!controller.isApplyingDocument) currentOnContentChanged()
                }
                subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                    if (!controller.isApplyingDocument) {
                        currentOnCursorChanged(event.left.line + 1, event.left.column + 1)
                    }
                }

                currentDocument?.let { doc ->
                    controller.renderTab(
                        tabId = doc.tabId,
                        revision = doc.revision,
                        text = doc.text,
                        language = doc.language,
                        cursorLine = doc.cursorLine,
                        cursorColumn = doc.cursorColumn,
                        wrapLanguage = currentWrapLanguage
                    )
                }
            }
        },
        update = { editor ->
            editor.setColorScheme(colorScheme)
            currentDocument?.let { doc ->
                controller.renderTab(
                    tabId = doc.tabId,
                    revision = doc.revision,
                    text = doc.text,
                    language = doc.language,
                    cursorLine = doc.cursorLine,
                    cursorColumn = doc.cursorColumn,
                    wrapLanguage = currentWrapLanguage
                )
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose { controller.release() }
    }
}
