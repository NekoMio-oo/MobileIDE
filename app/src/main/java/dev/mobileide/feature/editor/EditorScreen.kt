package dev.mobileide.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobileide.R
import dev.mobileide.core.ui.projectFileErrorMessage
import dev.mobileide.domain.editor.EditorSnapshot
import dev.mobileide.domain.editor.EditorTab
import dev.mobileide.domain.editor.EditorTabStatus
import dev.mobileide.domain.lsp.LspDocumentSymbol
import dev.mobileide.domain.lsp.LspServerState
import dev.mobileide.domain.repository.ProjectFileError
import dev.mobileide.feature.editor.components.EditorExternalChangeDialog
import dev.mobileide.feature.editor.components.EditorFindBar
import dev.mobileide.feature.editor.components.EditorQuickBar
import dev.mobileide.feature.editor.components.EditorTabBar
import dev.mobileide.feature.editor.components.EditorTabLimitDialog
import dev.mobileide.feature.editor.components.EditorTopBar
import dev.mobileide.feature.editor.components.EditorUnsavedDialog
import dev.mobileide.feature.editor.components.LspPanel
import dev.mobileide.feature.editor.components.LspPanelSheet
import dev.mobileide.feature.editor.components.LspSymbolRow
import dev.mobileide.feature.editor.components.SoraCodeEditor
import dev.mobileide.feature.editor.components.rememberEditorColorScheme
import dev.mobileide.feature.editor.components.toSoraLanguage
import dev.mobileide.feature.editor.lsp.LspLanguage
import dev.mobileide.feature.editor.model.EditorLspAction
import dev.mobileide.feature.editor.model.EditorLspMenu
import dev.mobileide.feature.editor.model.EditorUiState
import dev.mobileide.feature.editor.model.SaveReport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Multi-tab editor: a compact tab strip above one Sora view. Each tab keeps its
 * own Content (text, caret, undo) inside [EditorController]; the ViewModel owns
 * save/close policies and the session persistence.
 */
@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onOpenBuild: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val controller = remember { EditorController() }
    val colorScheme = rememberEditorColorScheme()
    val scope = rememberCoroutineScope()
    val snapshotHolder = remember { SnapshotJobHolder() }

    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var cursorLine by remember { mutableIntStateOf(1) }
    var cursorColumn by remember { mutableIntStateOf(1) }

    val activeTab = uiState.activeTab
    val canEdit = activeTab != null && activeTab.status == EditorTabStatus.READY
    val document = viewModel.renderDocument()

    val lspState by viewModel.lspState.collectAsStateWithLifecycle()
    val diagnosticsMap by viewModel.lspDiagnostics.collectAsStateWithLifecycle()
    val diagnostics = viewModel.activeTabDiagnostics(diagnosticsMap)
    var lspPanel by remember { mutableStateOf<LspPanel?>(null) }
    val lspSyncHolder = remember { SnapshotJobHolder() }

    fun currentSnapshot(): EditorSnapshot = controller.snapshot()

    fun scheduleSnapshot() {
        snapshotHolder.job?.cancel()
        snapshotHolder.job = scope.launch {
            delay(SNAPSHOT_DEBOUNCE_MS)
            viewModel.onEditorSnapshot(controller.snapshot())
        }
    }

    BackHandler { viewModel.requestExit(currentSnapshot()) }

    LaunchedEffect(uiState.exitRequested) {
        if (uiState.exitRequested) {
            viewModel.consumeExit()
            onBack()
        }
    }

    LaunchedEffect(uiState.tabs.map { it.id }) {
        controller.retainTabs(uiState.tabs.map { it.id }.toSet())
    }

    LaunchedEffect(activeTab?.id, activeTab?.status) {
        if (activeTab?.status == EditorTabStatus.READY) viewModel.syncActiveDocument()
    }

    LaunchedEffect(lspState.settings.enabled, lspState.settings.autoStart) {
        if (lspState.settings.enabled) viewModel.syncActiveDocument()
    }

    LaunchedEffect(activeTab?.id, document?.revision, diagnostics) {
        if (activeTab?.status == EditorTabStatus.READY) {
            controller.showDiagnostics(diagnostics)
        } else {
            controller.clearDiagnostics()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.closeLspProject() }
    }

    LaunchedEffect(document?.tabId, document?.revision) {
        document?.let {
            cursorLine = it.cursorLine + 1
            cursorColumn = it.cursorColumn + 1
        }
    }

    LaunchedEffect(uiState.activeTabId) {
        if (showFindBar) {
            controller.stopSearch()
            findQuery = ""
            showFindBar = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onScreenResumed()
                Lifecycle.Event.ON_STOP -> viewModel.persistSession(controller.snapshot())
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hoverTitle = stringResource(R.string.lsp_panel_hover)
    val definitionsTitle = stringResource(R.string.lsp_panel_definitions)
    val referencesTitle = stringResource(R.string.lsp_panel_references)
    val symbolsTitle = stringResource(R.string.lsp_panel_symbols)
    val problemsTitle = stringResource(R.string.lsp_panel_problems)
    val formatTitle = stringResource(R.string.lsp_action_format)
    val noResults = stringResource(R.string.lsp_no_results)
    val noHover = stringResource(R.string.lsp_no_hover)
    val nothingToFormat = stringResource(R.string.lsp_nothing_to_format)

    fun navigateTo(path: String, line: Int, column: Int) {
        if (path == activeTab?.relativePath) {
            controller.revealPosition(line, column)
        } else {
            viewModel.openFileAt(path, line, column)
        }
        lspPanel = null
    }

    fun flattenSymbols(
        symbols: List<LspDocumentSymbol>,
        depth: Int,
        out: MutableList<LspSymbolRow>
    ) {
        symbols.forEach { symbol ->
            out += LspSymbolRow(
                name = symbol.name,
                detail = symbol.detail,
                depth = depth,
                line = symbol.selectionRange.start.line,
                column = symbol.selectionRange.start.character
            )
            flattenSymbols(symbol.children, depth + 1, out)
        }
    }

    fun handleLspAction(action: EditorLspAction) {
        val (line, column) = controller.cursorPosition()
        when (action) {
            EditorLspAction.COMPLETE -> controller.requestCompletion()
            EditorLspAction.PROBLEMS -> lspPanel = LspPanel.Problems(
                title = problemsTitle,
                path = activeTab?.relativePath.orEmpty(),
                items = diagnostics
            )

            EditorLspAction.FORMAT -> scope.launch {
                val edits = viewModel.lspFormat()
                if (edits.isEmpty()) {
                    lspPanel = LspPanel.Text(formatTitle, nothingToFormat)
                } else if (controller.applyTextEdits(edits)) {
                    viewModel.onDocumentTextChanged(controller.currentText())
                }
            }

            EditorLspAction.HOVER -> scope.launch {
                val hover = viewModel.lspHover(line, column)
                lspPanel = if (hover == null) {
                    LspPanel.Text(hoverTitle, noHover)
                } else {
                    LspPanel.Text(hoverTitle, hover.contents)
                }
            }

            EditorLspAction.DEFINITION -> scope.launch {
                val locations = viewModel.lspDefinition(line, column)
                when {
                    locations.isEmpty() -> lspPanel = LspPanel.Text(definitionsTitle, noResults)
                    locations.size == 1 -> navigateTo(
                        locations.first().relativePath,
                        locations.first().range.start.line,
                        locations.first().range.start.character
                    )

                    else -> lspPanel = LspPanel.Locations(definitionsTitle, locations)
                }
            }

            EditorLspAction.REFERENCES -> scope.launch {
                val locations = viewModel.lspReferences(line, column)
                lspPanel = if (locations.isEmpty()) {
                    LspPanel.Text(referencesTitle, noResults)
                } else {
                    LspPanel.Locations(referencesTitle, locations)
                }
            }

            EditorLspAction.SYMBOLS -> scope.launch {
                val rows = mutableListOf<LspSymbolRow>()
                flattenSymbols(viewModel.lspSymbols(), 0, rows)
                lspPanel = if (rows.isEmpty()) {
                    LspPanel.Text(symbolsTitle, noResults)
                } else {
                    LspPanel.Symbols(
                        title = symbolsTitle,
                        path = activeTab?.relativePath.orEmpty(),
                        items = rows
                    )
                }
            }
        }
    }

    val lspCapabilities = viewModel.lspCapabilities()
    val lspServerId = viewModel.lspServerForActiveTab()?.id
    val lspServer = lspState.servers.firstOrNull { it.id == lspServerId }
    val lspMenu = EditorLspMenu(
        serverName = lspServer?.displayName,
        canComplete = lspCapabilities.completion && lspServer?.state == LspServerState.READY,
        canHover = lspCapabilities.hover && lspServer?.state == LspServerState.READY,
        canDefinition = lspCapabilities.definition && lspServer?.state == LspServerState.READY,
        canReferences = lspCapabilities.references && lspServer?.state == LspServerState.READY,
        canSymbols = lspCapabilities.documentSymbol && lspServer?.state == LspServerState.READY,
        canFormat = lspCapabilities.formatting && lspServer?.state == LspServerState.READY,
        problemCount = diagnostics.size
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
    ) {
        EditorTopBar(
            fileName = activeTab?.name ?: uiState.projectName,
            meta = editorMeta(activeTab, cursorLine, cursorColumn),
            isDirty = activeTab?.isDirty == true,
            isSaving = uiState.isSaving,
            canSave = canEdit && activeTab?.isDirty == true && !uiState.isSaving,
            onBack = { viewModel.requestExit(currentSnapshot()) },
            onSave = { viewModel.saveActive(currentSnapshot()) },
            onUndo = controller::undo,
            onRedo = controller::redo,
            onSelectAll = controller::selectAll,
            onCut = controller::cut,
            onCopy = controller::copy,
            onPaste = controller::paste,
            onFind = { if (canEdit) showFindBar = true },
            onBuildProject = onOpenBuild,
            onTerminal = onOpenTerminal,
            lspMenu = lspMenu,
            onLspAction = { action -> handleLspAction(action) }
        )

        EditorTabBar(
            tabs = uiState.tabs,
            activeTabId = uiState.activeTabId,
            onSelect = { tabId ->
                viewModel.onDocumentTextChanged(controller.currentText())
                viewModel.onTabSelected(tabId, currentSnapshot())
            },
            onClose = { tabId -> viewModel.requestCloseTab(tabId, currentSnapshot()) },
            onCloseCurrent = {
                uiState.activeTabId?.let { viewModel.requestCloseTab(it, currentSnapshot()) }
            },
            onCloseOthers = { viewModel.requestCloseOthers(currentSnapshot()) },
            onCloseAll = { viewModel.requestCloseAll(currentSnapshot()) },
            onSave = { viewModel.saveActive(currentSnapshot()) },
            onSaveAll = { viewModel.saveAll(currentSnapshot()) }
        )

        if (uiState.tabs.isNotEmpty()) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )
        }

        if (showFindBar && canEdit) {
            EditorFindBar(
                query = findQuery,
                onQueryChange = { query ->
                    findQuery = query
                    controller.search(query)
                },
                onPrevious = { controller.findPrevious() },
                onNext = { controller.findNext() },
                onClose = {
                    controller.stopSearch()
                    findQuery = ""
                    showFindBar = false
                }
            )
        }

        if (canEdit && activeTab?.externallyModified == true) {
            ExternalChangedBanner(
                onReview = { viewModel.promptExternalChange(currentSnapshot()) }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when {
                uiState.isLoading -> CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp),
                    strokeWidth = 2.dp
                )

                uiState.loadError != null -> EditorErrorState(
                    error = uiState.loadError!!,
                    detail = uiState.loadErrorDetail,
                    onBack = onBack
                )

                uiState.tabs.isEmpty() -> NoTabsState(onBack = onBack)

                else -> {
                    SoraCodeEditor(
                        controller = controller,
                        document = document,
                        colorScheme = colorScheme,
                        onContentChanged = {
                            viewModel.onEditorChanged()
                            scheduleSnapshot()
                            lspSyncHolder.job?.cancel()
                            lspSyncHolder.job = scope.launch {
                                delay(LSP_SYNC_DEBOUNCE_MS)
                                viewModel.onDocumentTextChanged(controller.currentText())
                            }
                        },
                        onCursorChanged = { line, column ->
                            cursorLine = line
                            cursorColumn = column
                        },
                        wrapLanguage = { language, tabId ->
                            val path = uiState.tabs.firstOrNull { it.id == tabId }?.relativePath
                            if (path == null) {
                                language.toSoraLanguage()
                            } else {
                                LspLanguage(
                                    delegate = language.toSoraLanguage(),
                                    relativePath = path,
                                    source = { relative, line, column, text, onResult ->
                                        viewModel.lspCompletion(relative, line, column, text, onResult)
                                    }
                                )
                            }
                        }
                    )
                    when (activeTab?.status) {
                        EditorTabStatus.LOADING -> LoadingOverlay()
                        EditorTabStatus.MISSING -> TabProblemOverlay(
                            title = stringResource(R.string.editor_file_missing_title),
                            message = stringResource(R.string.editor_file_missing_message),
                            detail = null,
                            onClose = {
                                activeTab?.let {
                                    viewModel.requestCloseTab(it.id, currentSnapshot())
                                }
                            }
                        )

                        EditorTabStatus.FAILED -> TabProblemOverlay(
                            title = stringResource(
                                projectFileErrorMessage(
                                    activeTab.loadError ?: ProjectFileError.IO
                                )
                            ),
                            message = null,
                            detail = activeTab.loadErrorDetail,
                            onClose = {
                                activeTab?.let {
                                    viewModel.requestCloseTab(it.id, currentSnapshot())
                                }
                            }
                        )

                        else -> Unit
                    }
                }
            }
        }

        uiState.saveError?.let { error ->
            SaveErrorBanner(
                error = error,
                detail = uiState.saveErrorDetail,
                onDismiss = viewModel::dismissSaveError
            )
        }

        uiState.saveReport?.let { report ->
            SaveReportBanner(report = report, onDismiss = viewModel::dismissSaveReport)
        }

        if (canEdit) {
            EditorQuickBar(controller = controller)
        }
    }

    uiState.closeDialog?.let { dialog ->
        EditorUnsavedDialog(
            dialog = dialog,
            onSave = viewModel::confirmCloseWithSave,
            onDiscard = viewModel::discardClose,
            onCancel = viewModel::dismissCloseDialog
        )
    }

    uiState.externalChange?.let { state ->
        EditorExternalChangeDialog(
            state = state,
            onOverwrite = viewModel::confirmExternalOverwrite,
            onReload = viewModel::reloadExternal,
            onCancel = viewModel::dismissExternalChange
        )
    }

    if (uiState.tabLimitReached) {
        EditorTabLimitDialog(onDismiss = viewModel::dismissTabLimit)
    }

    lspPanel?.let { panel ->
        LspPanelSheet(
            panel = panel,
            onDismiss = { lspPanel = null },
            onNavigate = { path, line, column -> navigateTo(path, line, column) }
        )
    }
}

@Composable
private fun editorMeta(tab: EditorTab?, cursorLine: Int, cursorColumn: Int): String {
    if (tab == null) return stringResource(R.string.editor_tabs_empty_title)
    return when (tab.status) {
        EditorTabStatus.LOADING -> stringResource(R.string.editor_saving)
        EditorTabStatus.MISSING -> tab.name
        EditorTabStatus.FAILED -> tab.language.displayName
        EditorTabStatus.READY -> {
            val base = stringResource(
                R.string.editor_meta,
                tab.language.displayName,
                cursorLine,
                cursorColumn
            )
            base + " · " + stringResource(
                if (tab.isDirty) R.string.editor_unsaved else R.string.editor_saved
            )
        }
    }
}

@Composable
private fun NoTabsState(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_file),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.editor_tabs_empty_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.editor_tabs_empty_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) {
            Text(stringResource(R.string.editor_tabs_empty_action))
        }
    }
}

@Composable
private fun LoadingOverlay() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun TabProblemOverlay(
    title: String,
    message: String?,
    detail: String?,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            message?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            detail?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.editor_tab_close))
            }
        }
    }
}

@Composable
private fun ExternalChangedBanner(onReview: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.editor_external_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onReview) {
            Text(
                text = stringResource(R.string.editor_external_review),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun EditorErrorState(
    error: ProjectFileError,
    detail: String?,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_file),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (error == ProjectFileError.FILE_TOO_LARGE) {
                stringResource(R.string.editor_error_too_large, formatFileSizeLabel(detail))
            } else {
                stringResource(projectFileErrorMessage(error))
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        detail?.takeIf { it.isNotBlank() && error != ProjectFileError.FILE_TOO_LARGE }?.let {
            Spacer(Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.action_back))
        }
    }
}

@Composable
private fun SaveErrorBanner(
    error: ProjectFileError,
    detail: String?,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(projectFileErrorMessage(error)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDismiss) {
            Text(
                text = stringResource(R.string.action_dismiss),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SaveReportBanner(report: SaveReport, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (report.failedNames.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.editor_save_failed_names,
                        report.failedNames.joinToString(", ")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (report.changedNames.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.editor_save_changed_names,
                        report.changedNames.joinToString(", ")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        TextButton(onClick = onDismiss) {
            Text(
                text = stringResource(R.string.action_dismiss),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

private fun formatFileSizeLabel(detail: String?): String {
    val bytes = detail?.toLongOrNull() ?: return ""
    val megabytes = bytes / (1024.0 * 1024.0)
    return String.format(java.util.Locale.US, "%.1f MB", megabytes)
}

private class SnapshotJobHolder {
    var job: Job? = null
}

private const val SNAPSHOT_DEBOUNCE_MS = 1_200L
private const val LSP_SYNC_DEBOUNCE_MS = 300L
