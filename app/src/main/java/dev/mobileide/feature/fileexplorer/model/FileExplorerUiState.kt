package dev.mobileide.feature.fileexplorer.model

import androidx.annotation.StringRes
import dev.mobileide.R
import dev.mobileide.domain.model.Project
import dev.mobileide.domain.repository.EntryFailure
import dev.mobileide.domain.repository.ProjectFileEntry
import dev.mobileide.domain.repository.ProjectFileError
import dev.mobileide.domain.validation.EntryNameError

enum class EntryDialogMode {
    CREATE_FILE,
    CREATE_FOLDER,
    RENAME
}

data class EntryDialogState(
    val mode: EntryDialogMode,
    val name: String = "",
    val target: ProjectFileEntry? = null,
    val nameError: EntryNameError? = null,
    val failure: EntryFailure? = null,
    val failureDetail: String? = null,
    val isSubmitting: Boolean = false
)

data class DeleteRequestState(
    val entry: ProjectFileEntry,
    val descendantCount: Int? = null,
    val isDeleting: Boolean = false,
    val failure: EntryFailure? = null,
    val failureDetail: String? = null
)

/** Extension groups offered by the search filter. */
enum class SearchTypeFilter(
    @StringRes val labelRes: Int,
    val extensions: Set<String>
) {
    ANY(R.string.explorer_filter_any, emptySet()),
    C_FAMILY(
        R.string.explorer_filter_c,
        setOf("c", "h", "cpp", "hpp", "cc", "cxx", "hxx")
    ),
    PYTHON(R.string.explorer_filter_python, setOf("py")),
    JVM(R.string.explorer_filter_jvm, setOf("java", "kt", "kts")),
    WEB(
        R.string.explorer_filter_web,
        setOf("js", "mjs", "cjs", "jsx", "ts", "tsx", "html", "htm", "css")
    ),
    SYSTEMS(R.string.explorer_filter_systems, setOf("go", "rs")),
    DOCS(
        R.string.explorer_filter_docs,
        setOf("md", "markdown", "txt", "json", "xml", "yml", "yaml")
    )
}

data class FileExplorerUiState(
    val isLoading: Boolean = true,
    val project: Project? = null,
    val storageGranted: Boolean = true,
    val currentPath: String = "",
    val entries: List<ProjectFileEntry> = emptyList(),
    val error: ProjectFileError? = null,
    val errorDetail: String? = null,

    val isSearchMode: Boolean = false,
    val searchQuery: String = "",
    val searchFilter: SearchTypeFilter = SearchTypeFilter.ANY,
    val searchResults: List<ProjectFileEntry> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: ProjectFileError? = null,
    val searchErrorDetail: String? = null,

    val entryDialog: EntryDialogState? = null,
    val deleteRequest: DeleteRequestState? = null,
    val infoEntry: ProjectFileEntry? = null,
    val createdFile: ProjectFileEntry? = null
) {
    val canGoUp: Boolean
        get() = currentPath.isNotBlank()

    val currentFolderLabel: String
        get() = if (currentPath.isBlank()) "/" else "/$currentPath"
}
