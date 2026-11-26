package dev.mobileide.feature.git.model

import dev.mobileide.domain.model.Project

/** One row of the Git project picker. */
data class GitProjectItem(
    val project: Project,
    val hasRepository: Boolean
)

data class GitLandingUiState(
    val isLoading: Boolean = true,
    val projects: List<GitProjectItem> = emptyList(),
    val loadFailed: Boolean = false
)
