package dev.mobileide.data.git

import dev.mobileide.domain.git.GitAvailability
import dev.mobileide.domain.git.GitDiff
import dev.mobileide.domain.git.GitDiffFile
import dev.mobileide.domain.git.GitDiffHunk
import dev.mobileide.domain.git.GitDiffLine
import dev.mobileide.domain.git.GitDiffLineKind
import dev.mobileide.domain.git.GitDiffResult
import dev.mobileide.domain.git.GitError
import dev.mobileide.domain.git.GitErrorKind
import dev.mobileide.domain.git.GitFileChange
import dev.mobileide.domain.git.GitOperation
import dev.mobileide.domain.git.GitOperationKind
import dev.mobileide.domain.git.GitRepoInfo
import dev.mobileide.domain.git.GitRepository
import dev.mobileide.domain.git.GitSnapshot
import dev.mobileide.domain.repository.DeleteProjectEntryResult
import dev.mobileide.domain.repository.EntryFailure
import dev.mobileide.domain.repository.ProjectFileError
import dev.mobileide.domain.repository.ProjectRepository
import dev.mobileide.domain.repository.ReadProjectFileResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Git state and operations for every project.
 *
 * Detection and status run real `git` commands through [runner]; nothing is
 * inferred from a directory name. Only one operation may run at a time so two
 * commands can never race on `.git/index.lock`.
 *
 * Installation and initialization are fire-and-forget and publish their
 * progress through [GitSnapshot.operation], matching the runtime/LSP screens.
 */
class GitRepositoryImpl(
    private val projects: ProjectRepository,
    private val runner: GitCommandRunner,
    private val installer: GitInstaller,
    private val linuxInstalled: () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : GitRepository {

    private val states = ConcurrentHashMap<Long, MutableStateFlow<GitSnapshot>>()
    private val operationLock = Any()

    private var busy = false
    private var operationJob: Job? = null

    override fun observe(projectId: Long): StateFlow<GitSnapshot> =
        stateOf(projectId).asStateFlow()

    override suspend fun refresh(projectId: Long, force: Boolean) {
        if (isOperationRunning()) return
        val state = stateOf(projectId)
        state.update {
            it.copy(
                isLoading = it.availability == GitAvailability.CHECKING,
                error = if (force) null else it.error
            )
        }
        withContext(Dispatchers.IO) { refreshInternal(projectId) }
    }

    override suspend fun isRepository(projectId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val project = runCatching { projects.getProject(projectId) }.getOrNull() ?: return@withContext false
            val path = project.path ?: return@withContext false
            File(path, ".git").exists()
        }

    override fun installGit(projectId: Long) {
        val state = stateOf(projectId)
        launchOperation(
            projectId = projectId,
            state = state,
            operation = GitOperation(
                kind = GitOperationKind.INSTALL,
                title = "Installing Git",
                detail = "git, openssh-client and CA certificates"
            )
        ) {
            if (!linuxInstalled()) {
                state.update {
                    it.copy(
                        error = GitError(
                            GitErrorKind.LINUX_MISSING,
                            "Install the Linux environment first."
                        )
                    )
                }
                return@launchOperation
            }

            val result = installer.install { line ->
                val detail = line.trim().takeIf { it.isNotEmpty() }?.take(MAX_OPERATION_DETAIL)
                if (detail != null) {
                    state.update { current ->
                        current.copy(operation = current.operation?.copy(detail = detail))
                    }
                }
            }

            if (result.success) {
                state.update { it.copy(message = "Git installed.", error = null) }
                refreshInternal(projectId)
            } else {
                state.update {
                    it.copy(
                        error = GitError(
                            kind = GitErrorKind.UNKNOWN,
                            message = result.message ?: "Git installation failed.",
                            detail = result.detail
                        )
                    )
                }
            }
        }
    }

    override fun initRepository(projectId: Long, initialBranch: String) {
        val state = stateOf(projectId)
        launchOperation(
            projectId = projectId,
            state = state,
            operation = GitOperation(
                kind = GitOperationKind.INIT,
                title = "Initializing repository"
            )
        ) {
            val project = runCatching { projects.getProject(projectId) }.getOrNull()
            val path = project?.path
            if (project == null || path.isNullOrBlank()) {
                state.update {
                    it.copy(
                        error = GitError(
                            GitErrorKind.INVALID_PROJECT,
                            "The project folder is not available."
                        )
                    )
                }
                return@launchOperation
            }
            if (!linuxInstalled()) {
                state.update {
                    it.copy(
                        error = GitError(
                            GitErrorKind.LINUX_MISSING,
                            "Install the Linux environment first."
                        )
                    )
                }
                return@launchOperation
            }

            val version = runner.run(
                projectPath = null,
                args = listOf("--version"),
                options = GitRunOptions(timeoutMs = VERSION_TIMEOUT_MS)
            )
            if (!version.isSuccess) {
                state.update {
                    it.copy(
                        error = GitError(
                            GitErrorKind.GIT_MISSING,
                            "Git is not installed in the Linux environment."
                        )
                    )
                }
                return@launchOperation
            }

            val branch = initialBranch.trim()
            if (branch.isNotEmpty()) {
                val check = runner.run(
                    projectPath = path,
                    args = listOf("check-ref-format", "--branch", branch),
                    options = GitRunOptions(timeoutMs = VALIDATION_TIMEOUT_MS)
                )
                if (!check.isSuccess) {
                    state.update {
                        it.copy(
                            error = GitError(
                                kind = GitErrorKind.INVALID_PATH,
                                message = "Invalid branch name: $branch",
                                detail = errorDetail(check)
                            )
                        )
                    }
                    return@launchOperation
                }
            }

            val initArgs = buildList {
                add("init")
                if (branch.isNotEmpty()) {
                    add("-b")
                    add(branch)
                }
            }
            val init = runner.run(
                projectPath = path,
                args = initArgs,
                options = GitRunOptions(timeoutMs = INIT_TIMEOUT_MS, readOnly = false)
            )
            if (!init.isSuccess) {
                state.update {
                    it.copy(
                        error = errorFrom(init, "Could not initialize the repository.")
                    )
                }
                return@launchOperation
            }

            // Shared storage has no executable bit and no symlink support. The
            // per-command flags already cover our own calls; persisting them
            // keeps the terminal's git consistent with this panel.
            runner.run(
                projectPath = path,
                args = listOf("config", "core.fileMode", "false"),
                options = GitRunOptions(timeoutMs = VALIDATION_TIMEOUT_MS, readOnly = false)
            )
            runner.run(
                projectPath = path,
                args = listOf("config", "core.symlinks", "false"),
                options = GitRunOptions(timeoutMs = VALIDATION_TIMEOUT_MS, readOnly = false)
            )

            state.update { it.copy(message = "Repository initialized.", error = null) }
            refreshInternal(projectId)
        }
    }

    override fun stage(projectId: Long, path: String) {
        launchRepositoryOperation(
            projectId = projectId,
            kind = GitOperationKind.STAGE,
            title = "Staging changes",
            detail = path
        ) { projectPath ->
            val result = runner.run(
                projectPath = projectPath,
                args = listOf("add", "-A", "--", path),
                options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
            )
            if (!result.isSuccess) {
                failOperation(projectId, errorFrom(result, "Could not stage $path."))
                return@launchRepositoryOperation
            }
            refreshInternal(projectId)
        }
    }

    override fun unstage(projectId: Long, path: String) {
        launchRepositoryOperation(
            projectId = projectId,
            kind = GitOperationKind.UNSTAGE,
            title = "Unstaging changes",
            detail = path
        ) { projectPath ->
            val result = runner.run(
                projectPath = projectPath,
                args = unstageArgs(projectPath, path),
                options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
            )
            if (!result.isSuccess) {
                failOperation(projectId, errorFrom(result, "Could not unstage $path."))
                return@launchRepositoryOperation
            }
            refreshInternal(projectId)
        }
    }

    override fun stageAll(projectId: Long) {
        val state = stateOf(projectId)
        if (state.value.status?.conflicted?.isNotEmpty() == true) {
            state.update {
                it.copy(
                    error = GitError(
                        GitErrorKind.CONFLICT,
                        "Resolve conflicted files before staging everything."
                    )
                )
            }
            return
        }
        launchRepositoryOperation(
            projectId = projectId,
            kind = GitOperationKind.STAGE,
            title = "Staging all changes"
        ) { projectPath ->
            val result = runner.run(
                projectPath = projectPath,
                args = listOf("add", "-A", "--", "."),
                options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
            )
            if (!result.isSuccess) {
                failOperation(projectId, errorFrom(result, "Could not stage the changes."))
                return@launchRepositoryOperation
            }
            refreshInternal(projectId)
        }
    }

    override fun unstageAll(projectId: Long) {
        val state = stateOf(projectId)
        if (state.value.status?.conflicted?.isNotEmpty() == true) {
            state.update {
                it.copy(
                    error = GitError(
                        GitErrorKind.CONFLICT,
                        "Resolve conflicted files before changing the index."
                    )
                )
            }
            return
        }
        launchRepositoryOperation(
            projectId = projectId,
            kind = GitOperationKind.UNSTAGE,
            title = "Unstaging all changes"
        ) { projectPath ->
            val hasHead = hasHeadCommit(projectPath)
            val args = if (hasHead) {
                listOf("reset", "--quiet", "HEAD", "--", ".")
            } else {
                listOf("rm", "--cached", "-r", "--ignore-unmatch", ".")
            }
            val result = runner.run(
                projectPath = projectPath,
                args = args,
                options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
            )
            if (!result.isSuccess) {
                failOperation(projectId, errorFrom(result, "Could not unstage the changes."))
                return@launchRepositoryOperation
            }
            refreshInternal(projectId)
        }
    }

    override fun discardChanges(
        projectId: Long,
        change: GitFileChange,
        confirmed: Boolean
    ) {
        val state = stateOf(projectId)
        if (!confirmed) {
            state.update {
                it.copy(
                    error = GitError(
                        GitErrorKind.UNKNOWN,
                        "Discarding changes requires confirmation."
                    )
                )
            }
            return
        }
        if (change.conflicted) {
            state.update {
                it.copy(
                    error = GitError(
                        GitErrorKind.CONFLICT,
                        "Resolve conflicts in the editor instead of discarding them."
                    )
                )
            }
            return
        }
        if (!change.untracked && !change.hasUnstaged) {
            state.update {
                it.copy(
                    error = GitError(
                        GitErrorKind.UNKNOWN,
                        "There are no unstaged changes to discard for this file."
                    )
                )
            }
            return
        }

        launchRepositoryOperation(
            projectId = projectId,
            kind = GitOperationKind.DISCARD,
            title = "Discarding changes",
            detail = change.path
        ) { projectPath ->
            if (change.untracked) {
                when (val result = projects.deleteProjectEntry(projectId, change.path)) {
                    DeleteProjectEntryResult.Success -> {
                        state.update { it.copy(message = "Deleted ${change.path}.") }
                        refreshInternal(projectId)
                    }

                    is DeleteProjectEntryResult.Failure -> {
                        if (result.reason == EntryFailure.NOT_FOUND) {
                            state.update {
                                it.copy(message = "${change.path} was already removed.")
                            }
                            refreshInternal(projectId)
                        } else {
                            state.update {
                                it.copy(
                                    error = GitError(
                                        kind = when (result.reason) {
                                            EntryFailure.PERMISSION_DENIED ->
                                                GitErrorKind.PERMISSION_DENIED

                                            EntryFailure.INVALID_PATH ->
                                                GitErrorKind.INVALID_PATH

                                            else -> GitErrorKind.IO
                                        },
                                        message = "Could not delete ${change.path}.",
                                        detail = result.detail
                                    )
                                )
                            }
                        }
                    }
                }
            } else {
                val result = runner.run(
                    projectPath = projectPath,
                    args = listOf("restore", "--worktree", "--", change.path),
                    options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
                )
                if (!result.isSuccess) {
                    failOperation(
                        projectId,
                        errorFrom(result, "Could not discard changes for ${change.path}.")
                    )
                    return@launchRepositoryOperation
                }
                state.update { it.copy(message = "Discarded changes for ${change.path}.") }
                refreshInternal(projectId)
            }
        }
    }

    override suspend fun diff(
        projectId: Long,
        change: GitFileChange,
        staged: Boolean
    ): GitDiffResult = withContext(Dispatchers.IO) {
        val project = runCatching { projects.getProject(projectId) }.getOrNull()
        val projectPath = project?.path
        if (projectPath.isNullOrBlank()) {
            return@withContext GitDiffResult.Failure(
                GitError(GitErrorKind.INVALID_PROJECT, "The project folder is not available.")
            )
        }
        if (!linuxInstalled()) {
            return@withContext GitDiffResult.Failure(
                GitError(GitErrorKind.LINUX_MISSING, "Install the Linux environment first.")
            )
        }
        if (change.conflicted) {
            return@withContext GitDiffResult.Failure(
                GitError(GitErrorKind.CONFLICT, "Resolve this conflict in the editor.")
            )
        }
        if (change.untracked) {
            return@withContext when (val read = projects.readProjectFile(projectId, change.path)) {
                is ReadProjectFileResult.Success ->
                    GitDiffResult.Success(buildUntrackedDiff(change.path, read.content))

                is ReadProjectFileResult.Failure -> when (read.error) {
                    ProjectFileError.BINARY -> GitDiffResult.Success(
                        GitDiff(
                            files = listOf(
                                GitDiffFile(path = change.path, isBinary = true)
                            )
                        )
                    )

                    ProjectFileError.FILE_TOO_LARGE -> GitDiffResult.Success(
                        GitDiff(
                            files = listOf(
                                GitDiffFile(path = change.path, isTooLarge = true)
                            )
                        )
                    )

                    ProjectFileError.NOT_FOUND -> GitDiffResult.Failure(
                        GitError(
                            GitErrorKind.NOT_FOUND,
                            "The file no longer exists.",
                            read.detail
                        )
                    )

                    else -> GitDiffResult.Failure(
                        GitError(
                            GitErrorKind.IO,
                            "Could not read ${change.path}.",
                            read.detail
                        )
                    )
                }
            }
        }

        val args = buildList {
            add("diff")
            if (staged) add("--cached")
            add("--no-color")
            add("--no-ext-diff")
            add("--unified=3")
            add("--")
            add(change.path)
        }
        val result = runner.run(
            projectPath = projectPath,
            args = args,
            options = GitRunOptions(timeoutMs = DIFF_TIMEOUT_MS, readOnly = true)
        )
        if (!result.isSuccess) {
            return@withContext GitDiffResult.Failure(
                errorFrom(result, "Could not load the diff for ${change.path}.")
            )
        }
        val parsed = GitDiffParser.parse(result.stdout, change.path)
        GitDiffResult.Success(
            parsed.copy(truncated = parsed.truncated || result.stdoutTruncated)
        )
    }

    override fun cancelOperation() {
        val job = synchronized(operationLock) { operationJob }
        job?.cancel()
    }

    override fun clearMessage(projectId: Long) {
        stateOf(projectId).update { it.copy(message = null) }
    }

    override fun clearError(projectId: Long) {
        stateOf(projectId).update { it.copy(error = null) }
    }

    // ---- internals --------------------------------------------------------

    private fun launchRepositoryOperation(
        projectId: Long,
        kind: GitOperationKind,
        title: String,
        detail: String? = null,
        block: suspend (String) -> Unit
    ) {
        val state = stateOf(projectId)
        val current = state.value
        if (current.availability != GitAvailability.READY || current.projectPath.isBlank()) {
            state.update {
                it.copy(
                    error = GitError(
                        GitErrorKind.NOT_A_REPO,
                        "The repository is not ready."
                    )
                )
            }
            return
        }
        launchOperation(projectId, state, GitOperation(kind, title, detail)) {
            block(current.projectPath)
        }
    }

    private fun failOperation(projectId: Long, error: GitError) {
        stateOf(projectId).update { it.copy(error = error) }
    }

    private suspend fun hasHeadCommit(projectPath: String): Boolean =
        runner.run(
            projectPath = projectPath,
            args = listOf("rev-parse", "--verify", "--quiet", "HEAD"),
            options = GitRunOptions(timeoutMs = VALIDATION_TIMEOUT_MS)
        ).isSuccess

    private suspend fun unstageArgs(projectPath: String, path: String): List<String> =
        if (hasHeadCommit(projectPath)) {
            listOf("reset", "--quiet", "HEAD", "--", path)
        } else {
            listOf("rm", "--cached", "--ignore-unmatch", "--", path)
        }

    /**
     * Untracked files have no base version, so their diff is built from the
     * current text. The editor's 2 MB read limit and binary rejection already
     * apply before this method sees the content.
     */
    private fun buildUntrackedDiff(path: String, content: String): GitDiff {
        val rawLines = content.split('\n')
        val lines = if (rawLines.isNotEmpty() && rawLines.last().isEmpty()) {
            rawLines.dropLast(1)
        } else {
            rawLines
        }.map { it.removeSuffix("\r") }

        val limited = lines.take(MAX_UNTRACKED_DIFF_LINES)
        val rendered = limited.mapIndexed { index, line ->
            GitDiffLine(
                kind = GitDiffLineKind.ADDED,
                text = line,
                newLine = index + 1
            )
        }
        val hunk = GitDiffHunk(
            header = "@@ -0,0 +1,${rendered.size} @@",
            oldStart = 0,
            oldCount = 0,
            newStart = 1,
            newCount = rendered.size,
            lines = rendered
        )
        return GitDiff(
            files = listOf(
                GitDiffFile(
                    path = path,
                    hunks = if (rendered.isEmpty()) emptyList() else listOf(hunk)
                )
            ),
            truncated = lines.size > limited.size
        )
    }

    private fun stateOf(projectId: Long): MutableStateFlow<GitSnapshot> =
        states.computeIfAbsent(projectId) {
            MutableStateFlow(GitSnapshot(projectId = projectId))
        }

    private fun isOperationRunning(): Boolean = synchronized(operationLock) { busy }

    private fun launchOperation(
        projectId: Long,
        state: MutableStateFlow<GitSnapshot>,
        operation: GitOperation,
        block: suspend () -> Unit
    ) {
        synchronized(operationLock) {
            if (busy) {
                state.update {
                    it.copy(
                        error = GitError(
                            GitErrorKind.UNKNOWN,
                            "Another Git operation is already running."
                        )
                    )
                }
                return
            }
            busy = true
            state.update {
                it.copy(
                    isBusy = true,
                    operation = operation,
                    message = null,
                    error = null
                )
            }

            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    state.update { it.copy(message = "Operation cancelled.") }
                    throw cancelled
                } catch (error: Throwable) {
                    state.update {
                        it.copy(
                            error = GitError(
                                kind = GitErrorKind.UNKNOWN,
                                message = error.message ?: "Git operation failed."
                            )
                        )
                    }
                }
            }
            operationJob = job
            job.invokeOnCompletion {
                synchronized(operationLock) {
                    if (operationJob === job) {
                        operationJob = null
                        busy = false
                    }
                }
                state.update { it.copy(isBusy = false, operation = null) }
            }
            job.start()
        }
    }

    private suspend fun refreshInternal(projectId: Long) {
        val state = stateOf(projectId)
        val project = runCatching { projects.getProject(projectId) }.getOrNull()
        val path = project?.path
        if (project == null || path.isNullOrBlank()) {
            state.update {
                it.copy(
                    isLoading = false,
                    projectName = project?.name.orEmpty(),
                    projectPath = path.orEmpty(),
                    availability = GitAvailability.ERROR,
                    repo = null,
                    status = null,
                    error = GitError(
                        GitErrorKind.INVALID_PROJECT,
                        "The project folder is not available."
                    )
                )
            }
            return
        }

        val gitDir = File(path, ".git")
        val hasGitDir = gitDir.exists()
        val linux = linuxInstalled()
        if (!linux) {
            state.update {
                it.copy(
                    isLoading = false,
                    projectName = project.name,
                    projectPath = path,
                    linuxInstalled = false,
                    gitVersion = null,
                    hasGitDir = hasGitDir,
                    availability = GitAvailability.LINUX_MISSING,
                    repo = null,
                    status = null,
                    error = null
                )
            }
            return
        }

        val versionResult = runner.run(
            projectPath = null,
            args = listOf("--version"),
            options = GitRunOptions(timeoutMs = VERSION_TIMEOUT_MS)
        )
        if (!versionResult.isSuccess) {
            state.update {
                it.copy(
                    isLoading = false,
                    projectName = project.name,
                    projectPath = path,
                    linuxInstalled = true,
                    gitVersion = null,
                    hasGitDir = hasGitDir,
                    availability = GitAvailability.GIT_MISSING,
                    repo = null,
                    status = null,
                    error = null
                )
            }
            return
        }
        val version = parseVersion(versionResult.stdout)

        val rootResult = runner.run(
            projectPath = path,
            args = listOf("rev-parse", "--show-toplevel"),
            options = GitRunOptions(timeoutMs = DETECTION_TIMEOUT_MS)
        )
        if (!rootResult.isSuccess) {
            if (hasGitDir) {
                state.update {
                    it.copy(
                        isLoading = false,
                        projectName = project.name,
                        projectPath = path,
                        linuxInstalled = true,
                        gitVersion = version,
                        hasGitDir = true,
                        availability = GitAvailability.ERROR,
                        repo = null,
                        status = null,
                        error = GitError(
                            kind = GitErrorKind.INVALID_PROJECT,
                            message = "The .git folder is not a valid repository.",
                            detail = errorDetail(rootResult)
                        )
                    )
                }
            } else {
                state.update {
                    it.copy(
                        isLoading = false,
                        projectName = project.name,
                        projectPath = path,
                        linuxInstalled = true,
                        gitVersion = version,
                        hasGitDir = false,
                        availability = GitAvailability.NOT_A_REPO,
                        repo = null,
                        status = null,
                        error = null
                    )
                }
            }
            return
        }

        val statusResult = runner.run(
            projectPath = path,
            args = listOf(
                "status",
                "--porcelain=v2",
                "--branch",
                "-z",
                "--untracked-files=all"
            ),
            options = GitRunOptions(timeoutMs = STATUS_TIMEOUT_MS)
        )
        if (!statusResult.isSuccess) {
            state.update {
                it.copy(
                    isLoading = false,
                    projectName = project.name,
                    projectPath = path,
                    linuxInstalled = true,
                    gitVersion = version,
                    hasGitDir = true,
                    availability = GitAvailability.ERROR,
                    repo = null,
                    status = null,
                    error = errorFrom(statusResult, "Could not read the repository status.")
                )
            }
            return
        }

        val status = GitStatusParser.parse(statusResult.stdout, statusResult.stdoutTruncated)
        val repo = GitRepoInfo(
            projectPath = path,
            gitDirPath = gitDir.absolutePath,
            branch = status.branch,
            detached = status.detached,
            headCommit = status.headCommit?.take(SHORT_HASH_LENGTH),
            upstream = status.upstream,
            ahead = status.ahead,
            behind = status.behind
        )

        state.update {
            it.copy(
                isLoading = false,
                projectName = project.name,
                projectPath = path,
                linuxInstalled = true,
                gitVersion = version,
                hasGitDir = true,
                availability = GitAvailability.READY,
                repo = repo,
                status = status,
                error = null
            )
        }
    }

    private fun parseVersion(output: String): String? =
        output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("git version ") }
            ?.removePrefix("git version ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun errorFrom(result: GitCommandResult, fallbackMessage: String): GitError {
        val stderr = sanitize(result.stderr)
        val kind = when {
            result.timedOut -> GitErrorKind.TIMEOUT
            stderr.contains("index.lock", ignoreCase = true) -> GitErrorKind.LOCKED
            stderr.contains("Authentication failed", ignoreCase = true) ||
                stderr.contains("could not read Username", ignoreCase = true) ||
                stderr.contains("Permission denied (publickey)", ignoreCase = true) ->
                GitErrorKind.AUTH_FAILED

            stderr.contains("Could not resolve host", ignoreCase = true) ||
                stderr.contains("unable to access", ignoreCase = true) ||
                stderr.contains("Connection refused", ignoreCase = true) ||
                stderr.contains("Network is unreachable", ignoreCase = true) ->
                GitErrorKind.NETWORK

            stderr.contains("not fully merged", ignoreCase = true) ->
                GitErrorKind.BRANCH_NOT_MERGED

            stderr.contains("would be overwritten", ignoreCase = true) ||
                stderr.contains("Your local changes", ignoreCase = true) ->
                GitErrorKind.DIRTY_WORKTREE

            stderr.contains("Please tell me who you are", ignoreCase = true) ->
                GitErrorKind.IDENTITY_MISSING

            stderr.contains("not a git repository", ignoreCase = true) ->
                GitErrorKind.NOT_A_REPO

            else -> GitErrorKind.UNKNOWN
        }
        val message = when (kind) {
            GitErrorKind.TIMEOUT -> "The Git command timed out."
            GitErrorKind.LOCKED -> "The repository is locked by another Git process."
            GitErrorKind.AUTH_FAILED -> "Authentication failed. Check the saved credentials."
            GitErrorKind.NETWORK -> "The network operation failed."
            GitErrorKind.BRANCH_NOT_MERGED -> "This branch has commits that are not merged."
            GitErrorKind.DIRTY_WORKTREE -> "Local changes would be overwritten."
            GitErrorKind.IDENTITY_MISSING -> "Set your Git user name and email first."
            GitErrorKind.NOT_A_REPO -> "This folder is not a Git repository."
            else -> fallbackMessage
        }
        return GitError(
            kind = kind,
            message = message,
            detail = errorDetail(result)
        )
    }

    private fun errorDetail(result: GitCommandResult): String? =
        sanitize(result.stderr)
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(MAX_ERROR_LINES)
            .joinToString("\n")
            .take(MAX_ERROR_DETAIL)
            .ifBlank { null }

    private fun sanitize(text: String): String =
        text.replace(ANSI_ESCAPE, "")
            .replace(URL_CREDENTIALS) { match -> match.groupValues[1] + "***@" }

    private companion object {
        const val SHORT_HASH_LENGTH = 7
        const val MAX_OPERATION_DETAIL = 160
        const val MAX_ERROR_LINES = 20
        const val MAX_ERROR_DETAIL = 4_000
        const val VERSION_TIMEOUT_MS = 20_000L
        const val DETECTION_TIMEOUT_MS = 30_000L
        const val STATUS_TIMEOUT_MS = 60_000L
        const val VALIDATION_TIMEOUT_MS = 20_000L
        const val INIT_TIMEOUT_MS = 60_000L
        const val MUTATION_TIMEOUT_MS = 60_000L
        const val DIFF_TIMEOUT_MS = 60_000L
        const val MAX_UNTRACKED_DIFF_LINES = 5_000

        val ANSI_ESCAPE = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        val URL_CREDENTIALS = Regex("(https?://)[^/@\\s]+@")
    }
}
