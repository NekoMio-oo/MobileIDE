package dev.mobileide.feature.fileexplorer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobileide.MobileIdeApplication
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.EmptyState
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.core.ui.projectFileErrorMessage
import dev.mobileide.core.ui.rememberStoragePermissionState
import dev.mobileide.domain.files.FileType
import dev.mobileide.domain.repository.ProjectFileEntry
import dev.mobileide.feature.fileexplorer.components.DeleteEntryDialog
import dev.mobileide.feature.fileexplorer.components.EntryInfoDialog
import dev.mobileide.feature.fileexplorer.components.EntryNameDialog
import dev.mobileide.feature.fileexplorer.components.FileBreadcrumb
import dev.mobileide.feature.fileexplorer.components.FileEntryRow
import dev.mobileide.feature.fileexplorer.components.FileExplorerTopBar
import dev.mobileide.feature.fileexplorer.components.FileSearchHeader
import dev.mobileide.feature.fileexplorer.components.ProjectInfoDialog
import dev.mobileide.feature.fileexplorer.components.SearchResultRow
import dev.mobileide.feature.fileexplorer.model.FileExplorerUiState
import kotlinx.coroutines.launch

/**
 * Real-filesystem project browser: navigation, search, create, rename, delete
 * and details. Opening a text file hands the project-relative path to the
 * existing editor route, which re-reads the file through the repository.
 */
@Composable
fun FileExplorerScreen(
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenBuild: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FileExplorerViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val allFilesAccess = remember(context) {
        (context.applicationContext as MobileIdeApplication).container.allFilesAccess
    }
    val permissionState = rememberStoragePermissionState(allFilesAccess)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showProjectInfo by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = uiState.isSearchMode || uiState.canGoUp) {
        if (uiState.isSearchMode) viewModel.exitSearch() else viewModel.goUp()
    }

    val openTextFile: (ProjectFileEntry) -> Unit = { entry ->
        if (FileType.isProbablyBinary(entry.name)) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.explorer_binary_blocked, entry.name)
                )
            }
        } else {
            onOpenFile(entry.path)
        }
    }

    val openEntry: (ProjectFileEntry) -> Unit = { entry ->
        if (entry.isDirectory) viewModel.openDirectory(entry.name) else openTextFile(entry)
    }

    val createdFile = uiState.createdFile
    LaunchedEffect(createdFile?.path) {
        if (createdFile == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = context.getString(R.string.explorer_created_file, createdFile.name),
            actionLabel = context.getString(R.string.action_open),
            duration = SnackbarDuration.Short
        )
        viewModel.consumeCreatedFile()
        if (result == SnackbarResult.ActionPerformed) {
            if (FileType.isProbablyBinary(createdFile.name)) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.explorer_binary_blocked, createdFile.name)
                )
            } else {
                onOpenFile(createdFile.path)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            FileExplorerTopBar(
                title = uiState.project?.name ?: stringResource(R.string.app_name),
                subtitle = if (uiState.currentPath.isBlank()) {
                    stringResource(R.string.explorer_subtitle_root)
                } else {
                    "/${uiState.currentPath}"
                },
                onBack = onBack,
                onSearch = {
                    if (uiState.isSearchMode) viewModel.exitSearch() else viewModel.startSearch()
                },
                onNewFile = { viewModel.openCreateDialog(createFolder = false) },
                onNewFolder = { viewModel.openCreateDialog(createFolder = true) },
                onRefresh = viewModel::refresh,
                onBuild = onOpenBuild,
                onTerminal = onOpenTerminal,
                onProjectInfo = {
                    if (uiState.project != null) showProjectInfo = true
                }
            )

            when {
                !uiState.storageGranted -> StorageGate(
                    onGrant = permissionState.request,
                    modifier = Modifier.weight(1f)
                )

                uiState.isSearchMode -> {
                    FileSearchHeader(
                        query = uiState.searchQuery,
                        filter = uiState.searchFilter,
                        onQueryChange = viewModel::onSearchQueryChanged,
                        onFilterSelected = viewModel::onSearchFilterSelected,
                        onClose = viewModel::exitSearch
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                    SearchContent(
                        state = uiState,
                        onFolder = viewModel::openSearchFolder,
                        onFile = openTextFile,
                        onRetry = viewModel::retry,
                        modifier = Modifier.weight(1f)
                    )
                }

                else -> {
                    FileBreadcrumb(
                        projectName = uiState.project?.name.orEmpty(),
                        currentPath = uiState.currentPath,
                        canGoUp = uiState.canGoUp,
                        onUp = viewModel::goUp,
                        onNavigate = viewModel::loadDirectory
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                    DirectoryContent(
                        state = uiState,
                        onEntryClick = openEntry,
                        onRename = viewModel::openRenameDialog,
                        onInfo = viewModel::showInfo,
                        onDelete = viewModel::requestDelete,
                        onRetry = viewModel::retry,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(MobileIdeSpacing.lg)
        )
    }

    uiState.entryDialog?.let { dialog ->
        EntryNameDialog(
            dialog = dialog,
            location = uiState.currentFolderLabel,
            onNameChange = viewModel::onEntryNameChanged,
            onConfirm = viewModel::submitEntryDialog,
            onDismiss = viewModel::dismissEntryDialog
        )
    }

    uiState.deleteRequest?.let { request ->
        DeleteEntryDialog(
            request = request,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete
        )
    }

    uiState.infoEntry?.let { entry ->
        EntryInfoDialog(
            entry = entry,
            projectPath = uiState.project?.path,
            onDismiss = viewModel::dismissInfo
        )
    }

    val project = uiState.project
    if (showProjectInfo && project != null) {
        ProjectInfoDialog(project = project, onDismiss = { showProjectInfo = false })
    }
}

@Composable
private fun DirectoryContent(
    state: FileExplorerUiState,
    onEntryClick: (ProjectFileEntry) -> Unit,
    onRename: (ProjectFileEntry) -> Unit,
    onInfo: (ProjectFileEntry) -> Unit,
    onDelete: (ProjectFileEntry) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        when {
            state.isLoading && state.entries.isEmpty() -> CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = MobileIdeSpacing.xxxl)
                    .size(24.dp),
                strokeWidth = 2.dp
            )

            state.error != null -> ExplorerErrorState(
                message = stringResource(projectFileErrorMessage(state.error)),
                detail = state.errorDetail,
                onRetry = onRetry
            )

            state.entries.isEmpty() -> EmptyState(
                iconRes = R.drawable.ic_folder,
                title = stringResource(R.string.explorer_empty_title),
                description = stringResource(R.string.explorer_empty_description)
            )

            else -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .widthIn(max = MobileIdeDimens.contentMaxWidth)
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                        contentPadding = PaddingValues(bottom = MobileIdeSpacing.xxl)
                    ) {
                        itemsIndexed(
                            items = state.entries,
                            key = { _, entry -> entry.path }
                        ) { index, entry ->
                            FileEntryRow(
                                entry = entry,
                                onClick = { onEntryClick(entry) },
                                onRename = { onRename(entry) },
                                onInfo = { onInfo(entry) },
                                onDelete = { onDelete(entry) }
                            )
                            if (index < state.entries.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 53.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                    if (state.isLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                                .fillMaxWidth()
                                .height(2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchContent(
    state: FileExplorerUiState,
    onFolder: (ProjectFileEntry) -> Unit,
    onFile: (ProjectFileEntry) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        when {
            state.searchError != null -> ExplorerErrorState(
                message = stringResource(projectFileErrorMessage(state.searchError)),
                detail = state.searchErrorDetail,
                onRetry = onRetry
            )

            state.searchQuery.isBlank() -> EmptyState(
                iconRes = R.drawable.ic_search,
                title = stringResource(R.string.explorer_search_prompt_title),
                description = stringResource(R.string.explorer_search_prompt_description)
            )

            state.isSearching && state.searchResults.isEmpty() -> Column(
                modifier = Modifier.padding(top = MobileIdeSpacing.xxxl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(MobileIdeSpacing.md))
                Text(
                    text = stringResource(R.string.explorer_search_scanning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.searchResults.isEmpty() -> EmptyState(
                iconRes = R.drawable.ic_search,
                title = stringResource(R.string.explorer_search_empty_title),
                description = stringResource(R.string.explorer_search_empty_description)
            )

            else -> Column(
                modifier = Modifier
                    .widthIn(max = MobileIdeDimens.contentMaxWidth)
                    .fillMaxWidth()
            ) {
                if (state.isSearching) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                    )
                }
                Text(
                    text = pluralStringResource(
                        R.plurals.explorer_search_results,
                        state.searchResults.size,
                        state.searchResults.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = MobileIdeDimens.screenHorizontalPadding,
                        top = MobileIdeSpacing.sm,
                        bottom = MobileIdeSpacing.xs
                    )
                )
                LazyColumn(
                    contentPadding = PaddingValues(bottom = MobileIdeSpacing.xxl)
                ) {
                    items(items = state.searchResults, key = { it.path }) { entry ->
                        SearchResultRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) onFolder(entry) else onFile(entry)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplorerErrorState(
    message: String,
    detail: String?,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp, vertical = MobileIdeSpacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        detail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(MobileIdeSpacing.xs))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(MobileIdeSpacing.sm))
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.workspace_retry))
        }
    }
}

@Composable
private fun StorageGate(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_folder_open),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.height(MobileIdeSpacing.md))
        Text(
            text = stringResource(R.string.storage_gate_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(MobileIdeSpacing.xs))
        Text(
            text = stringResource(R.string.storage_gate_message),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(MobileIdeSpacing.lg))
        Button(onClick = onGrant) {
            Text(stringResource(R.string.storage_permission_grant))
        }
    }
}
