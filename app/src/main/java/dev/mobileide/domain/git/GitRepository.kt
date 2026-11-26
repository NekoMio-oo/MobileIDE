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

    fun cancelOperation()

    fun clearMessage(projectId: Long)

    fun clearError(projectId: Long)
}
