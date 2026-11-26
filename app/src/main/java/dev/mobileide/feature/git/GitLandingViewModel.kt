package dev.mobileide.feature.git

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileide.di.container
import dev.mobileide.feature.git.model.GitLandingUiState
import dev.mobileide.feature.git.model.GitProjectItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Lists projects for the top-level Git tab. The `.git` marker is only a hint;
 * the project panel performs the authoritative `git rev-parse` check.
 */
class GitLandingViewModel(application: Application) : AndroidViewModel(application) {

    private val projects = application.container.projectRepository
    private val git = application.container.gitRepository

    /** Re-checks the marker when the tab is shown again after init/clone. */
    private val refreshTick = MutableStateFlow(0L)

    val uiState: StateFlow<GitLandingUiState> =
        combine(projects.observeProjects(), refreshTick) { list, _ -> list }
            .map { list ->
                GitLandingUiState(
                    isLoading = false,
                    projects = list.map { project ->
                        GitProjectItem(
                            project = project,
                            hasRepository = git.isRepository(project.id)
                        )
                    }
                )
            }
            .catch { emit(GitLandingUiState(isLoading = false, loadFailed = true)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = GitLandingUiState()
            )

    fun refresh() {
        refreshTick.update { it + 1 }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
