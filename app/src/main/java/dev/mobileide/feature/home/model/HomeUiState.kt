package dev.mobileide.feature.home.model

import dev.mobileide.domain.model.Project

data class HomeUiState(
    val isLoading: Boolean = true,
    val recentProjects: List<Project> = emptyList(),
    val loadFailed: Boolean = false
)
