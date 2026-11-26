package dev.mobileide.feature.editor.model

import dev.mobileide.domain.editor.EditorSnapshot
import dev.mobileide.domain.editor.EditorTab
import dev.mobileide.domain.repository.ProjectFileError

enum class CloseScope {
    TABS,
    OTHERS,
    ALL,
    EXIT
}

/** Pending unsaved-changes decision for one or more tabs. */
data class CloseDialogState(
    val scope: CloseScope,
    val dirtyTabIds: List<String>,
    val dirtyNames: List<String>,
    val activeTabId: String?,
    val activeSnapshot: EditorSnapshot,
    val isSaving: Boolean = false,
    val failedNames: List<String> = emptyList(),
    val changedNames: List<String> = emptyList()
)

/** A save was blocked because the file changed on disk. */
data class ExternalChangeState(
    val tabId: String,
    val fileName: String,
    val snapshot: EditorSnapshot,
    val isWorking: Boolean = false,
    val error: ProjectFileError? = null,
    val errorDetail: String? = null
)

/** Result summary of "save all", shown as a banner. */
data class SaveReport(
    val failedNames: List<String>,
    val changedNames: List<String>
)

data class EditorUiState(
    val isLoading: Boolean = true,
    val projectName: String = "",
    val projectPath: String = "",
    val tabs: List<EditorTab> = emptyList(),
    val activeTabId: String? = null,
    val activationRevision: Long = 0L,
    val loadError: ProjectFileError? = null,
    val loadErrorDetail: String? = null,
    val isSaving: Boolean = false,
    val saveError: ProjectFileError? = null,
    val saveErrorDetail: String? = null,
    val saveReport: SaveReport? = null,
    val closeDialog: CloseDialogState? = null,
    val externalChange: ExternalChangeState? = null,
    val tabLimitReached: Boolean = false,
    val exitRequested: Boolean = false
) {
    val activeTab: EditorTab?
        get() = tabs.firstOrNull { it.id == activeTabId }

    val hasDirtyTabs: Boolean
        get() = tabs.any { it.isDirty }
}
