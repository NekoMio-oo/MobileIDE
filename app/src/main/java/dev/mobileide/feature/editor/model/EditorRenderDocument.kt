package dev.mobileide.feature.editor.model

import dev.mobileide.domain.editor.EditorLanguage

/**
 * What the Sora view should show right now. [revision] comes from the tab
 * manager and changes whenever the document must be re-injected.
 */
data class EditorRenderDocument(
    val tabId: String,
    val revision: Long,
    val text: String,
    val language: EditorLanguage,
    val cursorLine: Int,
    val cursorColumn: Int
)
