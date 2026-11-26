package dev.mobileide.feature.git

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    var showInitDialog by rememberSaveable { mutableStateOf(false) }
    var showInstallDialog by rememberSaveable { mutableStateOf(false) }
    var branchInput by rememberSaveable { mutableStateOf("main") }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onScreenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        GitProjectTopBar(
            title = state.projectName.ifBlank { stringResource(R.string.git_title) },
            onBack = onBack,
            onRefresh = viewModel::refresh,
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

            else -> GitOverviewContent(state)
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

@Composable
private fun GitOverviewContent(state: GitSnapshot) {
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
            ChangesCard(state.status)
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
private fun ChangesCard(status: GitRepoStatus?) {
    val current = status ?: GitRepoStatus()
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

            Spacer(Modifier.height(MobileIdeSpacing.sm))

            if (current.changes.isEmpty()) {
                Text(
                    text = stringResource(R.string.git_clean),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.git_change_summary,
                        current.staged.size,
                        current.unstaged.size,
                        current.untracked.size,
                        current.conflicted.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(MobileIdeSpacing.sm))

                current.changes.take(MAX_VISIBLE_CHANGES).forEach { change ->
                    GitChangeRow(change)
                }
                if (current.changes.size > MAX_VISIBLE_CHANGES) {
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    Text(
                        text = stringResource(
                            R.string.git_more_changes,
                            current.changes.size - MAX_VISIBLE_CHANGES
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
}

@Composable
private fun GitChangeRow(change: GitFileChange) {
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
                .background(changeColor(change.displayChange))
        )
        Spacer(Modifier.width(MobileIdeSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
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
                text = changeKindLabel(change.displayChange),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

@Composable
private fun changeKindLabel(kind: GitChangeKind): String = when (kind) {
    GitChangeKind.MODIFIED -> stringResource(R.string.git_change_modified)
    GitChangeKind.ADDED -> stringResource(R.string.git_change_added)
    GitChangeKind.DELETED -> stringResource(R.string.git_change_deleted)
    GitChangeKind.RENAMED -> stringResource(R.string.git_change_renamed)
    GitChangeKind.COPIED -> stringResource(R.string.git_change_copied)
    GitChangeKind.TYPE_CHANGED -> stringResource(R.string.git_change_type_changed)
    GitChangeKind.UNTRACKED -> stringResource(R.string.git_change_untracked)
    GitChangeKind.CONFLICTED -> stringResource(R.string.git_change_conflicted)
    GitChangeKind.UNKNOWN -> stringResource(R.string.git_change_unknown)
}

@Composable
private fun changeColor(kind: GitChangeKind): Color = when (kind) {
    GitChangeKind.CONFLICTED,
    GitChangeKind.DELETED -> MaterialTheme.colorScheme.error

    GitChangeKind.UNTRACKED,
    GitChangeKind.ADDED -> MaterialTheme.colorScheme.tertiary

    GitChangeKind.RENAMED,
    GitChangeKind.COPIED -> MaterialTheme.colorScheme.secondary

    else -> MaterialTheme.colorScheme.primary
}

private const val MAX_VISIBLE_CHANGES = 50
