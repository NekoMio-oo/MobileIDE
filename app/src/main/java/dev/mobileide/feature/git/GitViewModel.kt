package dev.mobileide.feature.git

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.mobileide.di.container
import dev.mobileide.domain.git.GitDiffResult
import dev.mobileide.domain.git.GitFileChange
import dev.mobileide.domain.git.GitRepository
import dev.mobileide.domain.git.GitSnapshot
import dev.mobileide.feature.git.model.GitDiffRequest
import dev.mobileide.feature.git.model.GitDiffUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Binds the Git repository for one project to the panel. */
class GitViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repository: GitRepository = application.container.gitRepository
    private val projectId: Long =
        savedStateHandle.get<String>("projectId")?.toLongOrNull() ?: 0L

    val state: StateFlow<GitSnapshot> = repository.observe(projectId)

    private val _diffState = MutableStateFlow<GitDiffUiState?>(null)
    val diffState: StateFlow<GitDiffUiState?> = _diffState.asStateFlow()

    private var resumedOnce = false

    init {
        viewModelScope.launch { repository.refresh(projectId) }
    }

    /** Re-checks after returning from Runtimes; the first resume is the init load. */
    fun onScreenResumed() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh(projectId, force = true) }
    }

    fun installGit() {
        repository.installGit(projectId)
    }

    fun initRepository(branch: String) {
        repository.initRepository(projectId, branch)
    }

    fun stage(change: GitFileChange) {
        repository.stage(projectId, change.path)
    }

    fun unstage(change: GitFileChange) {
        repository.unstage(projectId, change.path)
    }

    fun stageAll() {
        repository.stageAll(projectId)
    }

    fun unstageAll() {
        repository.unstageAll(projectId)
    }

    fun discard(change: GitFileChange) {
        repository.discardChanges(projectId, change, confirmed = true)
    }

    fun openDiff(change: GitFileChange, staged: Boolean) {
        if (change.conflicted) return
        val request = GitDiffRequest(path = change.path, staged = staged, change = change)
        _diffState.value = GitDiffUiState(request = request)
        viewModelScope.launch {
            when (val result = repository.diff(projectId, change, staged)) {
                is GitDiffResult.Success -> _diffState.update { current ->
                    if (current?.request != request) {
                        current
                    } else {
                        current.copy(isLoading = false, diff = result.diff, error = null)
                    }
                }

                is GitDiffResult.Failure -> _diffState.update { current ->
                    if (current?.request != request) {
                        current
                    } else {
                        current.copy(isLoading = false, error = result.error)
                    }
                }
            }
        }
    }

    fun retryDiff() {
        val request = _diffState.value?.request ?: return
        openDiff(request.change, request.staged)
    }

    fun closeDiff() {
        _diffState.value = null
    }

    fun cancelOperation() {
        repository.cancelOperation()
    }

    fun dismissError() {
        repository.clearError(projectId)
    }

    fun dismissMessage() {
        repository.clearMessage(projectId)
    }
}
