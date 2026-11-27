package dev.mobileide.feature.git.model

import dev.mobileide.domain.git.GitDiff
import dev.mobileide.domain.git.GitError
import dev.mobileide.domain.git.GitFileChange

/** What the full-screen diff view is showing. */
data class GitDiffRequest(
    val path: String,
    val staged: Boolean,
    val change: GitFileChange
)

data class GitDiffUiState(
    val request: GitDiffRequest,
    val isLoading: Boolean = true,
    val diff: GitDiff? = null,
    val error: GitError? = null
)
