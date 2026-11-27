package dev.mobileide.feature.git

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.mobileide.data.editor.EditorTabManager
import dev.mobileide.di.container
import dev.mobileide.domain.git.GitBranchesResult
import dev.mobileide.domain.git.GitCommit
import dev.mobileide.domain.git.GitCommitDetailResult
import dev.mobileide.domain.git.GitDiffResult
import dev.mobileide.domain.git.GitFileChange
import dev.mobileide.domain.git.GitHistoryResult
import dev.mobileide.domain.git.GitRemotesResult
import dev.mobileide.domain.git.GitRepository
import dev.mobileide.domain.git.GitSnapshot
import dev.mobileide.feature.git.model.GitBranchesUiState
import dev.mobileide.feature.git.model.GitCommitDetailUiState
import dev.mobileide.feature.git.model.GitDiffRequest
import dev.mobileide.feature.git.model.GitDiffUiState
import dev.mobileide.feature.git.model.GitHistoryUiState
import dev.mobileide.feature.git.model.GitRemotesUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Binds the Git repository for one project to the panel. */
class GitViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repository: GitRepository = application.container.gitRepository
    private val tabManager: EditorTabManager = application.container.editorTabManager
    private val projectId: Long =
        savedStateHandle.get<String>("projectId")?.toLongOrNull() ?: 0L

    val state: StateFlow<GitSnapshot> = repository.observe(projectId)

    private val _diffState = MutableStateFlow<GitDiffUiState?>(null)
    val diffState: StateFlow<GitDiffUiState?> = _diffState.asStateFlow()

    private val _history = MutableStateFlow(GitHistoryUiState())
    val history: StateFlow<GitHistoryUiState> = _history.asStateFlow()
    private var loadedCommits: List<GitCommit> = emptyList()
    private var historyLoaded = false
    private var historyJob: Job? = null

    private val _commitDetail = MutableStateFlow<GitCommitDetailUiState?>(null)
    val commitDetail: StateFlow<GitCommitDetailUiState?> = _commitDetail.asStateFlow()
    private val commitBackStack = ArrayDeque<String>()

    private val _branches = MutableStateFlow(GitBranchesUiState())
    val branches: StateFlow<GitBranchesUiState> = _branches.asStateFlow()
    private var branchesLoaded = false
    private var branchesJob: Job? = null
    private var branchesReloadRequested = false

    private val _remotes = MutableStateFlow(GitRemotesUiState())
    val remotes: StateFlow<GitRemotesUiState> = _remotes.asStateFlow()
    private var remotesLoaded = false
    private var remotesJob: Job? = null
    private var remotesReloadRequested = false

    private var resumedOnce = false

    init {
        viewModelScope.launch { repository.refresh(projectId) }
        viewModelScope.launch {
            repository.revision.drop(1).collect {
                historyLoaded = false
                if (branchesLoaded) loadBranches(force = true)
                if (remotesLoaded) loadRemotes(force = true)
            }
        }
    }

    /** Re-checks after returning from Runtimes; the first resume is the init load. */
    fun onScreenResumed() {
        if (!resumedOnce) {
            resumedOnce = true
            return
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh(projectId, force = true) }
    }

    fun installGit() {
        repository.installGit(projectId)
    }

    fun initRepository(branch: String) {
        repository.initRepository(projectId, branch)
    }

    fun stage(change: GitFileChange) {
        repository.stage(projectId, change.path)
    }

    fun unstage(change: GitFileChange) {
        repository.unstage(projectId, change.path)
    }

    fun stageAll() {
        repository.stageAll(projectId)
    }

    fun unstageAll() {
        repository.unstageAll(projectId)
    }

    fun discard(change: GitFileChange) {
        repository.discardChanges(projectId, change, confirmed = true)
    }

    fun commit(message: String) {
        repository.commit(projectId, message)
        historyLoaded = false
    }

    // ---- branches ---------------------------------------------------------

    fun loadBranches(force: Boolean = false) {
        if (branchesJob?.isActive == true) {
            if (force) branchesReloadRequested = true
            return
        }
        if (branchesLoaded && !force) return
        branchesJob = viewModelScope.launch {
            _branches.update { it.copy(isLoading = true, error = null) }
            when (val result = repository.branches(projectId)) {
                is GitBranchesResult.Success -> {
                    branchesLoaded = true
                    _branches.update {
                        it.copy(
                            isLoading = false,
                            local = result.branches.local,
                            remote = result.branches.remote,
                            error = null
                        )
                    }
                }

                is GitBranchesResult.Failure -> {
                    _branches.update { it.copy(isLoading = false, error = result.error) }
                }
            }
            branchesJob = null
            if (branchesReloadRequested) {
                branchesReloadRequested = false
                loadBranches(force = true)
            }
        }
    }

    fun createBranch(name: String, startPoint: String?, track: Boolean) {
        repository.createBranch(
            projectId = projectId,
            name = name,
            startPoint = startPoint,
            track = track,
            dirtyEditorFiles = dirtyEditorFiles()
        )
    }

    fun switchBranch(name: String) {
        repository.switchBranch(
            projectId = projectId,
            name = name,
            dirtyEditorFiles = dirtyEditorFiles()
        )
    }

    fun deleteBranch(name: String) {
        repository.deleteBranch(projectId, name)
    }

    private fun dirtyEditorFiles(): List<String> =
        tabManager.tabs(projectId)
            .filter { it.isDirty }
            .map { it.name }

    // ---- remotes and credentials -----------------------------------------

    fun loadRemotes(force: Boolean = false) {
        if (remotesJob?.isActive == true) {
            if (force) remotesReloadRequested = true
            return
        }
        if (remotesLoaded && !force) return
        remotesJob = viewModelScope.launch {
            _remotes.update { it.copy(isLoading = true, error = null) }
            when (val result = repository.remotes(projectId)) {
                is GitRemotesResult.Success -> {
                    val identity = repository.identity()
                    val sshKey = repository.sshKeyInfo()
                    remotesLoaded = true
                    _remotes.update {
                        it.copy(
                            isLoading = false,
                            remotes = result.remotes,
                            identity = identity,
                            sshKey = sshKey,
                            error = null
                        )
                    }
                }

                is GitRemotesResult.Failure -> {
                    _remotes.update { it.copy(isLoading = false, error = result.error) }
                }
            }
            remotesJob = null
            if (remotesReloadRequested) {
                remotesReloadRequested = false
                loadRemotes(force = true)
            }
        }
    }

    fun addRemote(name: String, url: String) {
        repository.addRemote(projectId, name, url)
    }

    fun setRemoteUrl(name: String, url: String) {
        repository.setRemoteUrl(projectId, name, url)
    }

    fun removeRemote(name: String) {
        repository.removeRemote(projectId, name)
    }

    fun fetchRemote(name: String) {
        repository.fetchRemote(projectId, name)
    }

    fun pullRemote(name: String) {
        repository.pullRemote(projectId, name, dirtyEditorFiles())
    }

    fun pushRemote(name: String) {
        repository.pushRemote(projectId, name)
    }

    fun saveIdentity(name: String, email: String) {
        viewModelScope.launch {
            repository.saveIdentity(projectId, name, email)
            loadRemotes(force = true)
        }
    }

    fun saveHttpsCredential(host: String, username: String, token: String) {
        viewModelScope.launch {
            repository.saveHttpsCredential(projectId, host, username, token)
            loadRemotes(force = true)
        }
    }

    fun clearHttpsCredential(host: String) {
        viewModelScope.launch {
            repository.clearHttpsCredential(projectId, host)
            loadRemotes(force = true)
        }
    }

    fun generateSshKey() {
        repository.generateSshKey(projectId)
    }

    fun importSshKey(privateKey: String, publicKey: String?, passphrase: String?) {
        repository.importSshKey(projectId, privateKey, publicKey, passphrase)
    }

    fun deleteSshKey() {
        repository.deleteSshKey(projectId)
    }

    // ---- history ----------------------------------------------------------

    fun loadHistory(force: Boolean = false) {
        if (historyJob?.isActive == true) return
        if (historyLoaded && !force) {
            applyHistoryFilter(_history.value.truncated)
            return
        }
        historyJob = viewModelScope.launch {
            _history.update { it.copy(isLoading = true, error = null) }
            when (val result = repository.history(projectId)) {
                is GitHistoryResult.Success -> {
                    loadedCommits = result.commits
                    historyLoaded = true
                    applyHistoryFilter(result.truncated)
                }

                is GitHistoryResult.Failure -> _history.update {
                    it.copy(isLoading = false, error = result.error)
                }
            }
        }
    }

    fun onHistoryQueryChanged(query: String) {
        _history.update { it.copy(query = query) }
        if (historyLoaded) {
            applyHistoryFilter(_history.value.truncated)
        }
    }

    private fun applyHistoryFilter(truncated: Boolean) {
        val query = _history.value.query.trim()
        val filtered = if (query.isEmpty()) {
            loadedCommits
        } else {
            loadedCommits.filter { commit ->
                commit.subject.contains(query, ignoreCase = true) ||
                    commit.authorName.contains(query, ignoreCase = true) ||
                    commit.hash.startsWith(query, ignoreCase = true) ||
                    commit.shortHash.startsWith(query, ignoreCase = true)
            }
        }
        _history.update {
            it.copy(
                isLoading = false,
                commits = filtered,
                truncated = truncated,
                error = null
            )
        }
    }

    // ---- commit detail ----------------------------------------------------

    fun openCommit(hash: String) {
        val currentHash = _commitDetail.value?.hash
        if (currentHash != null && currentHash != hash) {
            commitBackStack.addLast(currentHash)
        }
        loadCommitDetail(hash)
    }

    fun openParentCommit(hash: String) {
        openCommit(hash)
    }

    fun retryCommitDetail() {
        val current = _commitDetail.value ?: return
        loadCommitDetail(current.hash)
    }

    fun closeCommitDetail() {
        val previous = commitBackStack.removeLastOrNull()
        if (previous != null) {
            loadCommitDetail(previous)
        } else {
            _commitDetail.value = null
        }
    }

    fun openCommitFileDiff(path: String) {
        val hash = _commitDetail.value?.hash ?: return
        openCommitDiff(hash, path)
    }

    private fun loadCommitDetail(hash: String) {
        _commitDetail.value = GitCommitDetailUiState(hash = hash)
        viewModelScope.launch {
            when (val result = repository.commitDetail(projectId, hash)) {
                is GitCommitDetailResult.Success -> _commitDetail.update { current ->
                    if (current?.hash != hash) {
                        current
                    } else {
                        current.copy(
                            isLoading = false,
                            detail = result.detail,
                            error = null
                        )
                    }
                }

                is GitCommitDetailResult.Failure -> _commitDetail.update { current ->
                    if (current?.hash != hash) {
                        current
                    } else {
                        current.copy(isLoading = false, error = result.error)
                    }
                }
            }
        }
    }

    // ---- diffs ------------------------------------------------------------

    fun openDiff(change: GitFileChange, staged: Boolean) {
        if (change.conflicted) return
        val request = GitDiffRequest(path = change.path, staged = staged, change = change)
        loadDiff(request) { repository.diff(projectId, change, staged) }
    }

    fun openCommitDiff(hash: String, path: String) {
        val request = GitDiffRequest(path = path, staged = false, commitHash = hash)
        loadDiff(request) { repository.commitDiff(projectId, hash, path) }
    }

    private fun loadDiff(request: GitDiffRequest, loader: suspend () -> GitDiffResult) {
        _diffState.value = GitDiffUiState(request = request)
        viewModelScope.launch {
            when (val result = loader()) {
                is GitDiffResult.Success -> _diffState.update { current ->
                    if (current?.request != request) {
                        current
                    } else {
                        current.copy(isLoading = false, diff = result.diff, error = null)
                    }
                }

                is GitDiffResult.Failure -> _diffState.update { current ->
                    if (current?.request != request) {
                        current
                    } else {
                        current.copy(isLoading = false, error = result.error)
                    }
                }
            }
        }
    }

    fun retryDiff() {
        val request = _diffState.value?.request ?: return
        val hash = request.commitHash
        if (hash != null) {
            openCommitDiff(hash, request.path)
        } else {
            request.change?.let { openDiff(it, request.staged) }
        }
    }

    fun closeDiff() {
        _diffState.value = null
    }

    fun cancelOperation() {
        repository.cancelOperation()
    }

    fun dismissError() {
        repository.clearError(projectId)
    }

    fun dismissMessage() {
        repository.clearMessage(projectId)
    }
}
