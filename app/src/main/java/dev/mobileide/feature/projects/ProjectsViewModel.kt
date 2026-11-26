package dev.mobileide.feature.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileide.di.container
import dev.mobileide.domain.model.Project
import dev.mobileide.domain.repository.DeleteProjectResult
import dev.mobileide.domain.repository.ProjectRepository
import dev.mobileide.domain.repository.RelinkProjectResult
import dev.mobileide.domain.repository.RenameProjectResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ProjectsUiState(
    val isLoading: Boolean = true,
    val projects: List<Project> = emptyList(),
    val query: String = "",
    val loadFailed: Boolean = false
) {
    val visibleProjects: List<Project>
        get() = if (query.isBlank()) {
            projects
        } else {
            projects.filter { project ->
                project.name.contains(query, ignoreCase = true) ||
                    project.locationName.contains(query, ignoreCase = true) ||
                    project.type.label.contains(query, ignoreCase = true)
            }
        }
}

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ProjectRepository = application.container.projectRepository
    private val tabManager = application.container.editorTabManager
    private val lsp = application.container.lspRepository
    private val query = MutableStateFlow("")

    val uiState: StateFlow<ProjectsUiState> =
        combine(repository.observeProjects(), query) { projects, currentQuery ->
            ProjectsUiState(
                isLoading = false,
                projects = projects,
                query = currentQuery
            )
        }
            .catch { emit(ProjectsUiState(isLoading = false, loadFailed = true)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ProjectsUiState()
            )

    fun onQueryChanged(value: String) {
        query.value = value
    }

    suspend fun rename(projectId: Long, newName: String): RenameProjectResult =
        repository.renameProject(projectId, newName)

    suspend fun remove(projectId: Long): DeleteProjectResult {
        val result = repository.removeProject(projectId)
        if (result is DeleteProjectResult.Success) {
            tabManager.onProjectRemoved(projectId)
            lsp.stopProject(projectId)
        }
        return result
    }

    suspend fun deleteFiles(projectId: Long): DeleteProjectResult {
        val result = repository.deleteProjectFiles(projectId)
        if (result is DeleteProjectResult.Success) {
            tabManager.onProjectRemoved(projectId)
            lsp.stopProject(projectId)
        }
        return result
    }

    suspend fun relink(projectId: Long, path: String): RelinkProjectResult =
        repository.relinkProject(projectId, path)

    suspend fun checkAccess(projectId: Long): Boolean =
        repository.checkAccess(projectId).available

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
