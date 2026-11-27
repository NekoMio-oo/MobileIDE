package dev.mobileide.feature.git.model

import dev.mobileide.domain.git.GitBranch
import dev.mobileide.domain.git.GitCommit
import dev.mobileide.domain.git.GitCommitDetail
import dev.mobileide.domain.git.GitError
import dev.mobileide.domain.git.GitIdentity
import dev.mobileide.domain.git.GitRemote
import dev.mobileide.domain.git.GitSshKeyInfo
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

/** Loaded commit list plus the client-side search query. */
data class GitHistoryUiState(
    val isLoading: Boolean = false,
    val commits: List<GitCommit> = emptyList(),
    val query: String = "",
    val truncated: Boolean = false,
    val error: GitError? = null
)

/** Full-screen commit detail; [hash] is kept while the request is loading. */
data class GitCommitDetailUiState(
    val hash: String,
    val isLoading: Boolean = true,
    val detail: GitCommitDetail? = null,
    val error: GitError? = null
)

/** Local and remote branches for the Branches tab. */
data class GitBranchesUiState(
    val isLoading: Boolean = false,
    val local: List<GitBranch> = emptyList(),
    val remote: List<GitBranch> = emptyList(),
    val error: GitError? = null
)

/** Remotes, identity and SSH key status for the Remotes tab. */
data class GitRemotesUiState(
    val isLoading: Boolean = false,
    val remotes: List<GitRemote> = emptyList(),
    val identity: GitIdentity = GitIdentity(),
    val sshKey: GitSshKeyInfo = GitSshKeyInfo(),
    val error: GitError? = null
)

/** Open .gitignore editor; [exists] decides create vs edit. */
data class GitIgnoreUiState(
    val isLoading: Boolean = true,
    val exists: Boolean = false,
    val content: String = "",
    val isSaving: Boolean = false,
    val error: GitError? = null
)
