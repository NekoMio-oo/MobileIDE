package dev.mobileide.feature.editor

import android.app.Application
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.mobileide.data.editor.EditorRevealTarget
import dev.mobileide.data.editor.EditorTabManager
import dev.mobileide.di.container
import dev.mobileide.domain.editor.EditorSnapshot
import dev.mobileide.domain.editor.EditorTab
import dev.mobileide.domain.editor.EditorTabBuffer
import dev.mobileide.domain.editor.EditorTabStatus
import dev.mobileide.domain.editor.OpenTabResult
import dev.mobileide.domain.repository.ProjectFileError
import dev.mobileide.domain.repository.ProjectFileInfoResult
import dev.mobileide.domain.repository.ProjectRepository
import dev.mobileide.domain.repository.ReadProjectFileResult
import dev.mobileide.domain.repository.WriteProjectFileResult
import dev.mobileide.domain.lsp.LspCapabilities
import dev.mobileide.domain.lsp.LspCompletionItem
import dev.mobileide.domain.lsp.LspDiagnostic
import dev.mobileide.domain.lsp.LspDocumentKey
import dev.mobileide.domain.lsp.LspDocumentSymbol
import dev.mobileide.domain.lsp.LspHover
import dev.mobileide.domain.lsp.LspLocation
import dev.mobileide.domain.lsp.LspRepository
import dev.mobileide.domain.lsp.LspServerInfo
import dev.mobileide.domain.lsp.LspSnapshot
import dev.mobileide.domain.lsp.LspTextEdit
import dev.mobileide.feature.editor.model.CloseDialogState
import dev.mobileide.feature.editor.model.CloseScope
import dev.mobileide.feature.editor.model.EditorRenderDocument
import dev.mobileide.feature.editor.model.EditorUiState
import dev.mobileide.feature.editor.model.ExternalChangeState
import dev.mobileide.feature.editor.model.SaveReport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Base64

/**
 * Multi-tab editor state holder. Tab metadata lives in [EditorTabManager];
 * this ViewModel performs file IO, keeps the active document current and
 * implements save / close / external-change policies.
 */
class EditorViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repository: ProjectRepository = application.container.projectRepository
    private val tabManager: EditorTabManager = application.container.editorTabManager
    private val revealRequests = application.container.editorRevealRequests
    private val lsp: LspRepository = application.container.lspRepository

    /** Documents announced to a server, so closing a tab also closes its document. */
    private var lspOpenPaths: Set<String> = emptySet()

    private val projectId: Long =
        savedStateHandle.get<String>("projectId")?.toLongOrNull() ?: 0L
    private val initialRelativePath: String = runCatching {
        val encoded = savedStateHandle.get<String>("path").orEmpty()
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrDefault("")

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val loadingTabs = mutableSetOf<String>()
    private var persistJob: Job? = null
    private var projectRoot: String = ""

    init {
        viewModelScope.launch {
            tabManager.observe(projectId).collect { state ->
                _uiState.update {
                    it.copy(
                        tabs = state.tabs,
                        activeTabId = state.activeTabId,
                        activationRevision = state.revision
                    )
                }
                syncLspDocuments(state.tabs.map { tab -> tab.relativePath }.toSet())
            }
        }
        loadProject()
        // The editor can be the first screen that needs the catalog; load it
        // once in the background instead of re-probing on every entry.
        if (lsp.state.value.servers.isEmpty()) {
            viewModelScope.launch { runCatching { lsp.refresh() } }
        }
    }

    /** Document the Sora view should currently show, if any. */
    fun renderDocument(): EditorRenderDocument? {
        val state = _uiState.value
        val tab = state.activeTab ?: return null
        if (tab.status != EditorTabStatus.READY) return null
        val buffer = tabManager.buffer(tab.id) ?: return null
        return EditorRenderDocument(
            tabId = tab.id,
            revision = state.activationRevision,
            text = buffer.text,
            language = tab.language,
            cursorLine = buffer.cursorLine,
            cursorColumn = buffer.cursorColumn
        )
    }

    // ---- Loading ----------------------------------------------------------

    private fun loadProject() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null, loadErrorDetail = null) }
            val project = runCatching { repository.getProject(projectId) }.getOrNull()
            val path = project?.path
            if (project == null || path.isNullOrBlank()) {
                _uiState.update {
                    it.copy(isLoading = false, loadError = ProjectFileError.NOT_FOUND)
                }
                return@launch
            }
            projectRoot = path
            _uiState.update { it.copy(projectName = project.name, projectPath = path) }
            tabManager.restore(projectId, path)
            _uiState.update { it.copy(isLoading = false) }

            if (initialRelativePath.isNotBlank()) {
                openFile(initialRelativePath)
            } else {
                tabManager.activeTab(projectId)
                    ?.takeIf { it.status == EditorTabStatus.LOADING }
                    ?.let { loadTabContent(it) }
            }
            validateRestoredDirtyTabs()
        }
    }

    private suspend fun openFile(relativePath: String) {
        val name = relativePath.substringAfterLast('/')
        when (val result = tabManager.open(projectId, projectRoot, relativePath, name)) {
            OpenTabResult.LimitReached -> _uiState.update { it.copy(tabLimitReached = true) }

            is OpenTabResult.AlreadyOpen -> {
                val tab = tabManager.tabs(projectId).firstOrNull { it.id == result.tabId }
                when {
                    tab == null -> Unit
                    tab.status == EditorTabStatus.LOADING -> loadTabContent(tab)
                    else -> applyPendingReveal(tab)
                }
            }

            is OpenTabResult.Created -> tabManager.tabs(projectId)
                .firstOrNull { it.id == result.tabId }
                ?.let { loadTabContent(it) }
        }
    }

    private suspend fun loadTabContent(tab: EditorTab) {
        if (!loadingTabs.add(tab.id)) return
        try {
            val result = try {
                repository.readProjectFile(projectId, tab.relativePath)
            } catch (error: Exception) {
                ReadProjectFileResult.Failure(ProjectFileError.IO, error.message)
            }
            when (result) {
                is ReadProjectFileResult.Success -> {
                    val lineEnding = if (result.content.contains("\r\n")) "\r\n" else "\n"
                    val normalized = result.content.replace("\r\n", "\n").replace('\r', '\n')
                    tabManager.onLoaded(
                        projectId = projectId,
                        tabId = tab.id,
                        text = normalized,
                        lineEnding = lineEnding,
                        lastModified = result.lastModified,
                        size = result.size
                    )
                    applyPendingReveal(tab)
                    if (projectRoot.isNotBlank()) {
                        lsp.openDocument(
                            projectId = projectId,
                            projectPath = projectRoot,
                            relativePath = tab.relativePath,
                            language = tab.language,
                            text = normalized
                        )
                        lspOpenPaths = lspOpenPaths + tab.relativePath
                    }
                }

                is ReadProjectFileResult.Failure -> tabManager.onLoadFailed(
                    projectId = projectId,
                    tabId = tab.id,
                    error = result.error,
                    detail = result.detail
                )
            }
        } finally {
            loadingTabs.remove(tab.id)
        }
    }

    /** Applies a pending "jump to build problem" request to the loaded tab. */
    private fun applyPendingReveal(tab: EditorTab) {
        val target = revealRequests.consume(projectId) ?: return
        if (target.relativePath != tab.relativePath) return
        tabManager.reveal(
            projectId = projectId,
            tabId = tab.id,
            line = target.line - 1,
            column = (target.column ?: 1) - 1
        )
    }

    /** Dirty tabs restored from disk may point at renamed or deleted files. */    private suspend fun validateRestoredDirtyTabs() {
        tabManager.tabs(projectId)
            .filter { it.isDirty && it.status == EditorTabStatus.READY }
            .forEach { tab ->
                when (val info = getInfo(tab)) {
                    is ProjectFileInfoResult.Success -> {
                        if (tab.baselineModified > 0 &&
                            info.entry.lastModified != tab.baselineModified
                        ) {
                            tabManager.setExternallyModified(projectId, tab.id, true)
                        }
                    }

                    is ProjectFileInfoResult.Failure -> if (
                        info.error == ProjectFileError.NOT_FOUND
                    ) {
                        tabManager.markMissing(projectId, tab.id)
                    }
                }
            }
    }

    // ---- Tab switching ----------------------------------------------------

    fun onTabSelected(tabId: String, snapshot: EditorSnapshot) {
        val active = _uiState.value.activeTab
        val safeSnapshot = snapshot.takeIf { active != null && active.status == EditorTabStatus.READY }
        tabManager.activate(projectId, tabId, safeSnapshot)
        val tab = tabManager.tabs(projectId).firstOrNull { it.id == tabId } ?: return
        if (tab.status == EditorTabStatus.LOADING) {
            viewModelScope.launch { loadTabContent(tab) }
        }
        schedulePersist()
    }

    fun onEditorChanged() {
        val activeId = _uiState.value.activeTabId ?: return
        tabManager.markDirty(projectId, activeId)
    }

    /** Keeps the active tab's buffer current; called after typing settles or on stop. */
    fun onEditorSnapshot(snapshot: EditorSnapshot, immediate: Boolean = false) {
        captureActive(snapshot)
        schedulePersist(immediate)
    }

    fun persistSession(snapshot: EditorSnapshot?) {
        if (snapshot != null) captureActive(snapshot)
        viewModelScope.launch { runCatching { tabManager.persist(projectId) } }
    }

    /** Re-checks the active file after returning to the editor screen. */
    fun onScreenResumed() {
        val tab = _uiState.value.activeTab ?: return
        viewModelScope.launch {
            when (val info = getInfo(tab)) {
                is ProjectFileInfoResult.Success -> {
                    if (tab.isDirty &&
                        tab.baselineModified > 0 &&
                        info.entry.lastModified != tab.baselineModified
                    ) {
                        tabManager.setExternallyModified(projectId, tab.id, true)
                    }
                }

                is ProjectFileInfoResult.Failure -> if (
                    info.error == ProjectFileError.NOT_FOUND
                ) {
                    tabManager.markMissing(projectId, tab.id)
                }
            }
        }
    }

    // ---- Saving -----------------------------------------------------------

    fun saveActive(snapshot: EditorSnapshot) {
        val state = _uiState.value
        val tab = state.activeTab ?: return
        if (state.isSaving || tab.status != EditorTabStatus.READY) return

        _uiState.update {
            it.copy(isSaving = true, saveError = null, saveErrorDetail = null, saveReport = null)
        }
        viewModelScope.launch {
            when (val outcome = writeTab(tab, snapshot, overwrite = false)) {
                SaveOutcome.Saved -> {
                    _uiState.update { it.copy(isSaving = false) }
                    schedulePersist(immediate = true)
                }

                SaveOutcome.Missing -> {
                    tabManager.markMissing(projectId, tab.id)
                    _uiState.update {
                        it.copy(isSaving = false, saveError = ProjectFileError.NOT_FOUND)
                    }
                }

                SaveOutcome.ChangedOnDisk -> _uiState.update {
                    it.copy(
                        isSaving = false,
                        externalChange = ExternalChangeState(
                            tabId = tab.id,
                            fileName = tab.name,
                            snapshot = snapshot
                        )
                    )
                }

                is SaveOutcome.Failed -> _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveError = outcome.error,
                        saveErrorDetail = outcome.detail
                    )
                }
            }
        }
    }

    fun saveAll(snapshot: EditorSnapshot) {
        val state = _uiState.value
        val activeId = state.activeTabId
        val dirty = state.tabs.filter { it.isDirty }
        if (dirty.isEmpty() || state.isSaving) return

        captureActive(snapshot)
        _uiState.update {
            it.copy(isSaving = true, saveError = null, saveErrorDetail = null, saveReport = null)
        }
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            val changed = mutableListOf<String>()
            for (tab in dirty) {
                val tabSnapshot = if (tab.id == activeId) {
                    snapshot
                } else {
                    tabManager.buffer(tab.id)?.toSnapshot()
                }
                if (tabSnapshot == null) {
                    failed += tab.name
                    continue
                }
                when (val outcome = writeTab(tab, tabSnapshot, overwrite = false)) {
                    SaveOutcome.Saved -> Unit
                    SaveOutcome.Missing -> {
                        tabManager.markMissing(projectId, tab.id)
                        failed += tab.name
                    }

                    SaveOutcome.ChangedOnDisk -> changed += tab.name
                    is SaveOutcome.Failed -> failed += tab.name
                }
            }
            _uiState.update {
                it.copy(
                    isSaving = false,
                    saveReport = if (failed.isEmpty() && changed.isEmpty()) {
                        null
                    } else {
                        SaveReport(failedNames = failed, changedNames = changed)
                    }
                )
            }
            schedulePersist(immediate = true)
        }
    }

    fun confirmExternalOverwrite() {
        val dialog = _uiState.value.externalChange ?: return
        if (dialog.isWorking) return
        val tab = tabManager.tabs(projectId).firstOrNull { it.id == dialog.tabId } ?: return
        _uiState.update {
            it.copy(externalChange = dialog.copy(isWorking = true, error = null))
        }
        viewModelScope.launch {
            when (val outcome = writeTab(tab, dialog.snapshot, overwrite = true)) {
                SaveOutcome.Saved -> {
                    _uiState.update { it.copy(externalChange = null) }
                    schedulePersist(immediate = true)
                }

                SaveOutcome.Missing -> {
                    tabManager.markMissing(projectId, tab.id)
                    _uiState.update {
                        it.copy(
                            externalChange = dialog.copy(
                                isWorking = false,
                                error = ProjectFileError.NOT_FOUND
                            )
                        )
                    }
                }

                SaveOutcome.ChangedOnDisk -> Unit

                is SaveOutcome.Failed -> _uiState.update {
                    it.copy(
                        externalChange = dialog.copy(
                            isWorking = false,
                            error = outcome.error,
                            errorDetail = outcome.detail
                        )
                    )
                }
            }
        }
    }

    fun reloadExternal() {
        val dialog = _uiState.value.externalChange ?: return
        if (dialog.isWorking) return
        val tab = tabManager.tabs(projectId).firstOrNull { it.id == dialog.tabId } ?: return
        _uiState.update { it.copy(externalChange = dialog.copy(isWorking = true, error = null)) }
        viewModelScope.launch {
            when (val result = readTab(tab)) {
                is ReadProjectFileResult.Success -> {
                    val lineEnding = if (result.content.contains("\r\n")) "\r\n" else "\n"
                    val normalized = result.content.replace("\r\n", "\n").replace('\r', '\n')
                    tabManager.reload(
                        projectId = projectId,
                        tabId = tab.id,
                        text = normalized,
                        lineEnding = lineEnding,
                        lastModified = result.lastModified,
                        size = result.size
                    )
                    _uiState.update { it.copy(externalChange = null, saveError = null) }
                    schedulePersist(immediate = true)
                }

                is ReadProjectFileResult.Failure -> {
                    if (result.error == ProjectFileError.NOT_FOUND) {
                        tabManager.markMissing(projectId, tab.id)
                    }
                    _uiState.update {
                        it.copy(
                            externalChange = dialog.copy(
                                isWorking = false,
                                error = result.error,
                                errorDetail = result.detail
                            )
                        )
                    }
                }
            }
        }
    }

    /** Banner action: replace the active tab with the current disk content. */
    fun reloadActive() {
        val tab = _uiState.value.activeTab ?: return
        viewModelScope.launch {
            when (val result = readTab(tab)) {
                is ReadProjectFileResult.Success -> {
                    val lineEnding = if (result.content.contains("\r\n")) "\r\n" else "\n"
                    val normalized = result.content.replace("\r\n", "\n").replace('\r', '\n')
                    tabManager.reload(
                        projectId = projectId,
                        tabId = tab.id,
                        text = normalized,
                        lineEnding = lineEnding,
                        lastModified = result.lastModified,
                        size = result.size
                    )
                    schedulePersist(immediate = true)
                }

                is ReadProjectFileResult.Failure -> {
                    if (result.error == ProjectFileError.NOT_FOUND) {
                        tabManager.markMissing(projectId, tab.id)
                    }
                    _uiState.update {
                        it.copy(saveError = result.error, saveErrorDetail = result.detail)
                    }
                }
            }
        }
    }

    /** Opens the overwrite / reload decision for the active externally changed file. */
    fun promptExternalChange(snapshot: EditorSnapshot) {
        val tab = _uiState.value.activeTab ?: return
        _uiState.update {
            it.copy(
                externalChange = ExternalChangeState(
                    tabId = tab.id,
                    fileName = tab.name,
                    snapshot = snapshot
                )
            )
        }
    }

    fun dismissExternalChange() {
        if (_uiState.value.externalChange?.isWorking == true) return
        _uiState.update { it.copy(externalChange = null) }
    }

    // ---- Closing ----------------------------------------------------------

    fun requestCloseTab(tabId: String, snapshot: EditorSnapshot) {
        captureActive(snapshot)
        val tab = tabManager.tabs(projectId).firstOrNull { it.id == tabId } ?: return
        if (tab.isDirty) {
            openCloseDialog(CloseScope.TABS, listOf(tab), snapshot)
        } else {
            tabManager.close(projectId, tabId)
            schedulePersist(immediate = true)
        }
    }

    fun requestCloseOthers(snapshot: EditorSnapshot) {
        captureActive(snapshot)
        val state = _uiState.value
        val activeId = state.activeTabId ?: return
        val dirtyOthers = state.tabs.filter { it.id != activeId && it.isDirty }
        if (dirtyOthers.isEmpty()) {
            tabManager.closeOthers(projectId)
            schedulePersist(immediate = true)
        } else {
            openCloseDialog(CloseScope.OTHERS, dirtyOthers, snapshot)
        }
    }

    fun requestCloseAll(snapshot: EditorSnapshot) {
        captureActive(snapshot)
        val dirty = _uiState.value.tabs.filter { it.isDirty }
        if (dirty.isEmpty()) {
            tabManager.closeAll(projectId)
            schedulePersist(immediate = true)
        } else {
            openCloseDialog(CloseScope.ALL, dirty, snapshot)
        }
    }

    fun requestExit(snapshot: EditorSnapshot) {
        captureActive(snapshot)
        val dirty = _uiState.value.tabs.filter { it.isDirty }
        if (dirty.isEmpty()) {
            viewModelScope.launch {
                runCatching { tabManager.persist(projectId) }
                _uiState.update { it.copy(exitRequested = true) }
            }
        } else {
            openCloseDialog(CloseScope.EXIT, dirty, snapshot)
        }
    }

    fun confirmCloseWithSave() {
        val dialog = _uiState.value.closeDialog ?: return
        if (dialog.isSaving) return
        _uiState.update {
            it.copy(
                closeDialog = dialog.copy(
                    isSaving = true,
                    failedNames = emptyList(),
                    changedNames = emptyList()
                )
            )
        }
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            val changed = mutableListOf<String>()
            for (id in dialog.dirtyTabIds) {
                val tab = tabManager.tabs(projectId).firstOrNull { it.id == id } ?: continue
                val snapshot = if (id == dialog.activeTabId) {
                    dialog.activeSnapshot
                } else {
                    tabManager.buffer(id)?.toSnapshot()
                }
                if (snapshot == null) {
                    failed += tab.name
                    continue
                }
                when (val outcome = writeTab(tab, snapshot, overwrite = false)) {
                    SaveOutcome.Saved -> Unit
                    SaveOutcome.Missing -> {
                        tabManager.markMissing(projectId, tab.id)
                        failed += tab.name
                    }

                    SaveOutcome.ChangedOnDisk -> changed += tab.name
                    is SaveOutcome.Failed -> failed += tab.name
                }
            }
            if (failed.isEmpty() && changed.isEmpty()) {
                if (dialog.scope == CloseScope.EXIT) {
                    runCatching { tabManager.persist(projectId) }
                    _uiState.update { it.copy(closeDialog = null, exitRequested = true) }
                } else {
                    applyClose(dialog)
                    _uiState.update { it.copy(closeDialog = null) }
                    schedulePersist(immediate = true)
                }
            } else {
                _uiState.update {
                    it.copy(
                        closeDialog = dialog.copy(
                            isSaving = false,
                            failedNames = failed,
                            changedNames = changed
                        )
                    )
                }
            }
        }
    }

    fun discardClose() {
        val dialog = _uiState.value.closeDialog ?: return
        if (dialog.isSaving) return
        when (dialog.scope) {
            CloseScope.TABS -> dialog.dirtyTabIds.forEach { tabManager.close(projectId, it) }
            CloseScope.OTHERS -> tabManager.closeOthers(projectId)
            CloseScope.ALL -> tabManager.closeAll(projectId)
            CloseScope.EXIT -> {
                dialog.dirtyTabIds.forEach { tabManager.close(projectId, it) }
                _uiState.update { it.copy(closeDialog = null) }
                viewModelScope.launch {
                    runCatching { tabManager.persist(projectId) }
                    _uiState.update { it.copy(exitRequested = true) }
                }
                return
            }
        }
        _uiState.update { it.copy(closeDialog = null) }
        schedulePersist(immediate = true)
    }

    fun dismissCloseDialog() {
        if (_uiState.value.closeDialog?.isSaving == true) return
        _uiState.update { it.copy(closeDialog = null) }
    }

    fun consumeExit() {
        _uiState.update { it.copy(exitRequested = false) }
    }

    fun dismissSaveError() {
        _uiState.update { it.copy(saveError = null, saveErrorDetail = null) }
    }

    fun dismissSaveReport() {
        _uiState.update { it.copy(saveReport = null) }
    }

    fun dismissTabLimit() {
        _uiState.update { it.copy(tabLimitReached = false) }
    }

    private fun applyClose(dialog: CloseDialogState) {
        when (dialog.scope) {
            CloseScope.TABS -> dialog.dirtyTabIds.forEach { tabManager.close(projectId, it) }
            CloseScope.OTHERS -> tabManager.closeOthers(projectId)
            CloseScope.ALL -> tabManager.closeAll(projectId)
            CloseScope.EXIT -> Unit
        }
    }

    private fun openCloseDialog(
        scope: CloseScope,
        dirtyTabs: List<EditorTab>,
        activeSnapshot: EditorSnapshot
    ) {
        _uiState.update {
            it.copy(
                closeDialog = CloseDialogState(
                    scope = scope,
                    dirtyTabIds = dirtyTabs.map { tab -> tab.id },
                    dirtyNames = dirtyTabs.map { tab -> tab.name },
                    activeTabId = it.activeTabId,
                    activeSnapshot = activeSnapshot
                )
            )
        }
    }

    // ---- Internals --------------------------------------------------------

    private suspend fun writeTab(
        tab: EditorTab,
        snapshot: EditorSnapshot,
        overwrite: Boolean
    ): SaveOutcome {
        if (tab.status == EditorTabStatus.MISSING) return SaveOutcome.Missing

        val info = getInfo(tab)
        val entry = when (info) {
            is ProjectFileInfoResult.Success -> info.entry
            is ProjectFileInfoResult.Failure -> {
                if (info.error == ProjectFileError.NOT_FOUND) return SaveOutcome.Missing
                return SaveOutcome.Failed(info.error, info.detail)
            }
        }
        if (!overwrite &&
            tab.baselineModified > 0 &&
            entry.lastModified > 0 &&
            entry.lastModified != tab.baselineModified
        ) {
            return SaveOutcome.ChangedOnDisk
        }

        val payload = if (tab.lineEnding == "\r\n") {
            snapshot.text.replace("\n", "\r\n")
        } else {
            snapshot.text
        }
        val write = try {
            repository.writeProjectFile(projectId, tab.relativePath, payload)
        } catch (error: Exception) {
            WriteProjectFileResult.Failure(ProjectFileError.IO, error.message)
        }
        return when (write) {
            WriteProjectFileResult.Success -> {
                // Flush the editor text before didSave so the server never sees
                // a stale document at save time.
                lsp.changeDocument(projectId, tab.relativePath, snapshot.text)
                lsp.saveDocument(projectId, tab.relativePath)
                val fresh = getInfo(tab)
                val freshEntry = (fresh as? ProjectFileInfoResult.Success)?.entry
                tabManager.markSaved(
                    projectId = projectId,
                    tabId = tab.id,
                    snapshot = snapshot,
                    lastModified = freshEntry?.lastModified ?: entry.lastModified,
                    size = freshEntry?.size ?: payload.toByteArray(Charsets.UTF_8).size.toLong()
                )
                SaveOutcome.Saved
            }

            is WriteProjectFileResult.Failure -> {
                if (write.error == ProjectFileError.NOT_FOUND) {
                    tabManager.markMissing(projectId, tab.id)
                    SaveOutcome.Missing
                } else {
                    SaveOutcome.Failed(write.error, write.detail)
                }
            }
        }
    }

    private suspend fun getInfo(tab: EditorTab): ProjectFileInfoResult = try {
        repository.getProjectFileInfo(projectId, tab.relativePath)
    } catch (error: Exception) {
        ProjectFileInfoResult.Failure(ProjectFileError.IO, error.message)
    }

    /**
     * Stores the editor snapshot only when the active tab is the document the
     * editor actually shows. Tabs that are loading/missing/failed have no
     * rendered document, so their snapshot would belong to another file.
     */
    private fun captureActive(snapshot: EditorSnapshot) {
        val active = _uiState.value.activeTab ?: return
        if (active.status != EditorTabStatus.READY) return
        tabManager.updateActive(projectId, snapshot)
    }

    // ---- Language intelligence -------------------------------------------

    val lspState: StateFlow<LspSnapshot> get() = lsp.state

    val lspDiagnostics: StateFlow<Map<LspDocumentKey, List<LspDiagnostic>>> get() = lsp.diagnostics

    fun lspCapabilities(): LspCapabilities {
        val tab = _uiState.value.activeTab ?: return LspCapabilities()
        return lsp.capabilities(projectId, tab.relativePath)
    }

    fun lspServerForActiveTab(): LspServerInfo? {
        val tab = _uiState.value.activeTab ?: return null
        return lsp.serverFor(tab.language)
    }

    /** Announces the active tab; safe to call on every activation. */
    fun syncActiveDocument() {
        val tab = _uiState.value.activeTab ?: return
        if (tab.status != EditorTabStatus.READY || projectRoot.isBlank()) return
        val text = tabManager.buffer(tab.id)?.text ?: return
        lsp.openDocument(projectId, projectRoot, tab.relativePath, tab.language, text)
        lspOpenPaths = lspOpenPaths + tab.relativePath
    }

    fun onDocumentTextChanged(text: String) {
        val tab = _uiState.value.activeTab ?: return
        if (tab.status != EditorTabStatus.READY) return
        lsp.changeDocument(projectId, tab.relativePath, text)
    }

    fun closeLspProject() {
        lsp.closeProject(projectId)
        lspOpenPaths = emptySet()
    }

    fun openFileAt(relativePath: String, line: Int, column: Int) {
        viewModelScope.launch {
            revealRequests.request(
                projectId,
                EditorRevealTarget(relativePath, line + 1, column + 1)
            )
            openFile(relativePath)
        }
    }

    /** Blocking call used by Sora's completion thread, never by the UI thread. */
    fun lspCompletion(
        relativePath: String,
        line: Int,
        column: Int
    ): List<LspCompletionItem> {
        if (Looper.myLooper() == Looper.getMainLooper()) return emptyList()
        return runBlocking {
            withTimeoutOrNull(COMPLETION_TIMEOUT_MS) {
                lsp.completion(projectId, relativePath, line, column)
            }
        }.orEmpty()
    }

    suspend fun lspHover(line: Int, column: Int): LspHover? {
        val tab = _uiState.value.activeTab ?: return null
        return lsp.hover(projectId, tab.relativePath, line, column)
    }

    suspend fun lspDefinition(line: Int, column: Int): List<LspLocation> {
        val tab = _uiState.value.activeTab ?: return emptyList()
        return lsp.definition(projectId, tab.relativePath, line, column)
    }

    suspend fun lspReferences(line: Int, column: Int): List<LspLocation> {
        val tab = _uiState.value.activeTab ?: return emptyList()
        return lsp.references(projectId, tab.relativePath, line, column)
    }

    suspend fun lspSymbols(): List<LspDocumentSymbol> {
        val tab = _uiState.value.activeTab ?: return emptyList()
        return lsp.documentSymbols(projectId, tab.relativePath)
    }

    suspend fun lspFormat(): List<LspTextEdit> {
        val tab = _uiState.value.activeTab ?: return emptyList()
        return lsp.format(projectId, tab.relativePath)
    }

    fun lspDiagnosticsForActiveTab(): List<LspDiagnostic> {
        val tab = _uiState.value.activeTab ?: return emptyList()
        return lsp.diagnostics.value[LspDocumentKey(projectId, tab.relativePath)].orEmpty()
    }

    /** Resolves the active tab's diagnostics from an already-collected map. */
    fun activeTabDiagnostics(
        map: Map<LspDocumentKey, List<LspDiagnostic>>
    ): List<LspDiagnostic> {
        val tab = _uiState.value.activeTab ?: return emptyList()
        return map[LspDocumentKey(projectId, tab.relativePath)].orEmpty()
    }

    private fun syncLspDocuments(currentPaths: Set<String>) {
        val closed = lspOpenPaths - currentPaths
        closed.forEach { path -> lsp.closeDocument(projectId, path) }
        if (closed.isNotEmpty()) lspOpenPaths = lspOpenPaths - closed
    }

    override fun onCleared() {
        // Leaving the editor closes the documents; the servers then follow
        // their idle timeout instead of pinning memory forever.
        lsp.closeProject(projectId)
        super.onCleared()
    }

    private suspend fun readTab(tab: EditorTab): ReadProjectFileResult = try {
        repository.readProjectFile(projectId, tab.relativePath)
    } catch (error: Exception) {
        ReadProjectFileResult.Failure(ProjectFileError.IO, error.message)
    }

    private fun schedulePersist(immediate: Boolean = false) {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            if (!immediate) delay(PERSIST_DEBOUNCE_MS)
            runCatching { tabManager.persist(projectId) }
        }
    }

    private fun EditorTabBuffer.toSnapshot() = EditorSnapshot(
        text = text,
        cursorLine = cursorLine,
        cursorColumn = cursorColumn,
        scrollY = scrollY
    )

    private sealed interface SaveOutcome {
        data object Saved : SaveOutcome
        data object Missing : SaveOutcome
        data object ChangedOnDisk : SaveOutcome
        data class Failed(
            val error: ProjectFileError,
            val detail: String? = null
        ) : SaveOutcome
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 600L
        const val COMPLETION_TIMEOUT_MS = 1_500L
    }
}
