package dev.mobileide.feature.git

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.mobileide.di.container
import dev.mobileide.domain.git.GitRepository
import dev.mobileide.domain.git.GitSnapshot
import kotlinx.coroutines.flow.StateFlow
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
