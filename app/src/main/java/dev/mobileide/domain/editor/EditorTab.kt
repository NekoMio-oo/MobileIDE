package dev.mobileide.domain.editor

import dev.mobileide.domain.repository.ProjectFileError

/** Load state of a tab's text. */
enum class EditorTabStatus {
    /** Text is being read from disk. */
    LOADING,

    /** Text is available for editing. */
    READY,

    /** The file no longer exists on disk; saving is refused. */
    MISSING,

    /** Reading failed (binary, too large, permission, IO). */
    FAILED
}

/**
 * One open editor tab. [id] is the real absolute path and stays unique for the
 * lifetime of the tab; [relativePath] is what the repository uses.
 */
data class EditorTab(
    val id: String,
    val projectId: Long,
    val relativePath: String,
    val name: String,
    val language: EditorLanguage,
    val lineEnding: String = "\n",
    val status: EditorTabStatus = EditorTabStatus.LOADING,
    val isDirty: Boolean = false,
    val externallyModified: Boolean = false,
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
    val scrollY: Int = 0,
    val baselineModified: Long = 0L,
    val baselineSize: Long = 0L,
    val loadError: ProjectFileError? = null,
    val loadErrorDetail: String? = null
)

/** A snapshot of the editor view for one tab. */
data class EditorSnapshot(
    val text: String,
    val cursorLine: Int,
    val cursorColumn: Int,
    val scrollY: Int
)

/** Reactive tab collection for one project. */
data class EditorTabsState(
    val projectId: Long = 0L,
    val tabs: List<EditorTab> = emptyList(),
    val activeTabId: String? = null,
    /** Bumped whenever the editor must load a different document. */
    val revision: Long = 0L
) {
    val activeTab: EditorTab?
        get() = tabs.firstOrNull { it.id == activeTabId }
}

/** Cached text and caret of a tab, kept outside the UI state for performance. */
data class EditorTabBuffer(
    val text: String,
    val cursorLine: Int,
    val cursorColumn: Int,
    val scrollY: Int
)

sealed interface OpenTabResult {
    data class Created(val tabId: String) : OpenTabResult
    data class AlreadyOpen(val tabId: String) : OpenTabResult
    data object LimitReached : OpenTabResult
}

/** Temporary, process-death-safe representation of a tab. */
data class PersistedTab(
    val relativePath: String,
    val name: String,
    val lineEnding: String,
    val cursorLine: Int,
    val cursorColumn: Int,
    val scrollY: Int,
    val baselineModified: Long,
    val baselineSize: Long,
    val dirty: Boolean,
    val missing: Boolean,
    /** Only stored for dirty tabs; clean tabs are re-read from disk. */
    val text: String?
)

data class PersistedEditorSession(
    val projectId: Long,
    val activeRelativePath: String?,
    val tabs: List<PersistedTab>
)
