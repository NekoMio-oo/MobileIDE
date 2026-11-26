package dev.mobileide.feature.createproject.model

import dev.mobileide.domain.model.ProjectType
import dev.mobileide.domain.validation.ProjectNameError

enum class CreateProjectError {
    PERMISSION_DENIED,
    ROOT_UNAVAILABLE,
    DUPLICATE_NAME,
    INVALID_PATH,
    IO
}

data class CreateProjectUiState(
    val name: String = "",
    val type: ProjectType = ProjectType.EMPTY,
    val rootPath: String = "",
    val hasStorageAccess: Boolean = false,
    val isRootReady: Boolean = false,
    val nameError: ProjectNameError? = null,
    val isCreating: Boolean = false,
    val createdProjectId: Long? = null,
    val error: CreateProjectError? = null,
    val errorDetail: String? = null
)
