package dev.mobileide.feature.git.model

import dev.mobileide.domain.git.GitDiff
import dev.mobileide.domain.git.GitError
import dev.mobileide.domain.git.GitFileChange

/** What the full-screen diff view is showing. */
data class GitDiffRequest(
    val path: String,
    val staged: Boolean,
    /** Set for working tree / index diffs. */
    val change: GitFileChange? = null,
    /** Set for a file inside a commit; the diff compares with the first parent. */
    val commitHash: String? = null
)

data class GitDiffUiState(
    val request: GitDiffRequest,
    val isLoading: Boolean = true,
    val diff: GitDiff? = null,
    val error: GitError? = null
)
