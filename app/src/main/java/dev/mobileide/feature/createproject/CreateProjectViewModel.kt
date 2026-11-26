package dev.mobileide.feature.createproject

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileide.core.storage.AllFilesAccess
import dev.mobileide.core.storage.ProjectRootStore
import dev.mobileide.di.container
import dev.mobileide.domain.model.ProjectType
import dev.mobileide.domain.repository.CreateProjectResult
import dev.mobileide.domain.repository.ProjectFailure
import dev.mobileide.domain.repository.ProjectRepository
import dev.mobileide.domain.validation.ProjectNameValidator
import dev.mobileide.feature.createproject.model.CreateProjectError
import dev.mobileide.feature.createproject.model.CreateProjectUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CreateProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ProjectRepository = application.container.projectRepository
    private val allFilesAccess: AllFilesAccess = application.container.allFilesAccess
    private val rootStore: ProjectRootStore = application.container.projectRootStore

    private val _uiState = MutableStateFlow(
        CreateProjectUiState(rootPath = rootStore.rootPath())
    )
    val uiState: StateFlow<CreateProjectUiState> = _uiState.asStateFlow()

    init {
        refreshStorageState()
    }

    fun refreshStorageState() {
        viewModelScope.launch {
            val access = allFilesAccess.hasAccess()
            val rootReady = access && rootStore.ensureRoot()
            _uiState.update { state ->
                state.copy(
                    hasStorageAccess = access,
                    isRootReady = rootReady,
                    rootPath = rootStore.rootPath(),
                    error = when {
                        access && !rootReady -> CreateProjectError.ROOT_UNAVAILABLE
                        rootReady -> null
                        else -> state.error
                    },
                    errorDetail = if (rootReady) null else state.errorDetail
                )
            }
        }
    }

    fun onStoragePermissionChanged(granted: Boolean) {
        if (granted) {
            refreshStorageState()
        } else {
            _uiState.update {
                it.copy(hasStorageAccess = false, isRootReady = false)
            }
        }
    }

    fun onNameChanged(value: String) {
        _uiState.update { it.copy(name = value, nameError = null, error = null, errorDetail = null) }
    }

    fun onTypeSelected(type: ProjectType) {
        _uiState.update { it.copy(type = type) }
    }

    fun create() {
        val state = _uiState.value
        if (state.isCreating) return

        val normalized = ProjectNameValidator.normalize(state.name)
        val nameError = ProjectNameValidator.validate(normalized)
        if (nameError != null) {
            _uiState.update { it.copy(nameError = nameError) }
            return
        }
        if (!state.hasStorageAccess) {
            _uiState.update { it.copy(error = CreateProjectError.PERMISSION_DENIED) }
            return
        }
        if (!state.isRootReady) {
            _uiState.update { it.copy(error = CreateProjectError.ROOT_UNAVAILABLE) }
            return
        }

        _uiState.update {
            it.copy(isCreating = true, error = null, errorDetail = null, nameError = null)
        }

        viewModelScope.launch {
            when (val result = repository.createProject(normalized, state.type, state.rootPath)) {
                is CreateProjectResult.Success -> _uiState.update {
                    it.copy(isCreating = false, createdProjectId = result.projectId)
                }

                is CreateProjectResult.InvalidName -> _uiState.update {
                    it.copy(isCreating = false, nameError = result.error)
                }

                CreateProjectResult.DuplicateName -> _uiState.update {
                    it.copy(isCreating = false, error = CreateProjectError.DUPLICATE_NAME)
                }

                is CreateProjectResult.Failure -> _uiState.update {
                    it.copy(
                        isCreating = false,
                        error = when (result.reason) {
                            ProjectFailure.PERMISSION_DENIED -> CreateProjectError.PERMISSION_DENIED
                            ProjectFailure.INVALID_PATH -> CreateProjectError.INVALID_PATH
                            else -> CreateProjectError.IO
                        },
                        errorDetail = result.detail
                    )
                }
            }
        }
    }

    fun consumeCreatedProject() {
        _uiState.update { it.copy(createdProjectId = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null, errorDetail = null) }
    }
}
