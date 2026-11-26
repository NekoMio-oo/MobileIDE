package dev.mobileide.feature.editor.model

/** Actions exposed by the editor's language-server menu. */
enum class EditorLspAction {
    COMPLETE,
    HOVER,
    DEFINITION,
    REFERENCES,
    SYMBOLS,
    FORMAT,
    PROBLEMS
}

/**
 * What the active server can do. Only advertised capabilities become menu
 * items, so unsupported features never appear as dead entries.
 */
data class EditorLspMenu(
    val serverName: String? = null,
    val canComplete: Boolean = false,
    val canHover: Boolean = false,
    val canDefinition: Boolean = false,
    val canReferences: Boolean = false,
    val canSymbols: Boolean = false,
    val canFormat: Boolean = false,
    val problemCount: Int = 0
) {
    val hasAny: Boolean
        get() = canComplete || canHover || canDefinition || canReferences ||
            canSymbols || canFormat || problemCount > 0
}
