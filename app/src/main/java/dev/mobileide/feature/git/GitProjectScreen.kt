package dev.mobileide.feature.git

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobileide.R
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.domain.git.GitAvailability
import dev.mobileide.domain.git.GitChangeKind
import dev.mobileide.domain.git.GitError
import dev.mobileide.domain.git.GitFileChange
import dev.mobileide.domain.git.GitOperation
import dev.mobileide.domain.git.GitRepoStatus
import dev.mobileide.domain.git.GitSnapshot

/**
 * Git panel for one project: overview, real repository status and the entry
 * points to install Git or initialize the folder. Later phases add change
 * actions, diffs, commits, branches and remotes to the same screen.
 */
@Composable
fun GitProjectScreen(
    onBack: () -> Unit,
    onOpenRuntimes: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GitViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val diffState by viewModel.diffState.collectAsStateWithLifecycle()
    val historyState by viewModel.history.collectAsStateWithLifecycle()
    val branchesState by viewModel.branches.collectAsStateWithLifecycle()
    val commitDetailState by viewModel.commitDetail.collectAsStateWithLifecycle()
    var showInitDialog by rememberSaveable { mutableStateOf(false) }
    var showInstallDialog by rememberSaveable { mutableStateOf(false) }
    var branchInput by rememberSaveable { mutableStateOf("main") }
    var discardTarget by remember { mutableStateOf<GitFileChange?>(null) }
    var selectedTabOrdinal by rememberSaveable { mutableStateOf(GitPanelTab.OVERVIEW.ordinal) }
    var commitMessage by rememberSaveable { mutableStateOf("") }
    val selectedTab = GitPanelTab.entries[selectedTabOrdinal]

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(selectedTabOrdinal, state.availability) {
        if (state.availability == GitAvailability.READY) {
            when (selectedTab) {
                GitPanelTab.HISTORY -> viewModel.loadHistory()
                GitPanelTab.BRANCHES -> viewModel.loadBranches()
                else -> Unit
            }
        }
    }

    BackHandler(enabled = diffState != null || commitDetailState != null) {
        if (diffState != null) {
            viewModel.closeDiff()
        } else {
            viewModel.closeCommitDetail()
        }
    }

    val currentDiff = diffState
    if (currentDiff != null) {
        GitDiffScreen(
            state = currentDiff,
            onBack = viewModel::closeDiff,
            onRetry = viewModel::retryDiff,
            modifier = modifier
        )
        return
    }

    val currentCommitDetail = commitDetailState
    if (currentCommitDetail != null) {
        GitCommitDetailScreen(
            state = currentCommitDetail,
            onBack = viewModel::closeCommitDetail,
            onRetry = viewModel::retryCommitDetail,
            onOpenParent = viewModel::openParentCommit,
            onOpenFile = viewModel::openCommitFileDiff,
            modifier = modifier
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        GitProjectTopBar(
            title = state.projectName.ifBlank { stringResource(R.string.git_title) },
            onBack = onBack,
            onRefresh = {
                viewModel.refresh()
                if (state.availability == GitAvailability.READY) {
                    when (selectedTab) {
                        GitPanelTab.HISTORY -> viewModel.loadHistory(force = true)
                        GitPanelTab.BRANCHES -> viewModel.loadBranches(force = true)
                        else -> Unit
                    }
                }
            },
            refreshEnabled = !state.isBusy
        )

        state.operation?.let { operation ->
            OperationBanner(
                operation = operation,
                onCancel = viewModel::cancelOperation
            )
        }

        if (state.availability != GitAvailability.ERROR) {
            state.error?.let { error ->
                ErrorBanner(error = error, onDismiss = viewModel::dismissError)
            }
        }
        state.message?.let { message ->
            MessageBanner(message = message, onDismiss = viewModel::dismissMessage)
        }

        when {
            state.isLoading -> LoadingBox()

            state.availability == GitAvailability.LINUX_MISSING -> GitGate(
                title = stringResource(R.string.git_linux_required_title),
                message = stringResource(R.string.git_linux_required_message),
                actionLabel = stringResource(R.string.git_open_runtimes),
                onAction = onOpenRuntimes
            )

            state.availability == GitAvailability.GIT_MISSING -> GitGate(
                title = stringResource(R.string.git_missing_title),
                message = stringResource(R.string.git_missing_message),
                actionLabel = stringResource(R.string.git_install),
                onAction = { showInstallDialog = true },
                secondaryLabel = stringResource(R.string.git_refresh),
                onSecondary = viewModel::refresh
            )

            state.availability == GitAvailability.NOT_A_REPO -> GitGate(
                title = stringResource(R.string.git_not_repo_title),
                message = stringResource(R.string.git_not_repo_message),
                actionLabel = stringResource(R.string.git_init),
                onAction = { showInitDialog = true },
                secondaryLabel = stringResource(R.string.git_refresh),
                onSecondary = viewModel::refresh
            )

            state.availability == GitAvailability.ERROR -> GitErrorContent(
                error = state.error,
                onRetry = viewModel::refresh
            )

            else -> {
                GitPanelTabs(
                    selectedTab = selectedTab,
                    onSelect = { tab -> selectedTabOrdinal = tab.ordinal }
                )
                when (selectedTab) {
                    GitPanelTab.OVERVIEW -> GitOverviewTab(state)

                    GitPanelTab.CHANGES -> GitChangesTab(
                        state = state,
                        busy = state.isBusy,
                        commitMessage = commitMessage,
                        onCommitMessageChange = { commitMessage = it },
                        onCommit = { viewModel.commit(commitMessage) },
                        onStage = viewModel::stage,
                        onUnstage = viewModel::unstage,
                        onStageAll = viewModel::stageAll,
                        onUnstageAll = viewModel::unstageAll,
                        onDiscard = { discardTarget = it },
                        onOpenDiff = viewModel::openDiff
                    )

                    GitPanelTab.HISTORY -> GitHistoryPanel(
                        state = historyState,
                        onQueryChange = viewModel::onHistoryQueryChanged,
                        onRetry = { viewModel.loadHistory(force = true) },
                        onOpenCommit = viewModel::openCommit
                    )

                    GitPanelTab.BRANCHES -> GitBranchesPanel(
                        state = branchesState,
                        onRefresh = { viewModel.loadBranches(force = true) },
                        onCreate = viewModel::createBranch,
                        onSwitch = viewModel::switchBranch,
                        onDelete = viewModel::deleteBranch
                    )
                }
            }
        }
    }

    if (showInitDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.isBusy) showInitDialog = false },
            title = { Text(stringResource(R.string.git_init_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.git_init_message))
                    Spacer(Modifier.height(MobileIdeSpacing.md))
                    OutlinedTextField(
                        value = branchInput,
                        onValueChange = { branchInput = it },
                        label = { Text(stringResource(R.string.git_init_branch_label)) },
                        singleLine = true,
                        enabled = !state.isBusy
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showInitDialog = false
                        viewModel.initRepository(branchInput)
                    },
                    enabled = !state.isBusy
                ) {
                    Text(stringResource(R.string.git_init_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showInitDialog = false },
                    enabled = !state.isBusy
                ) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }

    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { if (!state.isBusy) showInstallDialog = false },
            title = { Text(stringResource(R.string.git_install_title)) },
            text = { Text(stringResource(R.string.git_install_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showInstallDialog = false
                        viewModel.installGit()
                    },
                    enabled = !state.isBusy
                ) {
                    Text(stringResource(R.string.git_install))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showInstallDialog = false },
                    enabled = !state.isBusy
                ) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }

    discardTarget?.let { change ->
        val untracked = change.untracked
        AlertDialog(
            onDismissRequest = { discardTarget = null },
            title = {
                Text(
                    stringResource(
                        if (untracked) {
                            R.string.git_discard_untracked_title
                        } else {
                            R.string.git_discard_title
                        }
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (untracked) {
                            R.string.git_discard_untracked_message
                        } else {
                            R.string.git_discard_message
                        },
                        change.path
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        discardTarget = null
                        viewModel.discard(change)
                    }
                ) {
                    Text(
                        text = stringResource(
                            if (untracked) {
                                R.string.git_delete_confirm
                            } else {
                                R.string.git_discard_confirm
                            }
                        ),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { discardTarget = null }) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }
}

@Composable
private fun GitProjectTopBar(
    title: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth()
                .padding(
                    start = MobileIdeSpacing.xs,
                    end = MobileIdeSpacing.xs,
                    top = MobileIdeSpacing.xs,
                    bottom = MobileIdeSpacing.xs
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MobileIdeSpacing.sm)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.git_title),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRefresh, enabled = refreshEnabled) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = stringResource(R.string.git_refresh),
                    tint = if (refreshEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun GitGate(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = MobileIdeDimens.screenHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_git),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(MobileIdeSpacing.lg))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(MobileIdeSpacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(MobileIdeSpacing.lg))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
            if (secondaryLabel != null && onSecondary != null) {
                TextButton(onClick = onSecondary) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}

@Composable
private fun GitErrorContent(
    error: GitError?,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = MobileIdeDimens.screenHorizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_error),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(MobileIdeSpacing.lg))
            Text(
                text = stringResource(R.string.git_error_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(MobileIdeSpacing.xs))
            Text(
                text = error?.message ?: stringResource(R.string.git_error_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            error?.detail?.let { detail ->
                Spacer(Modifier.height(MobileIdeSpacing.md))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    SelectionContainer {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(MobileIdeSpacing.md),
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(MobileIdeSpacing.lg))
            Button(onClick = onRetry) {
                Text(stringResource(R.string.git_retry))
            }
        }
    }
}

@Composable
private fun OperationBanner(
    operation: GitOperation,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth()
                .padding(
                    horizontal = MobileIdeDimens.screenHorizontalPadding,
                    vertical = MobileIdeSpacing.xs
                ),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(MobileIdeSpacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(MobileIdeSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = operation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    operation.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (operation.cancellable) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.git_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    error: GitError,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth()
                .padding(
                    horizontal = MobileIdeDimens.screenHorizontalPadding,
                    vertical = MobileIdeSpacing.xs
                ),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Row(
                modifier = Modifier.padding(MobileIdeSpacing.md),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_error),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(MobileIdeSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    error.detail?.let { detail ->
                        Spacer(Modifier.height(MobileIdeSpacing.xs))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.git_dismiss),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth()
                .padding(
                    horizontal = MobileIdeDimens.screenHorizontalPadding,
                    vertical = MobileIdeSpacing.xs
                ),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(
                modifier = Modifier.padding(MobileIdeSpacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.git_dismiss),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

private enum class GitPanelTab(@StringRes val labelRes: Int) {
    OVERVIEW(R.string.git_tab_overview),
    CHANGES(R.string.git_tab_changes),
    HISTORY(R.string.git_tab_history),
    BRANCHES(R.string.git_tab_branches)
}

@Composable
private fun GitPanelTabs(
    selectedTab: GitPanelTab,
    onSelect: (GitPanelTab) -> Unit
) {
    ScrollableTabRow(
        selectedTabIndex = selectedTab.ordinal,
        edgePadding = 0.dp,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        GitPanelTab.entries.forEach { tab ->
            Tab(
                selected = tab == selectedTab,
                onClick = { onSelect(tab) },
                text = {
                    Text(
                        text = stringResource(tab.labelRes),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            )
        }
    }
}

@Composable
private fun GitOverviewTab(state: GitSnapshot) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = MobileIdeDimens.screenHorizontalPadding,
                    vertical = MobileIdeSpacing.md
                ),
            verticalArrangement = Arrangement.spacedBy(MobileIdeSpacing.md)
        ) {
            RepositoryCard(state)
        }
    }
}

@Composable
private fun GitChangesTab(
    state: GitSnapshot,
    busy: Boolean,
    commitMessage: String,
    onCommitMessageChange: (String) -> Unit,
    onCommit: () -> Unit,
    onStage: (GitFileChange) -> Unit,
    onUnstage: (GitFileChange) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onDiscard: (GitFileChange) -> Unit,
    onOpenDiff: (GitFileChange, Boolean) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = MobileIdeDimens.screenHorizontalPadding,
                    vertical = MobileIdeSpacing.md
                ),
            verticalArrangement = Arrangement.spacedBy(MobileIdeSpacing.md)
        ) {
            ChangesCard(
                status = state.status,
                busy = busy,
                onStage = onStage,
                onUnstage = onUnstage,
                onStageAll = onStageAll,
                onUnstageAll = onUnstageAll,
                onDiscard = onDiscard,
                onOpenDiff = onOpenDiff
            )
            CommitCard(
                state = state,
                busy = busy,
                message = commitMessage,
                onMessageChange = onCommitMessageChange,
                onCommit = onCommit
            )
        }
    }
}

@Composable
private fun CommitCard(
    state: GitSnapshot,
    busy: Boolean,
    message: String,
    onMessageChange: (String) -> Unit,
    onCommit: () -> Unit
) {
    val staged = state.status?.staged.orEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.git_commit_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.git_commit_staged,
                        staged.size,
                        staged.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(MobileIdeSpacing.sm))

            if (staged.isEmpty()) {
                Text(
                    text = stringResource(R.string.git_commit_no_staged),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedTextField(
                    value = message,
                    onValueChange = onMessageChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.git_commit_message_label)) },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !busy
                )
                Spacer(Modifier.height(MobileIdeSpacing.sm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onCommit,
                        enabled = !busy && message.isNotBlank()
                    ) {
                        Text(stringResource(R.string.git_commit_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun RepositoryCard(state: GitSnapshot) {
    val repo = state.repo
    val status = state.status ?: GitRepoStatus()
    val branch = when {
        repo?.detached == true -> stringResource(R.string.git_detached)
        !repo?.branch.isNullOrBlank() -> repo?.branch.orEmpty()
        else -> stringResource(R.string.git_no_commits)
    }
    val head = repo?.headCommit ?: stringResource(R.string.git_no_commits)
    val workingTree = if (status.isClean) {
        stringResource(R.string.git_clean)
    } else {
        pluralStringResource(
            R.plurals.git_changes,
            status.changes.size,
            status.changes.size
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_git),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(MobileIdeSpacing.sm))
                Text(
                    text = stringResource(R.string.git_repository_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(MobileIdeSpacing.md))
            LabelValueRow(stringResource(R.string.git_branch), branch)
            LabelValueRow(stringResource(R.string.git_head), head, monospace = true)
            repo?.let { currentRepo ->
                val upstream = currentRepo.upstream
                if (!upstream.isNullOrBlank()) {
                    LabelValueRow(stringResource(R.string.git_upstream), upstream, monospace = true)
                    LabelValueRow(
                        stringResource(R.string.git_ahead_behind),
                        stringResource(
                            R.string.git_ahead_behind_value,
                            currentRepo.ahead,
                            currentRepo.behind
                        )
                    )
                }
            }
            LabelValueRow(stringResource(R.string.git_working_tree), workingTree)

            Spacer(Modifier.height(MobileIdeSpacing.sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(MobileIdeSpacing.sm))

            LabelValueRow(
                stringResource(R.string.git_repository_root),
                state.projectPath,
                monospace = true
            )
            LabelValueRow(
                stringResource(R.string.git_git_dir),
                repo?.gitDirPath ?: stringResource(R.string.git_not_available),
                monospace = true
            )
            LabelValueRow(
                stringResource(R.string.git_git_version),
                state.gitVersion ?: stringResource(R.string.git_not_available),
                monospace = true
            )
        }
    }
}

@Composable
private fun ChangesCard(
    status: GitRepoStatus?,
    busy: Boolean,
    onStage: (GitFileChange) -> Unit,
    onUnstage: (GitFileChange) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onDiscard: (GitFileChange) -> Unit,
    onOpenDiff: (GitFileChange, Boolean) -> Unit
) {
    val current = status ?: GitRepoStatus()
    val hasConflicts = current.conflicted.isNotEmpty()
    val compactPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.git_changes_card_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.git_changes,
                        current.changes.size,
                        current.changes.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (current.changes.isEmpty()) {
                Spacer(Modifier.height(MobileIdeSpacing.sm))
                Text(
                    text = stringResource(R.string.git_clean),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            if (hasConflicts) {
                Spacer(Modifier.height(MobileIdeSpacing.sm))
                Text(
                    text = stringResource(R.string.git_conflict_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            ChangeSection(
                title = stringResource(R.string.git_section_staged),
                changes = current.staged,
                busy = busy,
                primaryLabel = stringResource(R.string.git_unstage),
                onPrimary = onUnstage,
                onOpen = { change -> onOpenDiff(change, true) },
                compactPadding = compactPadding
            )
            ChangeSection(
                title = stringResource(R.string.git_section_unstaged),
                changes = current.unstaged,
                busy = busy,
                primaryLabel = stringResource(R.string.git_stage),
                onPrimary = onStage,
                discardLabel = stringResource(R.string.git_discard),
                onDiscard = onDiscard,
                onOpen = { change -> onOpenDiff(change, false) },
                compactPadding = compactPadding
            )
            ChangeSection(
                title = stringResource(R.string.git_section_untracked),
                changes = current.untracked,
                busy = busy,
                primaryLabel = stringResource(R.string.git_stage),
                onPrimary = onStage,
                discardLabel = stringResource(R.string.git_delete),
                onDiscard = onDiscard,
                onOpen = { change -> onOpenDiff(change, false) },
                compactPadding = compactPadding
            )
            ChangeSection(
                title = stringResource(R.string.git_section_conflicted),
                changes = current.conflicted,
                busy = busy,
                compactPadding = compactPadding
            )

            if (!hasConflicts &&
                (current.staged.isNotEmpty() ||
                    current.unstaged.isNotEmpty() ||
                    current.untracked.isNotEmpty())
            ) {
                Spacer(Modifier.height(MobileIdeSpacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (current.unstaged.isNotEmpty() || current.untracked.isNotEmpty()) {
                        TextButton(
                            onClick = onStageAll,
                            enabled = !busy,
                            contentPadding = compactPadding
                        ) {
                            Text(
                                text = stringResource(R.string.git_stage_all),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    if (current.staged.isNotEmpty()) {
                        TextButton(
                            onClick = onUnstageAll,
                            enabled = !busy,
                            contentPadding = compactPadding
                        ) {
                            Text(
                                text = stringResource(R.string.git_unstage_all),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            if (current.truncated) {
                Spacer(Modifier.height(MobileIdeSpacing.sm))
                Text(
                    text = stringResource(R.string.git_changes_truncated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ChangeSection(
    title: String,
    changes: List<GitFileChange>,
    busy: Boolean,
    primaryLabel: String? = null,
    onPrimary: ((GitFileChange) -> Unit)? = null,
    discardLabel: String? = null,
    onDiscard: ((GitFileChange) -> Unit)? = null,
    onOpen: ((GitFileChange) -> Unit)? = null,
    compactPadding: PaddingValues
) {
    if (changes.isEmpty()) return
    Spacer(Modifier.height(MobileIdeSpacing.md))
    Text(
        text = stringResource(R.string.git_section_count, title, changes.size),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
    changes.take(MAX_VISIBLE_CHANGES).forEach { change ->
        GitChangeRow(
            change = change,
            busy = busy,
            primaryLabel = primaryLabel,
            onPrimary = onPrimary?.let { action -> { action(change) } },
            discardLabel = discardLabel,
            onDiscard = onDiscard?.let { action -> { action(change) } },
            onOpen = onOpen?.let { action -> { action(change) } },
            compactPadding = compactPadding
        )
    }
    if (changes.size > MAX_VISIBLE_CHANGES) {
        Text(
            text = stringResource(
                R.string.git_more_changes,
                changes.size - MAX_VISIBLE_CHANGES
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GitChangeRow(
    change: GitFileChange,
    busy: Boolean,
    primaryLabel: String?,
    onPrimary: (() -> Unit)?,
    discardLabel: String?,
    onDiscard: (() -> Unit)?,
    onOpen: (() -> Unit)?,
    compactPadding: PaddingValues
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MobileIdeSpacing.xs),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(gitChangeColor(change.displayChange))
        )
        Spacer(Modifier.width(MobileIdeSpacing.sm))
        val openModifier = if (onOpen != null) {
            Modifier.clickable(enabled = !busy, onClick = onOpen)
        } else {
            Modifier
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .then(openModifier)
                .padding(vertical = 2.dp)
        ) {
            Text(
                text = changeLabel(change),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = gitChangeKindLabel(change.displayChange),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (primaryLabel != null && onPrimary != null) {
            TextButton(
                onClick = onPrimary,
                enabled = !busy,
                contentPadding = compactPadding
            ) {
                Text(
                    text = primaryLabel,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        if (discardLabel != null && onDiscard != null) {
            TextButton(
                onClick = onDiscard,
                enabled = !busy,
                contentPadding = compactPadding,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    text = discardLabel,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun LabelValueRow(
    label: String,
    value: String,
    monospace: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp)
        )
        Spacer(Modifier.width(MobileIdeSpacing.sm))
        val resolved = if (value.isBlank()) {
            stringResource(R.string.git_not_available)
        } else {
            value
        }
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                text = resolved,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun changeLabel(change: GitFileChange): String =
    if (change.displayChange == GitChangeKind.RENAMED && !change.originalPath.isNullOrBlank()) {
        "${change.originalPath} → ${change.path}"
    } else {
        change.path
    }

private const val MAX_VISIBLE_CHANGES = 50
