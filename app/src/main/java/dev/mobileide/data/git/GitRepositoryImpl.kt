package dev.mobileide.data.git

import dev.mobileide.domain.git.GitActionResult
import dev.mobileide.domain.git.GitAvailability
import dev.mobileide.domain.git.GitBranch
import dev.mobileide.domain.git.GitBranches
import dev.mobileide.domain.git.GitBranchesResult
import dev.mobileide.domain.git.GitIdentity
import dev.mobileide.domain.git.GitIgnoreResult
import dev.mobileide.domain.git.GitRemote
import dev.mobileide.domain.git.GitRemoteAuth
import dev.mobileide.domain.git.GitRemotesResult
import dev.mobileide.domain.git.GitSshKeyInfo
import dev.mobileide.domain.git.GitCommit
import dev.mobileide.domain.git.GitCommitDetail
import dev.mobileide.domain.git.GitCommitDetailResult
import dev.mobileide.domain.git.GitDiff
import dev.mobileide.domain.git.GitDiffFile
import dev.mobileide.domain.git.GitDiffHunk
import dev.mobileide.domain.git.GitDiffLine
import dev.mobileide.domain.git.GitDiffLineKind
import dev.mobileide.domain.git.GitDiffResult
import dev.mobileide.domain.git.GitError
import dev.mobileide.domain.git.GitErrorKind
import dev.mobileide.domain.git.GitFileChange
import dev.mobileide.domain.git.GitHistoryResult
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
import dev.mobileide.domain.repository.SaveProjectDocumentResult
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
    private val paths: GitPaths,
    private val credentials: GitCredentialStore,
    private val ssh: SshKeyManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : GitRepository {

    private val states = ConcurrentHashMap<Long, MutableStateFlow<GitSnapshot>>()
    private val operationLock = Any()

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()

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
                bumpRevision()
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
            bumpRevision()
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
            bumpRevision()
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
            bumpRevision()
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
            bumpRevision()
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
            bumpRevision()
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
                        bumpRevision()
                    }

                    is DeleteProjectEntryResult.Failure -> {
                        if (result.reason == EntryFailure.NOT_FOUND) {
                            state.update {
                                it.copy(message = "${change.path} was already removed.")
                            }
                            refreshInternal(projectId)
                            bumpRevision()
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
                bumpRevision()
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

    override fun commit(projectId: Long, message: String) {
        val state = stateOf(projectId)
        val trimmed = message.trim()
        if (trimmed.isEmpty()) {
            state.update {
                it.copy(error = GitError(GitErrorKind.UNKNOWN, "Enter a commit message."))
            }
            return
        }
        val status = state.value.status
        if (state.value.availability != GitAvailability.READY || status == null) {
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
        if (status.conflicted.isNotEmpty()) {
            state.update {
                it.copy(
                    error = GitError(
                        GitErrorKind.CONFLICT,
                        "Resolve conflicted files before committing."
                    )
                )
            }
            return
        }
        if (status.staged.isEmpty()) {
            state.update {
                it.copy(
                    error = GitError(
                        GitErrorKind.UNKNOWN,
                        "No staged changes to commit."
                    )
                )
            }
            return
        }

        launchRepositoryOperation(
            projectId = projectId,
            kind = GitOperationKind.COMMIT,
            title = "Committing changes"
        ) { projectPath ->
            val result = runner.run(
                projectPath = projectPath,
                args = listOf("commit", "-m", trimmed),
                options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
            )
            if (!result.isSuccess) {
                failOperation(projectId, errorFrom(result, "Commit failed."))
                return@launchRepositoryOperation
            }
            state.update { it.copy(message = "Commit created.") }
            refreshInternal(projectId)
            bumpRevision()
        }
    }

    override suspend fun history(
        projectId: Long,
        limit: Int
    ): GitHistoryResult = withContext(Dispatchers.IO) {
        val project = runCatching { projects.getProject(projectId) }.getOrNull()
        val projectPath = project?.path
        if (projectPath.isNullOrBlank()) {
            return@withContext GitHistoryResult.Failure(
                GitError(GitErrorKind.INVALID_PROJECT, "The project folder is not available.")
            )
        }
        if (!linuxInstalled()) {
            return@withContext GitHistoryResult.Failure(
                GitError(GitErrorKind.LINUX_MISSING, "Install the Linux environment first.")
            )
        }

        val count = limit.coerceIn(1, MAX_HISTORY_LIMIT)
        val result = runner.run(
            projectPath = projectPath,
            args = listOf(
                "log",
                "-n", (count + 1).toString(),
                "--no-color",
                "--date=iso-strict",
                "--pretty=format:$HISTORY_FORMAT"
            ),
            options = GitRunOptions(timeoutMs = HISTORY_TIMEOUT_MS)
        )
        if (!result.isSuccess) {
            if (isUnbornRepository(result)) {
                return@withContext GitHistoryResult.Success(emptyList())
            }
            return@withContext GitHistoryResult.Failure(
                errorFrom(result, "Could not load the commit history.")
            )
        }
        val commits = GitLogParser.parse(result.stdout)
        val truncated = commits.size > count || result.stdoutTruncated
        GitHistoryResult.Success(commits.take(count), truncated)
    }

    override suspend fun commitDetail(
        projectId: Long,
        hash: String
    ): GitCommitDetailResult = withContext(Dispatchers.IO) {
        val project = runCatching { projects.getProject(projectId) }.getOrNull()
        val projectPath = project?.path
        if (projectPath.isNullOrBlank()) {
            return@withContext GitCommitDetailResult.Failure(
                GitError(GitErrorKind.INVALID_PROJECT, "The project folder is not available.")
            )
        }
        if (!linuxInstalled()) {
            return@withContext GitCommitDetailResult.Failure(
                GitError(GitErrorKind.LINUX_MISSING, "Install the Linux environment first.")
            )
        }

        val commit = loadCommit(projectPath, hash)
            ?: return@withContext GitCommitDetailResult.Failure(
                GitError(
                    GitErrorKind.NOT_FOUND,
                    "Commit ${hash.take(SHORT_HASH_LENGTH)} was not found."
                )
            )
        val parent = commit.parents.firstOrNull()

        val files = if (parent != null) {
            runner.run(
                projectPath = projectPath,
                args = listOf("diff", "--name-status", "-z", parent, hash),
                options = GitRunOptions(timeoutMs = HISTORY_TIMEOUT_MS)
            )
        } else {
            runner.run(
                projectPath = projectPath,
                args = listOf(
                    "diff-tree",
                    "--root",
                    "--no-commit-id",
                    "--name-status",
                    "-r",
                    "-z",
                    hash
                ),
                options = GitRunOptions(timeoutMs = HISTORY_TIMEOUT_MS)
            )
        }
        if (!files.isSuccess) {
            return@withContext GitCommitDetailResult.Failure(
                errorFrom(files, "Could not load the files in this commit.")
            )
        }
        val parsedFiles = GitNameStatusParser.parse(files.stdout, MAX_COMMIT_FILES)

        val messageResult = runner.run(
            projectPath = projectPath,
            args = listOf("show", "-s", "--no-color", "--format=%B", hash),
            options = GitRunOptions(timeoutMs = HISTORY_TIMEOUT_MS)
        )
        val message = if (messageResult.isSuccess) {
            messageResult.stdout.trimEnd('\n', '\r')
        } else {
            commit.subject
        }

        val branchesResult = runner.run(
            projectPath = projectPath,
            args = listOf("branch", "--contains", hash, "--format=%(refname:short)"),
            options = GitRunOptions(timeoutMs = HISTORY_TIMEOUT_MS)
        )
        val branches = if (branchesResult.isSuccess) {
            branchesResult.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        } else {
            emptyList()
        }

        GitCommitDetailResult.Success(
            GitCommitDetail(
                commit = commit,
                message = message,
                files = parsedFiles.files,
                branches = branches,
                compareParent = parent,
                truncated = parsedFiles.truncated || files.stdoutTruncated
            )
        )
    }

    override suspend fun commitDiff(
        projectId: Long,
        hash: String,
        path: String
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
        val commit = loadCommit(projectPath, hash)
            ?: return@withContext GitDiffResult.Failure(
                GitError(
                    GitErrorKind.NOT_FOUND,
                    "Commit ${hash.take(SHORT_HASH_LENGTH)} was not found."
                )
            )
        val parent = commit.parents.firstOrNull()
        val args = if (parent != null) {
            listOf(
                "diff",
                "--no-color",
                "--no-ext-diff",
                "--unified=3",
                parent,
                hash,
                "--",
                path
            )
        } else {
            listOf(
                "show",
                "--no-color",
                "--format=",
                "--unified=3",
                hash,
                "--",
                path
            )
        }
        val result = runner.run(
            projectPath = projectPath,
            args = args,
            options = GitRunOptions(timeoutMs = DIFF_TIMEOUT_MS)
        )
        if (!result.isSuccess) {
            return@withContext GitDiffResult.Failure(
                errorFrom(result, "Could not load the commit diff.")
            )
        }
        val parsed = GitDiffParser.parse(result.stdout, path)
        GitDiffResult.Success(
            parsed.copy(truncated = parsed.truncated || result.stdoutTruncated)
        )
    }

    override suspend fun branches(projectId: Long): GitBranchesResult =
        withContext(Dispatchers.IO) {
            val project = runCatching { projects.getProject(projectId) }.getOrNull()
            val projectPath = project?.path
            if (projectPath.isNullOrBlank()) {
                return@withContext GitBranchesResult.Failure(
                    GitError(
                        GitErrorKind.INVALID_PROJECT,
                        "The project folder is not available."
                    )
                )
            }
            if (!linuxInstalled()) {
                return@withContext GitBranchesResult.Failure(
                    GitError(
                        GitErrorKind.LINUX_MISSING,
                        "Install the Linux environment first."
                    )
                )
            }

            val local = runner.run(
                projectPath = projectPath,
                args = listOf(
                    "branch",
                    "--format=%(refname:short)%1f%(HEAD)%1f%(objectname:short)" +
                        "%1f%(upstream:short)%1f%(upstream:track)"
                ),
                options = GitRunOptions(timeoutMs = BRANCH_TIMEOUT_MS)
            )
            if (!local.isSuccess) {
                return@withContext GitBranchesResult.Failure(
                    errorFrom(local, "Could not load local branches.")
                )
            }
            val remote = runner.run(
                projectPath = projectPath,
                args = listOf(
                    "branch", "-r",
                    "--format=%(refname:short)%1f%(objectname:short)"
                ),
                options = GitRunOptions(timeoutMs = BRANCH_TIMEOUT_MS)
            )
            if (!remote.isSuccess) {
                return@withContext GitBranchesResult.Failure(
                    errorFrom(remote, "Could not load remote branches.")
                )
            }
            GitBranchesResult.Success(
                GitBranches(
                    local = GitBranchParser.parseLocal(local.stdout),
                    remote = GitBranchParser.parseRemote(remote.stdout)
                )
            )
        }

    override fun createBranch(
        projectId: Long,
        name: String,
        startPoint: String?,
        track: Boolean,
        dirtyEditorFiles: List<String>
    ) {
        val state = stateOf(projectId)
        launchRepositoryOperation(
            projectId = projectId,
            kind = GitOperationKind.BRANCH,
            title = "Creating branch",
            detail = name
        ) { projectPath ->
            val branch = name.trim()
            if (branch.isEmpty()) {
                failOperation(projectId, GitError(GitErrorKind.UNKNOWN, "Enter a branch name."))
                return@launchRepositoryOperation
            }
            if (track && startPoint.isNullOrBlank()) {
                failOperation(
                    projectId,
                    GitError(GitErrorKind.UNKNOWN, "Select a remote branch to track.")
                )
                return@launchRepositoryOperation
            }
            val check = runner.run(
                projectPath = projectPath,
                args = listOf("check-ref-format", "--branch", branch),
                options = GitRunOptions(timeoutMs = VALIDATION_TIMEOUT_MS)
            )
            if (!check.isSuccess) {
                failOperation(
                    projectId,
                    GitError(
                        kind = GitErrorKind.INVALID_PATH,
                        message = "Invalid branch name: $branch",
                        detail = errorDetail(check)
                    )
                )
                return@launchRepositoryOperation
            }
            if (!startPoint.isNullOrBlank() || track) {
                dirtyBranchError(state.value, dirtyEditorFiles)?.let { error ->
                    failOperation(projectId, error)
                    return@launchRepositoryOperation
                }
            }
            val args = buildList {
                add("switch")
                add("-c")
                add(branch)
                if (track) add("--track")
                startPoint?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            val result = runner.run(
                projectPath = projectPath,
                args = args,
                options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
            )
            if (!result.isSuccess) {
                failOperation(
                    projectId,
                    errorFrom(result, "Could not create branch $branch.")
                )
                return@launchRepositoryOperation
            }
            state.update { it.copy(message = "Created branch $branch.") }
            refreshInternal(projectId)
            bumpRevision()
        }
    }

    override fun switchBranch(
        projectId: Long,
        name: String,
        dirtyEditorFiles: List<String>
    ) {
        val state = stateOf(projectId)
        launchRepositoryOperation(
            projectId = projectId,
            kind = GitOperationKind.BRANCH,
            title = "Switching branch",
            detail = name
        ) { projectPath ->
            dirtyBranchError(state.value, dirtyEditorFiles)?.let { error ->
                failOperation(projectId, error)
                return@launchRepositoryOperation
            }
            val result = runner.run(
                projectPath = projectPath,
                args = listOf("switch", name),
                options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
            )
            if (!result.isSuccess) {
                failOperation(projectId, errorFrom(result, "Could not switch to $name."))
                return@launchRepositoryOperation
            }
            state.update { it.copy(message = "Switched to $name.") }
            refreshInternal(projectId)
            bumpRevision()
        }
    }

    override fun deleteBranch(projectId: Long, name: String) {
        val state = stateOf(projectId)
        launchRepositoryOperation(
            projectId = projectId,
            kind = GitOperationKind.BRANCH,
            title = "Deleting branch",
            detail = name
        ) { projectPath ->
            val result = runner.run(
                projectPath = projectPath,
                args = listOf("branch", "-d", name),
                options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
            )
            if (!result.isSuccess) {
                failOperation(projectId, errorFrom(result, "Could not delete branch $name."))
                return@launchRepositoryOperation
            }
            state.update { it.copy(message = "Deleted branch $name.") }
            refreshInternal(projectId)
            bumpRevision()
        }
    }

    override suspend fun remotes(projectId: Long): GitRemotesResult =
        withContext(Dispatchers.IO) {
            val project = runCatching { projects.getProject(projectId) }.getOrNull()
            val projectPath = project?.path
            if (projectPath.isNullOrBlank()) {
                return@withContext GitRemotesResult.Failure(
                    GitError(
                        GitErrorKind.INVALID_PROJECT,
                        "The project folder is not available."
                    )
                )
            }
            if (!linuxInstalled()) {
                return@withContext GitRemotesResult.Failure(
                    GitError(
                        GitErrorKind.LINUX_MISSING,
                        "Install the Linux environment first."
                    )
                )
            }
            val result = runner.run(
                projectPath = projectPath,
                args = listOf("remote", "-v"),
                options = GitRunOptions(timeoutMs = VALIDATION_TIMEOUT_MS)
            )
            if (!result.isSuccess) {
                return@withContext GitRemotesResult.Failure(
                    errorFrom(result, "Could not load remotes.")
                )
            }
            val remotes = GitRemoteParser.parse(result.stdout).map { remote ->
                val parsed = GitRemoteUrlParser.parse(remote.fetchUrl)
                val credential = if (parsed.auth == GitRemoteAuth.HTTPS) {
                    parsed.hostKey?.let { credentials.loadHttps(it) }
                } else {
                    null
                }
                GitRemote(
                    name = remote.name,
                    url = remote.fetchUrl,
                    pushUrl = remote.pushUrl,
                    host = parsed.host,
                    auth = parsed.auth,
                    hasCredential = when (parsed.auth) {
                        GitRemoteAuth.HTTPS -> credential != null
                        GitRemoteAuth.SSH -> paths.privateKeyFile.isFile
                        GitRemoteAuth.OTHER -> false
                    },
                    credentialUsername = credential?.username
                )
            }
            GitRemotesResult.Success(remotes)
        }

    override fun addRemote(projectId: Long, name: String, url: String) {
        launchRepositoryOperation(
            projectId = projectId,
            kind = GitOperationKind.REMOTE,
            title = "Adding remote",
            detail = name
        ) { projectPath ->
            val remoteName = name.trim()
            val parsed = GitRemoteUrlParser.parse(url)
            if (remoteName.isEmpty() || parsed.cleanUrl.isBlank()) {
                failOperation(
                    projectId,
                    GitError(GitErrorKind.UNKNOWN, "Enter a remote name and URL.")
                )
                return@launchRepositoryOperation
            }
            val result = runner.run(
                projectPath = projectPath,
                args = listOf("remote", "add", remoteName, parsed.cleanUrl),
                options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
            )
            if (!result.isSuccess) {
                failOperation(projectId, errorFrom(result, "Could not add the remote."))
                return@launchRepositoryOperation
            }
            saveEmbeddedCredentials(parsed)
            stateOf(projectId).update { it.copy(message = "Remote $remoteName added.") }
            refreshInternal(projectId)
            bumpRevision()
        }
    }

    override fun setRemoteUrl(projectId: Long, name: String, url: String) {
        launchRepositoryOperation(
            projectId = projectId,
            kind = GitOperationKind.REMOTE,
            title = "Updating remote",
            detail = name
        ) { projectPath ->
            val parsed = GitRemoteUrlParser.parse(url)
            if (parsed.cleanUrl.isBlank()) {
                failOperation(projectId, GitError(GitErrorKind.UNKNOWN, "Enter a remote URL."))
                return@launchRepositoryOperation
            }
            val result = runner.run(
                projectPath = projectPath,
                args = listOf("remote", "set-url", name, parsed.cleanUrl),
                options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
            )
            if (!result.isSuccess) {
                failOperation(projectId, errorFrom(result, "Could not update the remote."))
                return@launchRepositoryOperation
            }
            saveEmbeddedCredentials(parsed)
            stateOf(projectId).update { it.copy(message = "Remote $name updated.") }
            refreshInternal(projectId)
            bumpRevision()
        }
    }

    override fun removeRemote(projectId: Long, name: String) {
        launchRepositoryOperation(
            projectId = projectId,
            kind = GitOperationKind.REMOTE,
            title = "Removing remote",
            detail = name
        ) { projectPath ->
            val result = runner.run(
                projectPath = projectPath,
                args = listOf("remote", "remove", name),
                options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
            )
            if (!result.isSuccess) {
                failOperation(projectId, errorFrom(result, "Could not remove the remote."))
                return@launchRepositoryOperation
            }
            stateOf(projectId).update { it.copy(message = "Remote $name removed.") }
            refreshInternal(projectId)
            bumpRevision()
        }
    }

    override fun fetchRemote(projectId: Long, remote: String) {
        launchRemoteOperation(projectId, remote, GitOperationKind.FETCH, "Fetching $remote") { path, url ->
            val result = runner.run(
                projectPath = path,
                args = listOf("fetch", "--prune", "--progress", remote),
                options = remoteOptionsFor(projectId, url, REMOTE_TIMEOUT_MS)
            )
            if (!result.isSuccess) {
                failOperation(projectId, errorFrom(result, "Fetch from $remote failed."))
                return@launchRemoteOperation
            }
            stateOf(projectId).update { it.copy(message = "Fetched $remote.") }
            refreshInternal(projectId)
            bumpRevision()
        }
    }

    override fun pullRemote(
        projectId: Long,
        remote: String,
        dirtyEditorFiles: List<String>
    ) {
        val state = stateOf(projectId)
        launchRemoteOperation(projectId, remote, GitOperationKind.PULL, "Pulling from $remote") { path, url ->
            dirtyBranchError(state.value, dirtyEditorFiles)?.let { error ->
                failOperation(projectId, error)
                return@launchRemoteOperation
            }
            val branch = state.value.status?.branch
            if (branch.isNullOrBlank()) {
                failOperation(
                    projectId,
                    GitError(GitErrorKind.UNKNOWN, "Switch to a branch before pulling.")
                )
                return@launchRemoteOperation
            }
            val result = runner.run(
                projectPath = path,
                args = listOf("pull", "--ff-only", "--progress", remote, branch),
                options = remoteOptionsFor(projectId, url, REMOTE_TIMEOUT_MS)
            )
            if (!result.isSuccess) {
                failOperation(
                    projectId,
                    errorFrom(result, "Pull from $remote failed.")
                )
                return@launchRemoteOperation
            }
            state.update { it.copy(message = "Pulled from $remote.") }
            refreshInternal(projectId)
            bumpRevision()
        }
    }

    override fun pushRemote(projectId: Long, remote: String) {
        val state = stateOf(projectId)
        launchRemoteOperation(projectId, remote, GitOperationKind.PUSH, "Pushing to $remote") { path, url ->
            val branch = state.value.status?.branch
            if (branch.isNullOrBlank()) {
                failOperation(
                    projectId,
                    GitError(GitErrorKind.UNKNOWN, "Switch to a branch before pushing.")
                )
                return@launchRemoteOperation
            }
            val result = runner.run(
                projectPath = path,
                args = listOf("push", "--progress", "-u", remote, branch),
                options = remoteOptionsFor(projectId, url, REMOTE_TIMEOUT_MS)
            )
            if (!result.isSuccess) {
                failOperation(projectId, errorFrom(result, "Push to $remote failed."))
                return@launchRemoteOperation
            }
            state.update { it.copy(message = "Pushed to $remote.") }
            refreshInternal(projectId)
            bumpRevision()
        }
    }

    override suspend fun identity(): GitIdentity = withContext(Dispatchers.IO) {
        GitIdentity(
            name = configValue("user.name"),
            email = configValue("user.email")
        )
    }

    override suspend fun saveIdentity(
        projectId: Long,
        name: String,
        email: String
    ): GitActionResult = withContext(Dispatchers.IO) {
        val state = stateOf(projectId)
        val trimmedName = name.trim()
        val trimmedEmail = email.trim()
        if (trimmedName.isEmpty() || trimmedEmail.isEmpty()) {
            val error = GitError(
                GitErrorKind.IDENTITY_MISSING,
                "Enter both a name and an email."
            )
            state.update { it.copy(error = error) }
            return@withContext GitActionResult.Failure(error)
        }
        val nameResult = runner.run(
            projectPath = null,
            args = listOf("config", "--global", "user.name", trimmedName),
            options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
        )
        val emailResult = runner.run(
            projectPath = null,
            args = listOf("config", "--global", "user.email", trimmedEmail),
            options = GitRunOptions(timeoutMs = MUTATION_TIMEOUT_MS, readOnly = false)
        )
        if (!nameResult.isSuccess || !emailResult.isSuccess) {
            val error = errorFrom(
                if (!nameResult.isSuccess) nameResult else emailResult,
                "Could not save the Git identity."
            )
            state.update { it.copy(error = error) }
            return@withContext GitActionResult.Failure(error)
        }
        state.update { it.copy(message = "Git identity saved.", error = null) }
        GitActionResult.Success
    }

    override suspend fun saveHttpsCredential(
        projectId: Long,
        host: String,
        username: String,
        token: String
    ): GitActionResult = withContext(Dispatchers.IO) {
        val state = stateOf(projectId)
        val trimmedUsername = username.trim()
        val trimmedToken = token.trim()
        if (host.isBlank() || trimmedUsername.isEmpty() || trimmedToken.isEmpty()) {
            val error = GitError(
                GitErrorKind.AUTH_FAILED,
                "Enter the host, username and token."
            )
            state.update { it.copy(error = error) }
            return@withContext GitActionResult.Failure(error)
        }
        runCatching {
            credentials.saveHttps(host, trimmedUsername, trimmedToken)
        }.fold(
            onSuccess = {
                state.update { it.copy(message = "Credential saved for $host.", error = null) }
                GitActionResult.Success
            },
            onFailure = {
                val error = GitError(
                    GitErrorKind.UNKNOWN,
                    "Could not save the credential securely."
                )
                state.update { it.copy(error = error) }
                GitActionResult.Failure(error)
            }
        )
    }

    override suspend fun clearHttpsCredential(
        projectId: Long,
        host: String
    ): GitActionResult = withContext(Dispatchers.IO) {
        val state = stateOf(projectId)
        runCatching { credentials.removeHttps(host) }.fold(
            onSuccess = {
                state.update { it.copy(message = "Credential removed for $host.", error = null) }
                GitActionResult.Success
            },
            onFailure = {
                val error = GitError(
                    GitErrorKind.UNKNOWN,
                    "Could not remove the credential."
                )
                state.update { it.copy(error = error) }
                GitActionResult.Failure(error)
            }
        )
    }

    override suspend fun sshKeyInfo(): GitSshKeyInfo = withContext(Dispatchers.IO) { ssh.info() }

    override fun generateSshKey(projectId: Long) {
        val state = stateOf(projectId)
        launchOperation(
            projectId = projectId,
            state = state,
            operation = GitOperation(
                kind = GitOperationKind.SSH,
                title = "Generating SSH key"
            )
        ) {
            when (val result = ssh.generate()) {
                GitActionResult.Success -> {
                    state.update { it.copy(message = "SSH key generated.", error = null) }
                    bumpRevision()
                }

                is GitActionResult.Failure ->
                    state.update { it.copy(error = result.error) }
            }
        }
    }

    override fun importSshKey(
        projectId: Long,
        privateKey: String,
        publicKey: String?,
        passphrase: String?
    ) {
        val state = stateOf(projectId)
        launchOperation(
            projectId = projectId,
            state = state,
            operation = GitOperation(
                kind = GitOperationKind.SSH,
                title = "Importing SSH key"
            )
        ) {
            when (val result = ssh.importKey(privateKey, publicKey, passphrase)) {
                GitActionResult.Success -> {
                    state.update {
                        it.copy(
                            message = "SSH key imported. Test it with Fetch.",
                            error = null
                        )
                    }
                    bumpRevision()
                }

                is GitActionResult.Failure ->
                    state.update { it.copy(error = result.error) }
            }
        }
    }

    override fun deleteSshKey(projectId: Long) {
        val state = stateOf(projectId)
        launchOperation(
            projectId = projectId,
            state = state,
            operation = GitOperation(
                kind = GitOperationKind.SSH,
                title = "Removing SSH key"
            )
        ) {
            when (val result = ssh.delete()) {
                GitActionResult.Success -> {
                    state.update { it.copy(message = "SSH key removed.", error = null) }
                    bumpRevision()
                }

                is GitActionResult.Failure ->
                    state.update { it.copy(error = result.error) }
            }
        }
    }

    override suspend fun readGitIgnore(projectId: Long): GitIgnoreResult =
        withContext(Dispatchers.IO) {
            val project = runCatching { projects.getProject(projectId) }.getOrNull()
            val projectPath = project?.path
            if (projectPath.isNullOrBlank()) {
                return@withContext GitIgnoreResult.Failure(
                    GitError(
                        GitErrorKind.INVALID_PROJECT,
                        "The project folder is not available."
                    )
                )
            }
            if (!File(projectPath, ".gitignore").isFile) {
                return@withContext GitIgnoreResult.Success(exists = false)
            }
            when (val read = projects.readProjectFile(projectId, ".gitignore")) {
                is ReadProjectFileResult.Success ->
                    GitIgnoreResult.Success(exists = true, content = read.content)

                is ReadProjectFileResult.Failure -> GitIgnoreResult.Failure(
                    GitError(
                        kind = when (read.error) {
                            ProjectFileError.NOT_FOUND -> GitErrorKind.NOT_FOUND
                            ProjectFileError.PERMISSION_DENIED -> GitErrorKind.PERMISSION_DENIED
                            ProjectFileError.FILE_TOO_LARGE -> GitErrorKind.OUTPUT_TRUNCATED
                            else -> GitErrorKind.IO
                        },
                        message = "Could not read .gitignore.",
                        detail = read.detail
                    )
                )
            }
        }

    override suspend fun writeGitIgnore(
        projectId: Long,
        content: String,
        overwrite: Boolean
    ): GitActionResult = withContext(Dispatchers.IO) {
        val state = stateOf(projectId)
        val project = runCatching { projects.getProject(projectId) }.getOrNull()
        val projectPath = project?.path
        if (projectPath.isNullOrBlank()) {
            val error = GitError(
                GitErrorKind.INVALID_PROJECT,
                "The project folder is not available."
            )
            state.update { it.copy(error = error) }
            return@withContext GitActionResult.Failure(error)
        }
        val exists = File(projectPath, ".gitignore").isFile
        if (exists && !overwrite) {
            val error = GitError(
                GitErrorKind.UNKNOWN,
                "A .gitignore already exists. Open it instead."
            )
            state.update { it.copy(error = error) }
            return@withContext GitActionResult.Failure(error)
        }
        if (content.isBlank()) {
            val error = GitError(GitErrorKind.UNKNOWN, "Enter .gitignore content.")
            state.update { it.copy(error = error) }
            return@withContext GitActionResult.Failure(error)
        }
        when (val result = projects.saveProjectDocument(projectId, ".gitignore", content)) {
            SaveProjectDocumentResult.Success -> {
                state.update { it.copy(message = ".gitignore saved.", error = null) }
                refreshInternal(projectId)
                bumpRevision()
                GitActionResult.Success
            }

            is SaveProjectDocumentResult.Failure -> {
                val error = GitError(
                    kind = when (result.error) {
                        ProjectFileError.NOT_FOUND -> GitErrorKind.NOT_FOUND
                        ProjectFileError.PERMISSION_DENIED -> GitErrorKind.PERMISSION_DENIED
                        else -> GitErrorKind.IO
                    },
                    message = "Could not write .gitignore.",
                    detail = result.detail
                )
                state.update { it.copy(error = error) }
                GitActionResult.Failure(error)
            }
        }
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

    private fun bumpRevision() {
        _revision.update { it + 1 }
    }

    private fun dirtyBranchError(
        state: GitSnapshot,
        dirtyEditorFiles: List<String>
    ): GitError? {
        if (dirtyEditorFiles.isNotEmpty()) {
            return GitError(
                kind = GitErrorKind.DIRTY_WORKTREE,
                message = "Save or discard unsaved editor changes first.",
                detail = dirtyEditorFiles.joinToString("\n").take(MAX_ERROR_DETAIL)
            )
        }
        val status = state.status ?: return null
        if (status.staged.isNotEmpty() || status.unstaged.isNotEmpty()) {
            return GitError(
                GitErrorKind.DIRTY_WORKTREE,
                "Commit or discard changes before switching branches."
            )
        }
        return null
    }

    private fun launchRemoteOperation(
        projectId: Long,
        remote: String,
        kind: GitOperationKind,
        title: String,
        block: suspend (projectPath: String, remoteUrl: String) -> Unit
    ) {
        launchRepositoryOperation(projectId, kind, title, remote) { projectPath ->
            val url = remoteUrl(projectPath, remote)
            if (url == null) {
                failOperation(
                    projectId,
                    GitError(GitErrorKind.UNKNOWN, "Remote $remote was not found.")
                )
                return@launchRepositoryOperation
            }
            val parsed = GitRemoteUrlParser.parse(url)
            if (parsed.auth == GitRemoteAuth.SSH && !paths.privateKeyFile.isFile) {
                failOperation(
                    projectId,
                    GitError(
                        GitErrorKind.AUTH_FAILED,
                        "Generate or import an SSH key first."
                    )
                )
                return@launchRepositoryOperation
            }
            block(projectPath, url)
        }
    }

    private suspend fun remoteUrl(projectPath: String, remote: String): String? {
        val result = runner.run(
            projectPath = projectPath,
            args = listOf("remote", "get-url", remote),
            options = GitRunOptions(timeoutMs = VALIDATION_TIMEOUT_MS)
        )
        if (!result.isSuccess) return null
        return result.stdout.trim().takeIf { it.isNotEmpty() }
    }

    private fun remoteOptionsFor(
        projectId: Long,
        url: String,
        timeoutMs: Long
    ): GitRunOptions {
        val parsed = GitRemoteUrlParser.parse(url)
        val environment = buildMap {
            when (parsed.auth) {
                GitRemoteAuth.HTTPS -> {
                    val credential = parsed.hostKey?.let { credentials.loadHttps(it) }
                    if (credential != null) {
                        put("MOBILEIDE_GIT_USERNAME", credential.username)
                        put("MOBILEIDE_GIT_TOKEN", credential.token)
                    }
                }

                GitRemoteAuth.SSH -> {
                    credentials.loadSshPassphrase()?.let {
                        put("MOBILEIDE_SSH_PASSPHRASE", it)
                    }
                }

                GitRemoteAuth.OTHER -> Unit
            }
        }
        val state = stateOf(projectId)
        return GitRunOptions(
            timeoutMs = timeoutMs,
            readOnly = false,
            extraEnv = environment,
            onProgress = { line ->
                val progress = GitProgressParser.parse(line)
                state.update { current ->
                    current.copy(
                        operation = current.operation?.copy(
                            detail = line.take(MAX_OPERATION_DETAIL),
                            progress = progress ?: current.operation?.progress
                        )
                    )
                }
            }
        )
    }

    private fun saveEmbeddedCredentials(parsed: ParsedRemoteUrl) {
        if (parsed.auth != GitRemoteAuth.HTTPS) return
        val hostKey = parsed.hostKey ?: return
        val username = parsed.username ?: return
        val password = parsed.password ?: return
        runCatching { credentials.saveHttps(hostKey, username, password) }
    }

    private suspend fun configValue(key: String): String? {
        val result = runner.run(
            projectPath = null,
            args = listOf("config", "--global", "--get", key),
            options = GitRunOptions(timeoutMs = VALIDATION_TIMEOUT_MS)
        )
        if (!result.isSuccess) return null
        return result.stdout.trim().takeIf { it.isNotEmpty() }
    }

    private suspend fun hasHeadCommit(projectPath: String): Boolean =
        runner.run(
            projectPath = projectPath,
            args = listOf("rev-parse", "--verify", "--quiet", "HEAD"),
            options = GitRunOptions(timeoutMs = VALIDATION_TIMEOUT_MS)
        ).isSuccess

    private suspend fun loadCommit(projectPath: String, hash: String): GitCommit? {
        val result = runner.run(
            projectPath = projectPath,
            args = listOf(
                "log", "-1",
                "--no-color",
                "--date=iso-strict",
                "--pretty=format:$HISTORY_FORMAT",
                hash
            ),
            options = GitRunOptions(timeoutMs = HISTORY_TIMEOUT_MS)
        )
        if (!result.isSuccess) return null
        return GitLogParser.parseSingle(result.stdout)
    }

    private fun isUnbornRepository(result: GitCommandResult): Boolean {
        val stderr = result.stderr
        return stderr.contains("does not have any commits yet", ignoreCase = true) ||
            stderr.contains(
                "unknown revision or path not in the working tree",
                ignoreCase = true
            )
    }

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
        const val HISTORY_TIMEOUT_MS = 60_000L
        const val BRANCH_TIMEOUT_MS = 60_000L
        const val REMOTE_TIMEOUT_MS = 5 * 60 * 1000L
        const val MAX_HISTORY_LIMIT = 1_000
        const val MAX_COMMIT_FILES = 1_000
        const val HISTORY_FORMAT =
            "%H%x1f%h%x1f%an%x1f%ae%x1f%aI%x1f%s%x1f%D%x1f%P%x1e"

        val ANSI_ESCAPE = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        val URL_CREDENTIALS = Regex("(https?://)[^/@\\s]+@")
    }
}
