package dev.mobileide.feature.fileexplorer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.mobileide.di.container
import dev.mobileide.domain.repository.CreateProjectEntryResult
import dev.mobileide.domain.repository.DeleteProjectEntryResult
import dev.mobileide.domain.repository.EntryFailure
import dev.mobileide.domain.repository.ListProjectFilesResult
import dev.mobileide.domain.repository.ProjectFailure
import dev.mobileide.domain.repository.ProjectFileEntry
import dev.mobileide.domain.repository.ProjectFileError
import dev.mobileide.domain.repository.ProjectRepository
import dev.mobileide.domain.repository.RenameProjectEntryResult
import dev.mobileide.domain.repository.SearchProjectFilesResult
import dev.mobileide.domain.validation.EntryNameError
import dev.mobileide.domain.validation.EntryNameValidator
import dev.mobileide.feature.fileexplorer.model.DeleteRequestState
import dev.mobileide.feature.fileexplorer.model.EntryDialogMode
import dev.mobileide.feature.fileexplorer.model.EntryDialogState
import dev.mobileide.feature.fileexplorer.model.FileExplorerUiState
import dev.mobileide.feature.fileexplorer.model.SearchTypeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * Project file browser. Every mutation goes through [ProjectRepository], which
 * resolves project-relative paths against the real project root and guards
 * against escaping it.
 */
class FileExplorerViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val container = application.container
    private val repository: ProjectRepository = container.projectRepository
    private val storageAccess = container.allFilesAccess
    private val tabManager = container.editorTabManager
    private val projectId: Long =
        savedStateHandle.get<String>("projectId")?.toLongOrNull() ?: 0L

    /**
     * Optional folder to open straight away (artifact folder jumps). Absolute
     * paths and `..` are refused, so the route cannot escape the project.
     */
    private val initialDirectory: String = runCatching {
        val encoded = savedStateHandle.get<String>("dir").orEmpty()
        if (encoded.isBlank()) "" else String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrDefault("").let { path ->
        if (path.startsWith("/") || path.contains('\\') || path.split('/').any { it == ".." }) {
            ""
        } else {
            path
        }
    }

    private val _uiState = MutableStateFlow(FileExplorerUiState())
    val uiState: StateFlow<FileExplorerUiState> = _uiState.asStateFlow()

    private var listJob: Job? = null
    private var searchJob: Job? = null
    private var resumedOnce = false

    init {
        loadProject()
    }

    /** Re-checks storage access and refreshes after returning from Settings or the editor. */
    fun onScreenResumed() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        val state = _uiState.value
        val granted = storageAccess.hasAccess()
        if (!granted) {
            if (state.storageGranted || state.isLoading) {
                _uiState.update { it.copy(storageGranted = false, isLoading = false) }
            }
            return
        }
        if (!state.storageGranted || state.project == null) {
            _uiState.update { it.copy(storageGranted = true) }
            loadProject()
            return
        }
        refresh(silent = true)
    }

    private fun loadProject() {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val granted = storageAccess.hasAccess()
            _uiState.update {
                it.copy(
                    isLoading = true,
                    storageGranted = granted,
                    error = null,
                    errorDetail = null
                )
            }
            if (!granted) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }

            val project = runCatching { repository.getProject(projectId) }.getOrNull()
            if (project == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        project = null,
                        entries = emptyList(),
                        error = ProjectFileError.NOT_FOUND
                    )
                }
                return@launch
            }

            val access = runCatching { repository.checkAccess(projectId) }.getOrNull()
            if (access == null || !access.available) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        project = project,
                        entries = emptyList(),
                        error = access?.reason.toProjectFileError()
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(project = project, storageGranted = true) }
            runCatching { repository.markOpened(projectId) }
            loadDirectoryInternal(initialDirectory, showLoading = true)
        }
    }

    fun loadDirectory(path: String) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            loadDirectoryInternal(path, showLoading = true)
        }
    }

    private suspend fun loadDirectoryInternal(path: String, showLoading: Boolean) {
        if (showLoading) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    currentPath = path,
                    error = null,
                    errorDetail = null
                )
            }
        }
        val result = try {
            repository.listProjectFiles(projectId, path)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ListProjectFilesResult.Failure(ProjectFileError.IO, error.message)
        }
        when (result) {
            is ListProjectFilesResult.Success -> _uiState.update {
                it.copy(
                    isLoading = false,
                    currentPath = path,
                    entries = result.entries,
                    error = null,
                    errorDetail = null
                )
            }

            is ListProjectFilesResult.Failure -> _uiState.update {
                it.copy(
                    isLoading = false,
                    currentPath = path,
                    entries = if (showLoading) emptyList() else it.entries,
                    error = result.error,
                    errorDetail = result.detail
                )
            }
        }
    }

    fun openDirectory(name: String) {
        val current = _uiState.value.currentPath
        loadDirectory(if (current.isBlank()) name else "$current/$name")
    }

    fun goUp() {
        val current = _uiState.value.currentPath
        if (current.isBlank()) return
        loadDirectory(current.substringBeforeLast('/', ""))
    }

    fun refresh() {
        refresh(silent = false)
    }

    private fun refresh(silent: Boolean) {
        val state = _uiState.value
        if (state.isSearchMode) {
            scheduleSearch()
            return
        }
        listJob?.cancel()
        listJob = viewModelScope.launch {
            loadDirectoryInternal(state.currentPath, showLoading = !silent)
        }
    }

    fun retry() {
        val state = _uiState.value
        when {
            !state.storageGranted -> loadProject()
            state.project == null -> loadProject()
            state.isSearchMode -> scheduleSearch()
            else -> loadDirectory(state.currentPath)
        }
    }

    // ---- Search -----------------------------------------------------------

    fun startSearch() {
        _uiState.update {
            it.copy(isSearchMode = true, searchError = null, searchErrorDetail = null)
        }
    }

    fun exitSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                isSearchMode = false,
                searchQuery = "",
                searchResults = emptyList(),
                isSearching = false,
                searchError = null,
                searchErrorDetail = null
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        scheduleSearch()
    }

    fun onSearchFilterSelected(filter: SearchTypeFilter) {
        if (filter == _uiState.value.searchFilter) return
        _uiState.update { it.copy(searchFilter = filter) }
        scheduleSearch()
    }

    /** Folder results navigate into that folder and leave search mode. */
    fun openSearchFolder(entry: ProjectFileEntry) {
        if (!entry.isDirectory) return
        exitSearch()
        loadDirectory(entry.path)
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        val state = _uiState.value
        val query = state.searchQuery.trim()
        if (query.isEmpty()) {
            _uiState.update {
                it.copy(
                    searchResults = emptyList(),
                    isSearching = false,
                    searchError = null,
                    searchErrorDetail = null
                )
            }
            return
        }

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _uiState.update {
                it.copy(isSearching = true, searchError = null, searchErrorDetail = null)
            }
            val result = try {
                repository.searchProjectFiles(
                    projectId = projectId,
                    query = query,
                    extensions = state.searchFilter.extensions
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SearchProjectFilesResult.Failure(ProjectFileError.IO, error.message)
            }
            if (!isActive) return@launch
            when (result) {
                is SearchProjectFilesResult.Success -> _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = result.entries,
                        searchError = null,
                        searchErrorDetail = null
                    )
                }

                is SearchProjectFilesResult.Failure -> _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = emptyList(),
                        searchError = result.error,
                        searchErrorDetail = result.detail
                    )
                }
            }
        }
    }

    // ---- Create / rename --------------------------------------------------

    fun openCreateDialog(createFolder: Boolean) {
        _uiState.update {
            it.copy(
                entryDialog = EntryDialogState(
                    mode = if (createFolder) EntryDialogMode.CREATE_FOLDER
                    else EntryDialogMode.CREATE_FILE
                )
            )
        }
    }

    fun openRenameDialog(entry: ProjectFileEntry) {
        _uiState.update {
            it.copy(
                entryDialog = EntryDialogState(
                    mode = EntryDialogMode.RENAME,
                    name = entry.name,
                    target = entry
                )
            )
        }
    }

    fun onEntryNameChanged(name: String) {
        val dialog = _uiState.value.entryDialog ?: return
        _uiState.update {
            it.copy(
                entryDialog = dialog.copy(
                    name = name,
                    nameError = null,
                    failure = null,
                    failureDetail = null
                )
            )
        }
    }

    fun dismissEntryDialog() {
        if (_uiState.value.entryDialog?.isSubmitting == true) return
        _uiState.update { it.copy(entryDialog = null) }
    }

    fun submitEntryDialog() {
        val state = _uiState.value
        val dialog = state.entryDialog ?: return
        if (dialog.isSubmitting) return

        val normalized = EntryNameValidator.normalize(dialog.name)
        val nameError = EntryNameValidator.validate(normalized)
        if (nameError != null) {
            _uiState.update { it.copy(entryDialog = dialog.copy(nameError = nameError)) }
            return
        }

        _uiState.update {
            it.copy(
                entryDialog = dialog.copy(
                    isSubmitting = true,
                    nameError = null,
                    failure = null,
                    failureDetail = null
                )
            )
        }

        viewModelScope.launch {
            when (dialog.mode) {
                EntryDialogMode.CREATE_FILE,
                EntryDialogMode.CREATE_FOLDER -> createEntry(
                    directoryPath = state.currentPath,
                    name = normalized,
                    isDirectory = dialog.mode == EntryDialogMode.CREATE_FOLDER
                )

                EntryDialogMode.RENAME -> renameEntry(
                    target = dialog.target,
                    newName = normalized
                )
            }
        }
    }

    private suspend fun createEntry(
        directoryPath: String,
        name: String,
        isDirectory: Boolean
    ) {
        val result = try {
            repository.createProjectEntry(projectId, directoryPath, name, isDirectory)
        } catch (error: Exception) {
            CreateProjectEntryResult.Failure(EntryFailure.IO, error.message)
        }
        when (result) {
            is CreateProjectEntryResult.Success -> {
                _uiState.update { it.copy(entryDialog = null) }
                refreshAfterChange()
                if (!isDirectory) {
                    _uiState.update { it.copy(createdFile = result.entry) }
                }
            }

            is CreateProjectEntryResult.InvalidName -> updateDialogFailure(
                nameError = result.error
            )

            CreateProjectEntryResult.DuplicateName -> updateDialogFailure(
                failure = EntryFailure.DUPLICATE
            )

            is CreateProjectEntryResult.Failure -> updateDialogFailure(
                failure = result.reason,
                detail = result.detail
            )
        }
    }

    private suspend fun renameEntry(target: ProjectFileEntry?, newName: String) {
        if (target == null) {
            _uiState.update { it.copy(entryDialog = null) }
            return
        }
        val result = try {
            repository.renameProjectEntry(projectId, target.path, newName)
        } catch (error: Exception) {
            RenameProjectEntryResult.Failure(EntryFailure.IO, error.message)
        }
        when (result) {
            is RenameProjectEntryResult.Success -> {
                val projectPath = _uiState.value.project?.path
                if (projectPath != null) {
                    tabManager.onEntryRenamed(
                        projectId = projectId,
                        projectPath = projectPath,
                        oldRelativePath = target.path,
                        newRelativePath = result.entry.path
                    )
                    runCatching { tabManager.persist(projectId) }
                }
                _uiState.update { it.copy(entryDialog = null) }
                refreshAfterChange()
            }

            is RenameProjectEntryResult.InvalidName -> updateDialogFailure(
                nameError = result.error
            )

            RenameProjectEntryResult.DuplicateName -> updateDialogFailure(
                failure = EntryFailure.DUPLICATE
            )

            RenameProjectEntryResult.NotFound -> {
                _uiState.update { it.copy(entryDialog = null) }
                refreshAfterChange()
            }

            is RenameProjectEntryResult.Failure -> updateDialogFailure(
                failure = result.reason,
                detail = result.detail
            )
        }
    }

    private fun updateDialogFailure(
        nameError: EntryNameError? = null,
        failure: EntryFailure? = null,
        detail: String? = null
    ) {
        _uiState.update { state ->
            val dialog = state.entryDialog ?: return@update state
            state.copy(
                entryDialog = dialog.copy(
                    isSubmitting = false,
                    nameError = nameError,
                    failure = failure,
                    failureDetail = detail
                )
            )
        }
    }

    // ---- Delete -----------------------------------------------------------

    fun requestDelete(entry: ProjectFileEntry) {
        if (entry.path.isBlank()) return
        _uiState.update {
            it.copy(
                deleteRequest = DeleteRequestState(
                    entry = entry,
                    descendantCount = if (entry.isDirectory) null else 0
                )
            )
        }
        if (!entry.isDirectory) return
        viewModelScope.launch {
            val count = repository.countProjectEntries(projectId, entry.path)
            _uiState.update { state ->
                val request = state.deleteRequest
                if (request == null || request.entry.path != entry.path) {
                    state
                } else {
                    state.copy(deleteRequest = request.copy(descendantCount = count))
                }
            }
        }
    }

    fun dismissDelete() {
        if (_uiState.value.deleteRequest?.isDeleting == true) return
        _uiState.update { it.copy(deleteRequest = null) }
    }

    fun confirmDelete() {
        val request = _uiState.value.deleteRequest ?: return
        if (request.isDeleting) return
        _uiState.update {
            it.copy(
                deleteRequest = request.copy(
                    isDeleting = true,
                    failure = null,
                    failureDetail = null
                )
            )
        }
        viewModelScope.launch {
            val result = try {
                repository.deleteProjectEntry(projectId, request.entry.path)
            } catch (error: Exception) {
                DeleteProjectEntryResult.Failure(EntryFailure.IO, error.message)
            }
            when (result) {
                DeleteProjectEntryResult.Success -> {
                    tabManager.onEntryDeleted(projectId, request.entry.path)
                    runCatching { tabManager.persist(projectId) }
                    _uiState.update { it.copy(deleteRequest = null) }
                    refreshAfterChange()
                }

                is DeleteProjectEntryResult.Failure -> _uiState.update { state ->
                    state.copy(
                        deleteRequest = state.deleteRequest?.copy(
                            isDeleting = false,
                            failure = result.reason,
                            failureDetail = result.detail
                        )
                    )
                }
            }
        }
    }

    // ---- Details ----------------------------------------------------------

    fun showInfo(entry: ProjectFileEntry) {
        _uiState.update { it.copy(infoEntry = entry) }
    }

    fun dismissInfo() {
        _uiState.update { it.copy(infoEntry = null) }
    }

    fun consumeCreatedFile() {
        _uiState.update { it.copy(createdFile = null) }
    }

    private fun refreshAfterChange() {
        if (_uiState.value.isSearchMode) {
            scheduleSearch()
        } else {
            listJob?.cancel()
            listJob = viewModelScope.launch {
                loadDirectoryInternal(_uiState.value.currentPath, showLoading = false)
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}

private fun ProjectFailure?.toProjectFileError(): ProjectFileError = when (this) {
    ProjectFailure.PERMISSION_DENIED -> ProjectFileError.PERMISSION_DENIED
    ProjectFailure.NOT_FOUND -> ProjectFileError.NOT_FOUND
    ProjectFailure.INVALID_PATH -> ProjectFileError.INVALID_PATH
    ProjectFailure.IO, null -> ProjectFileError.IO
}
