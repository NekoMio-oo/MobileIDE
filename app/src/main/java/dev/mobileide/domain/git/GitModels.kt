package dev.mobileide.domain.git

/**
 * How one path changed in the index or in the working tree.
 *
 * The enum is shared by the porcelain parser, the UI sections and the diff
 * loader; the raw `XY` characters are kept on [GitFileChange] so a file that is
 * both staged and modified can appear in both lists.
 */
enum class GitChangeKind {
    MODIFIED,
    ADDED,
    DELETED,
    RENAMED,
    COPIED,
    TYPE_CHANGED,
    UNTRACKED,
    CONFLICTED,
    UNKNOWN
}

/**
 * One entry of `git status --porcelain=v2`. [path] is project-relative with
 * forward slashes; [originalPath] is present for renames and copies.
 */
data class GitFileChange(
    val path: String,
    val originalPath: String? = null,
    val indexStatus: Char = '.',
    val workTreeStatus: Char = '.',
    val indexChange: GitChangeKind? = null,
    val workTreeChange: GitChangeKind? = null,
    val untracked: Boolean = false,
    val conflicted: Boolean = false
) {
    /** True when the index differs from HEAD, i.e. what a commit would include. */
    val isStaged: Boolean
        get() = !untracked && !conflicted && indexChange != null

    /** True when the worktree differs from the index. */
    val hasUnstaged: Boolean
        get() = !untracked && !conflicted && workTreeChange != null

    /** Best single label for compact lists. */
    val displayChange: GitChangeKind
        get() = when {
            conflicted -> GitChangeKind.CONFLICTED
            untracked -> GitChangeKind.UNTRACKED
            else -> indexChange ?: workTreeChange ?: GitChangeKind.UNKNOWN
        }
}

/** Parsed `git status --porcelain=v2 --branch` output. */
data class GitRepoStatus(
    val branch: String? = null,
    val detached: Boolean = false,
    val headCommit: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val changes: List<GitFileChange> = emptyList(),
    /** True when the output hit a cap and the list is incomplete. */
    val truncated: Boolean = false
) {
    val staged: List<GitFileChange>
        get() = changes.filter { it.isStaged }

    val unstaged: List<GitFileChange>
        get() = changes.filter { it.hasUnstaged }

    val untracked: List<GitFileChange>
        get() = changes.filter { it.untracked }

    val conflicted: List<GitFileChange>
        get() = changes.filter { it.conflicted }

    val isClean: Boolean
        get() = changes.isEmpty()
}

/** Repository details shown on the overview card. */
data class GitRepoInfo(
    val projectPath: String,
    val gitDirPath: String,
    val branch: String? = null,
    val detached: Boolean = false,
    val headCommit: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0
)

/** Why the Git panel cannot show a usable repository. */
enum class GitAvailability {
    CHECKING,
    LINUX_MISSING,
    GIT_MISSING,
    NOT_A_REPO,
    READY,
    ERROR
}

/** Long-running work shown as a banner, independent of individual commands. */
enum class GitOperationKind {
    INSTALL,
    INIT,
    REFRESH,
    STAGE,
    UNSTAGE,
    DISCARD,
    COMMIT,
    BRANCH,
    REMOTE,
    FETCH,
    PULL,
    PUSH,
    SSH,
    DIFF
}

data class GitOperation(
    val kind: GitOperationKind,
    val title: String,
    val detail: String? = null,
    val progress: Float? = null,
    /** False for short commands that should not expose a cancel button. */
    val cancellable: Boolean = true
)

enum class GitErrorKind {
    LINUX_MISSING,
    GIT_MISSING,
    NOT_A_REPO,
    INVALID_PROJECT,
    INVALID_PATH,
    NOT_FOUND,
    PERMISSION_DENIED,
    AUTH_FAILED,
    NETWORK,
    CONFLICT,
    DIRTY_WORKTREE,
    BRANCH_NOT_MERGED,
    IDENTITY_MISSING,
    LOCKED,
    TIMEOUT,
    CANCELLED,
    OUTPUT_TRUNCATED,
    IO,
    UNKNOWN
}

/**
 * A failed Git action. [message] is short and user-facing; [detail] keeps the
 * tail of stderr for diagnostics and is already stripped of credentials.
 */
data class GitError(
    val kind: GitErrorKind,
    val message: String,
    val detail: String? = null
)

/** Full state of the Git panel for one project. */
data class GitSnapshot(
    val projectId: Long = 0L,
    val projectName: String = "",
    val projectPath: String = "",
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val availability: GitAvailability = GitAvailability.CHECKING,
    val linuxInstalled: Boolean = false,
    val gitVersion: String? = null,
    /** A `.git` entry exists on disk; only a hint until `rev-parse` confirms. */
    val hasGitDir: Boolean = false,
    val repo: GitRepoInfo? = null,
    val status: GitRepoStatus? = null,
    val operation: GitOperation? = null,
    val message: String? = null,
    val error: GitError? = null
)

data class GitInstallResult(
    val success: Boolean,
    val message: String? = null,
    val detail: String? = null
)

sealed interface GitActionResult {
    data object Success : GitActionResult
    data class Failure(val error: GitError) : GitActionResult
}

// ---- Diff -------------------------------------------------------------------

enum class GitDiffLineKind {
    CONTEXT,
    ADDED,
    REMOVED
}

/**
 * One rendered diff line. [oldLine] and [newLine] are null on the side where the
 * line does not exist, which keeps the two gutter columns independent.
 */
data class GitDiffLine(
    val kind: GitDiffLineKind,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null
)

data class GitDiffHunk(
    val header: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<GitDiffLine> = emptyList()
)

data class GitDiffFile(
    val path: String,
    val oldPath: String? = null,
    val isBinary: Boolean = false,
    /** The file exceeds the editor/preview limit and was not read. */
    val isTooLarge: Boolean = false,
    val hunks: List<GitDiffHunk> = emptyList()
) {
    val addedLines: Int
        get() = hunks.sumOf { hunk ->
            hunk.lines.count { it.kind == GitDiffLineKind.ADDED }
        }

    val removedLines: Int
        get() = hunks.sumOf { hunk ->
            hunk.lines.count { it.kind == GitDiffLineKind.REMOVED }
        }
}

data class GitDiff(
    val files: List<GitDiffFile>,
    /** The output was cut at the line/byte cap. */
    val truncated: Boolean = false
) {
    val addedLines: Int
        get() = files.sumOf { it.addedLines }

    val removedLines: Int
        get() = files.sumOf { it.removedLines }

    val hasContent: Boolean
        get() = files.any { it.hunks.isNotEmpty() || it.isBinary || it.isTooLarge }
}

sealed interface GitDiffResult {
    data class Success(val diff: GitDiff) : GitDiffResult
    data class Failure(val error: GitError) : GitDiffResult
}

// ---- Commits and history -----------------------------------------------------

data class GitCommit(
    val hash: String,
    val shortHash: String,
    val authorName: String,
    val authorEmail: String,
    /** ISO-8601 author date, e.g. 2026-11-27T18:08:10+08:00. */
    val date: String,
    val subject: String,
    val refs: List<String> = emptyList(),
    val parents: List<String> = emptyList()
) {
    val isMerge: Boolean
        get() = parents.size > 1
}

data class GitCommitFile(
    val path: String,
    val oldPath: String? = null,
    val change: GitChangeKind
)

data class GitCommitDetail(
    val commit: GitCommit,
    /** Full commit message, including the body. */
    val message: String,
    val files: List<GitCommitFile> = emptyList(),
    /** Local branches that contain this commit. */
    val branches: List<String> = emptyList(),
    /** Parent used for the file list and diffs; null for a root commit. */
    val compareParent: String? = null,
    val truncated: Boolean = false
)

sealed interface GitHistoryResult {
    data class Success(
        val commits: List<GitCommit>,
        val truncated: Boolean = false
    ) : GitHistoryResult

    data class Failure(val error: GitError) : GitHistoryResult
}

sealed interface GitCommitDetailResult {
    data class Success(val detail: GitCommitDetail) : GitCommitDetailResult
    data class Failure(val error: GitError) : GitCommitDetailResult
}

// ---- Branches ---------------------------------------------------------------

data class GitBranch(
    val name: String,
    val isCurrent: Boolean = false,
    val isRemote: Boolean = false,
    val shortHash: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val upstreamGone: Boolean = false
)

data class GitBranches(
    val local: List<GitBranch> = emptyList(),
    val remote: List<GitBranch> = emptyList()
)

sealed interface GitBranchesResult {
    data class Success(val branches: GitBranches) : GitBranchesResult
    data class Failure(val error: GitError) : GitBranchesResult
}

// ---- Remotes and credentials -------------------------------------------------

enum class GitRemoteAuth {
    HTTPS,
    SSH,
    OTHER
}

data class GitRemote(
    val name: String,
    val url: String,
    val pushUrl: String? = null,
    val host: String? = null,
    val auth: GitRemoteAuth = GitRemoteAuth.OTHER,
    /** An HTTPS token or an SSH key is available for this remote. */
    val hasCredential: Boolean = false,
    /** Stored HTTPS username; the token is never exposed. */
    val credentialUsername: String? = null
)

sealed interface GitRemotesResult {
    data class Success(val remotes: List<GitRemote>) : GitRemotesResult
    data class Failure(val error: GitError) : GitRemotesResult
}

data class GitIdentity(
    val name: String? = null,
    val email: String? = null
) {
    val isComplete: Boolean
        get() = !name.isNullOrBlank() && !email.isNullOrBlank()
}

data class GitSshKeyInfo(
    val hasKey: Boolean = false,
    val publicKey: String? = null,
    val privateKeyPath: String = "",
    val hasPassphrase: Boolean = false
)
