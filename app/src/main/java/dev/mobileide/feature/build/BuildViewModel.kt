package dev.mobileide.feature.build

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.mobileide.R
import dev.mobileide.data.editor.EditorRevealTarget
import dev.mobileide.di.container
import dev.mobileide.domain.build.BuildActionResult
import dev.mobileide.domain.build.BuildArtifact
import dev.mobileide.domain.build.BuildProblem
import dev.mobileide.domain.build.BuildRepository
import dev.mobileide.domain.build.StartBuildResult
import dev.mobileide.domain.editor.EditorTabStatus
import dev.mobileide.feature.build.model.BuildPanel
import dev.mobileide.feature.build.model.BuildUiState
import dev.mobileide.feature.build.model.UnsavedBuildPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Build screen state holder. All compilation happens in the app-scoped build
 * manager, so leaving this screen does not stop a running build.
 */
class BuildViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val container = application.container
    private val repository: BuildRepository = container.buildRepository
    private val tabManager = container.editorTabManager
    private val revealRequests = container.editorRevealRequests
    private val projectId: Long =
        savedStateHandle.get<String>("projectId")?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(BuildUiState(projectId = projectId))
    val uiState: StateFlow<BuildUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observe(projectId).collect { snapshot ->
                _uiState.update { it.copy(snapshot = snapshot) }
            }
        }
        viewModelScope.launch { repository.load(projectId) }
    }

    /** Entry point for the Build button; warns about unsaved editor buffers. */
    fun requestBuild() {
        if (_uiState.value.isBuilding) return
        val dirty = tabManager.tabs(projectId)
            .filter { it.isDirty && it.status == EditorTabStatus.READY }
        if (dirty.isEmpty()) {
            startBuild()
        } else {
            _uiState.update {
                it.copy(unsavedPrompt = UnsavedBuildPrompt(dirty.map { tab -> tab.name }))
            }
        }
    }

    /** Re-runs the last build, with the same unsaved-buffer guard. */
    fun retry() {
        requestBuild()
    }

    fun buildAnyway() {
        _uiState.update { it.copy(unsavedPrompt = null) }
        startBuild()
    }

    fun dismissUnsavedPrompt() {
        _uiState.update { it.copy(unsavedPrompt = null) }
    }

    private fun startBuild() {
        when (val result = repository.startBuild(projectId)) {
            StartBuildResult.Started -> Unit
            StartBuildResult.Busy -> showMessage(
                getApplication<Application>().getString(R.string.build_busy)
            )

            is StartBuildResult.Failure -> showMessage(result.message)
        }
    }

    fun cancelBuild() {
        repository.cancelBuild(projectId)
    }

    fun refreshToolchain() {
        viewModelScope.launch { repository.refreshToolchain(projectId) }
    }

    fun selectPanel(panel: BuildPanel) {
        _uiState.update { it.copy(panel = panel) }
    }

    fun setAutoScroll(enabled: Boolean) {
        _uiState.update { it.copy(autoScroll = enabled) }
    }

    fun clearLogs() {
        repository.clearLogs(projectId)
    }

    fun showHistory(show: Boolean) {
        _uiState.update { it.copy(showHistory = show) }
    }

    fun openTaskLog(taskId: String) {
        viewModelScope.launch {
            when (val result = repository.openTaskLog(projectId, taskId)) {
                is BuildActionResult.Failure -> showMessage(result.message)
                BuildActionResult.Success -> _uiState.update {
                    it.copy(showHistory = false, panel = BuildPanel.LOG)
                }
            }
        }
    }

    fun requestDeleteArtifact(artifact: BuildArtifact) {
        _uiState.update { it.copy(deleteRequest = artifact) }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(deleteRequest = null) }
    }

    fun confirmDelete() {
        val artifact = _uiState.value.deleteRequest ?: return
        viewModelScope.launch {
            when (val result = repository.deleteArtifact(projectId, artifact.path)) {
                is BuildActionResult.Failure -> showMessage(result.message)
                BuildActionResult.Success -> Unit
            }
            _uiState.update { it.copy(deleteRequest = null) }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    /**
     * Remembers where to place the caret; the screen navigates to the editor,
     * which consumes the request once the file is loaded.
     */
    fun revealProblem(problem: BuildProblem) {
        val path = problem.path ?: return
        revealRequests.request(
            projectId,
            EditorRevealTarget(
                relativePath = path,
                line = problem.line,
                column = problem.column
            )
        )
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }
}
