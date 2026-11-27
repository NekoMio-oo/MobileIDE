package dev.mobileide.feature.git

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.EmptyState
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.domain.git.GitBranch
import dev.mobileide.feature.git.model.GitBranchesUiState

/** Branches tab: local/remote lists, create, switch, track and safe delete. */
@Composable
internal fun GitBranchesPanel(
    state: GitBranchesUiState,
    onRefresh: () -> Unit,
    onCreate: (name: String, startPoint: String?, track: Boolean) -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var createName by rememberSaveable { mutableStateOf("") }
    var createStart by rememberSaveable { mutableStateOf("") }
    var trackTarget by remember { mutableStateOf<GitBranch?>(null) }
    var trackName by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<GitBranch?>(null) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = MobileIdeDimens.screenHorizontalPadding,
                end = MobileIdeDimens.screenHorizontalPadding,
                top = MobileIdeSpacing.sm,
                bottom = MobileIdeSpacing.xxxl
            ),
            verticalArrangement = Arrangement.spacedBy(MobileIdeSpacing.sm)
        ) {
            item(key = "local-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.git_branches_local),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { showCreateDialog = true }) {
                        Text(stringResource(R.string.git_branch_new))
                    }
                }
            }

            if (state.isLoading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MobileIdeSpacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            state.error?.let { error ->
                item(key = "error") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(MobileIdeSpacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = error.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                error.detail?.let { detail ->
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            TextButton(onClick = onRefresh) {
                                Text(
                                    text = stringResource(R.string.git_retry),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            if (!state.isLoading && state.error == null && state.local.isEmpty()) {
                item(key = "local-empty") {
                    EmptyState(
                        iconRes = R.drawable.ic_git,
                        title = stringResource(R.string.git_branch_empty_title),
                        description = stringResource(R.string.git_branch_empty_description)
                    )
                }
            }

            items(items = state.local, key = { "local:${it.name}" }) { branch ->
                LocalBranchRow(
                    branch = branch,
                    onSwitch = { onSwitch(branch.name) },
                    onDelete = { deleteTarget = branch }
                )
            }

            item(key = "remote-header") {
                Spacer(Modifier.height(MobileIdeSpacing.sm))
                Text(
                    text = stringResource(R.string.git_branches_remote),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (state.remote.isEmpty() && !state.isLoading && state.error == null) {
                item(key = "remote-empty") {
                    Text(
                        text = stringResource(R.string.git_branch_remote_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(items = state.remote, key = { "remote:${it.name}" }) { branch ->
                RemoteBranchRow(
                    branch = branch,
                    onCreate = {
                        trackTarget = branch
                        trackName = branch.name.substringAfter('/', branch.name)
                    }
                )
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(stringResource(R.string.git_branch_create_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = createName,
                        onValueChange = { createName = it },
                        label = { Text(stringResource(R.string.git_branch_name_label)) },
                        singleLine = true
                    )
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    OutlinedTextField(
                        value = createStart,
                        onValueChange = { createStart = it },
                        label = { Text(stringResource(R.string.git_branch_start_label)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreate(
                            createName.trim(),
                            createStart.trim().takeIf { it.isNotEmpty() },
                            false
                        )
                        showCreateDialog = false
                        createName = ""
                        createStart = ""
                    },
                    enabled = createName.isNotBlank()
                ) {
                    Text(stringResource(R.string.git_branch_create_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }

    trackTarget?.let { remote ->
        AlertDialog(
            onDismissRequest = { trackTarget = null },
            title = { Text(stringResource(R.string.git_branch_track_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.git_branch_track_message, remote.name))
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    OutlinedTextField(
                        value = trackName,
                        onValueChange = { trackName = it },
                        label = { Text(stringResource(R.string.git_branch_track_name_label)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCreate(trackName.trim(), remote.name, true)
                        trackTarget = null
                        trackName = ""
                    },
                    enabled = trackName.isNotBlank()
                ) {
                    Text(stringResource(R.string.git_branch_create_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { trackTarget = null }) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }

    deleteTarget?.let { branch ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.git_branch_delete_title)) },
            text = { Text(stringResource(R.string.git_branch_delete_message, branch.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(branch.name)
                        deleteTarget = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.git_branch_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }
}

@Composable
private fun LocalBranchRow(
    branch: GitBranch,
    onSwitch: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (branch.isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = CircleShape
                        )
                )
                Spacer(Modifier.width(MobileIdeSpacing.sm))
                Text(
                    text = branch.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (branch.isCurrent) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.git_branch_current),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            val upstreamLabel = branch.upstream
                ?.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.git_branch_upstream, it) }
            val syncLabel = when {
                branch.upstreamGone -> stringResource(R.string.git_branch_gone)
                branch.ahead > 0 || branch.behind > 0 -> stringResource(
                    R.string.git_branch_ahead_behind,
                    branch.ahead,
                    branch.behind
                )

                else -> null
            }
            val meta = listOfNotNull(upstreamLabel, syncLabel).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(MobileIdeSpacing.xs))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!branch.isCurrent) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onSwitch) {
                        Text(stringResource(R.string.git_branch_switch))
                    }
                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(R.string.git_branch_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteBranchRow(
    branch: GitBranch,
    onCreate: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(start = MobileIdeSpacing.md, end = MobileIdeSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_git),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(MobileIdeSpacing.sm))
            Text(
                text = branch.name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCreate) {
                Text(
                    text = stringResource(R.string.git_branch_track),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}
