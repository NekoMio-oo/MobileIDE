package dev.mobileide.feature.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileide.core.storage.AllFilesAccess
import dev.mobileide.core.storage.ProjectRootStore
import dev.mobileide.di.container
import dev.mobileide.domain.repository.DeleteProjectResult
import dev.mobileide.domain.repository.OpenProjectResult
import dev.mobileide.domain.repository.ProjectRepository
import dev.mobileide.domain.repository.RelinkProjectResult
import dev.mobileide.feature.home.model.HomeUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ProjectRepository = application.container.projectRepository
    private val rootStore: ProjectRootStore = application.container.projectRootStore
    private val allFilesAccess: AllFilesAccess = application.container.allFilesAccess
    private val tabManager = application.container.editorTabManager
    private val lsp = application.container.lspRepository

    val uiState: StateFlow<HomeUiState> = repository.observeRecentProjects(RECENT_PROJECT_LIMIT)
        .map { projects -> HomeUiState(isLoading = false, recentProjects = projects) }
        .catch { emit(HomeUiState(isLoading = false, loadFailed = true)) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = HomeUiState()
        )

    fun projectRootPath(): String = rootStore.rootPath()

    fun externalStorageRoot(): String = allFilesAccess.externalStorageRoot()

    fun ensureProjectRoot() {
        viewModelScope.launch { rootStore.ensureRoot() }
    }

    suspend fun openProject(path: String): OpenProjectResult = repository.openProject(path)

    suspend fun checkAccess(projectId: Long): Boolean =
        repository.checkAccess(projectId).available

    suspend fun relinkProject(projectId: Long, path: String): RelinkProjectResult =
        repository.relinkProject(projectId, path)

    fun removeProject(projectId: Long) {
        viewModelScope.launch {
            val result = repository.removeProject(projectId)
            if (result is DeleteProjectResult.Success) {
                tabManager.onProjectRemoved(projectId)
                lsp.stopProject(projectId)
            }
        }
    }

    private companion object {
        const val RECENT_PROJECT_LIMIT = 4
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
