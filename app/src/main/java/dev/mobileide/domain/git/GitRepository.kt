package dev.mobileide.domain.git

import kotlinx.coroutines.flow.StateFlow

/**
 * Single entry point for Git repository state and operations.
 *
 * The UI only talks to this interface; it never starts PRoot processes or
 * assembles git arguments. Long-running actions (install, init, and later
 * fetch/pull/push) publish their progress through [GitSnapshot.operation] and
 * can be cancelled with [cancelOperation].
 */
interface GitRepository {

    /** Bumped after every successful mutation so lists can reload. */
    val revision: StateFlow<Long>

    /** State for one project; safe to call repeatedly and from any thread. */
    fun observe(projectId: Long): StateFlow<GitSnapshot>

    /**
     * Re-reads the project path, Linux/Git availability and repository status.
     * A failed refresh never throws: the reason is published in the snapshot.
     */
    suspend fun refresh(projectId: Long, force: Boolean = false)

    /**
     * Cheap hint used by the Git project list. It only checks for a `.git`
     * entry; [refresh] performs the authoritative `git rev-parse` check.
     */
    suspend fun isRepository(projectId: Long): Boolean

    /** Installs git, openssh-client and CA certificates inside the Linux runtime. */
    fun installGit(projectId: Long)

    /** Runs `git init` and then refreshes the panel. */
    fun initRepository(projectId: Long, initialBranch: String = "main")

    // ---- changes ----------------------------------------------------------

    /** Stages one path, including a deletion. */
    fun stage(projectId: Long, path: String)

    fun unstage(projectId: Long, path: String)

    /** `git add -A`; stages modifications, deletions and untracked files. */
    fun stageAll(projectId: Long)

    /** Restores the index from HEAD, or clears it in a repository without commits. */
    fun unstageAll(projectId: Long)

    /**
     * Discards unstaged changes for a tracked path, or deletes an untracked
     * one. [confirmed] must be true; callers show a destructive confirmation
     * before invoking this.
     */
    fun discardChanges(projectId: Long, change: GitFileChange, confirmed: Boolean)

    /**
     * Unified diff for one change. [staged] selects index vs HEAD; false selects
     * worktree vs index. Untracked files are rendered from their file content.
     */
    suspend fun diff(
        projectId: Long,
        change: GitFileChange,
        staged: Boolean
    ): GitDiffResult

    // ---- commits ----------------------------------------------------------

    /** Commits staged changes. An empty message or an empty index is rejected. */
    fun commit(projectId: Long, message: String)

    /** Latest commits, newest first, capped by [limit]. */
    suspend fun history(projectId: Long, limit: Int = DEFAULT_HISTORY_LIMIT): GitHistoryResult

    suspend fun commitDetail(projectId: Long, hash: String): GitCommitDetailResult

    /** Diff of one file in a commit; compared with the first parent. */
    suspend fun commitDiff(
        projectId: Long,
        hash: String,
        path: String
    ): GitDiffResult

    // ---- branches ---------------------------------------------------------

    suspend fun branches(projectId: Long): GitBranchesResult

    /**
     * Creates and switches to a new branch. [startPoint] may be a local or
     * remote ref; [track] makes the new branch track it. Unsafe start points
     * are blocked when the worktree or editor has unsaved changes.
     */
    fun createBranch(
        projectId: Long,
        name: String,
        startPoint: String? = null,
        track: Boolean = false,
        dirtyEditorFiles: List<String> = emptyList()
    )

    /** Switches branches; blocked by tracked changes or dirty editor tabs. */
    fun switchBranch(
        projectId: Long,
        name: String,
        dirtyEditorFiles: List<String> = emptyList()
    )

    /** Deletes only fully merged branches (`git branch -d`, never `-D`). */
    fun deleteBranch(projectId: Long, name: String)

    // ---- remotes ----------------------------------------------------------

    suspend fun remotes(projectId: Long): GitRemotesResult

    fun addRemote(projectId: Long, name: String, url: String)

    fun setRemoteUrl(projectId: Long, name: String, url: String)

    fun removeRemote(projectId: Long, name: String)

    fun fetchRemote(projectId: Long, remote: String)

    /** Fast-forward only; blocked by tracked changes or dirty editor tabs. */
    fun pullRemote(
        projectId: Long,
        remote: String,
        dirtyEditorFiles: List<String> = emptyList()
    )

    /** Pushes the current branch; never force-pushes. */
    fun pushRemote(projectId: Long, remote: String)

    // ---- identity and credentials -----------------------------------------

    suspend fun identity(): GitIdentity

    suspend fun saveIdentity(projectId: Long, name: String, email: String): GitActionResult

    suspend fun saveHttpsCredential(
        projectId: Long,
        host: String,
        username: String,
        token: String
    ): GitActionResult

    suspend fun clearHttpsCredential(projectId: Long, host: String): GitActionResult

    suspend fun sshKeyInfo(): GitSshKeyInfo

    /** Generates an ed25519 key without a passphrase and shows the public key. */
    fun generateSshKey(projectId: Long)

    /** Imports a private key (and optional public key/passphrase). */
    fun importSshKey(
        projectId: Long,
        privateKey: String,
        publicKey: String?,
        passphrase: String?
    )

    fun deleteSshKey(projectId: Long)

    fun cancelOperation()

    fun clearMessage(projectId: Long)

    fun clearError(projectId: Long)

    companion object {
        const val DEFAULT_HISTORY_LIMIT = 200
    }
}
