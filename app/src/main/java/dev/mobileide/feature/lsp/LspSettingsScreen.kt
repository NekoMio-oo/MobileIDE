package dev.mobileide.feature.lsp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobileide.R
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.domain.lsp.LspInstallProgress
import dev.mobileide.domain.lsp.LspInstallStage
import dev.mobileide.domain.lsp.LspLogEntry
import dev.mobileide.domain.lsp.LspLogLevel
import dev.mobileide.domain.lsp.LspServerInfo
import dev.mobileide.domain.lsp.LspServerState
import dev.mobileide.domain.lsp.LspSnapshot
import kotlinx.coroutines.delay

@Composable
fun LspSettingsScreen(
    onBack: () -> Unit,
    onOpenRuntimes: () -> Unit,
    viewModel: LspSettingsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val logServerId by viewModel.logServerId.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var installTarget by remember { mutableStateOf<LspServerInfo?>(null) }
    var removeTarget by remember { mutableStateOf<LspServerInfo?>(null) }
    var argsTarget by remember { mutableStateOf<LspServerInfo?>(null) }

    LaunchedEffect(logServerId) {
        while (logServerId != null) {
            viewModel.refreshLogs()
            delay(900L)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LspTopBar(
            onBack = onBack,
            onRefresh = viewModel::refresh,
            onStopAll = viewModel::stopAll,
            anyRunning = state.servers.any { it.sessionCount > 0 }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = MobileIdeSpacing.lg,
                end = MobileIdeSpacing.lg,
                top = MobileIdeSpacing.sm,
                bottom = MobileIdeSpacing.xxxl
            ),
            verticalArrangement = Arrangement.spacedBy(MobileIdeSpacing.md)
        ) {
            item { GlobalCard(state, viewModel) }

            if (!state.linuxInstalled) {
                item { LinuxNotice(onOpenRuntimes) }
            }

            state.error?.let { error ->
                item {
                    NoticeCard(
                        text = error,
                        isError = true,
                        onDismiss = viewModel::dismissMessage
                    )
                }
            }
            message?.let { text ->
                item {
                    NoticeCard(text = text, isError = false, onDismiss = viewModel::dismissMessage)
                }
            }

            items(items = state.servers, key = { it.id }) { server ->
                ServerCard(
                    server = server,
                    progress = state.install?.takeIf { it.serverId == server.id },
                    linuxInstalled = state.linuxInstalled,
                    onInstall = { installTarget = server },
                    onRemove = { removeTarget = server },
                    onRestart = { viewModel.restart(server.id) },
                    onToggle = { enabled -> viewModel.setEnabled(server.id, enabled) },
                    onLogs = { viewModel.openLogs(server.id) },
                    onArgs = { argsTarget = server },
                    onCancelInstall = viewModel::cancelInstall
                )
            }

            if (state.servers.isEmpty() && !state.isLoading) {
                item {
                    Text(
                        text = stringResource(R.string.lsp_no_servers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    installTarget?.let { server ->
        AlertDialog(
            onDismissRequest = { installTarget = null },
            title = { Text(stringResource(R.string.lsp_install_title, server.displayName)) },
            text = {
                Text(stringResource(R.string.lsp_install_message, server.sizeMb))
            },
            confirmButton = {
                TextButton(onClick = {
                    installTarget = null
                    viewModel.install(server.id)
                }) { Text(stringResource(R.string.lsp_install)) }
            },
            dismissButton = {
                TextButton(onClick = { installTarget = null }) {
                    Text(stringResource(R.string.lsp_cancel))
                }
            }
        )
    }

    removeTarget?.let { server ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.lsp_remove_title, server.displayName)) },
            text = { Text(stringResource(R.string.lsp_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    viewModel.uninstall(server.id)
                }) { Text(stringResource(R.string.lsp_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.lsp_cancel))
                }
            }
        )
    }

    argsTarget?.let { server ->
        ArgsDialog(
            server = server,
            onDismiss = { argsTarget = null },
            onSave = { text ->
                viewModel.setExtraArgs(server.id, text)
                argsTarget = null
            }
        )
    }

    logServerId?.let { serverId ->
        val server = state.servers.firstOrNull { it.id == serverId }
        LogsDialog(
            title = server?.displayName ?: serverId,
            logs = logs,
            onClear = { viewModel.clearLogs(serverId) },
            onDismiss = viewModel::closeLogs
        )
    }
}

@Composable
private fun LspTopBar(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onStopAll: () -> Unit,
    anyRunning: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MobileIdeSpacing.xs, end = MobileIdeSpacing.sm, top = 4.dp),
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
        Text(
            text = stringResource(R.string.lsp_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = MobileIdeSpacing.sm)
        )
        TextButton(onClick = onStopAll, enabled = anyRunning) {
            Text(stringResource(R.string.lsp_stop_all))
        }
        IconButton(onClick = onRefresh) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = stringResource(R.string.lsp_refresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun GlobalCard(state: LspSnapshot, viewModel: LspSettingsViewModel) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.lg)) {
            ToggleRow(
                title = stringResource(R.string.lsp_enabled),
                description = stringResource(R.string.lsp_enabled_description),
                checked = state.settings.enabled,
                onCheckedChange = viewModel::setGlobalEnabled
            )
            Spacer(Modifier.height(MobileIdeSpacing.sm))
            ToggleRow(
                title = stringResource(R.string.lsp_autostart),
                description = null,
                checked = state.settings.autoStart,
                enabled = state.settings.enabled,
                onCheckedChange = viewModel::setAutoStart
            )
            Spacer(Modifier.height(MobileIdeSpacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.lsp_max_sessions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Stepper(
                    value = state.settings.maxSessions,
                    onChange = viewModel::setMaxSessions,
                    enabled = state.settings.enabled
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit, enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(value - 1) }, enabled = enabled && value > 1) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        IconButton(onClick = { onChange(value + 1) }, enabled = enabled && value < 3) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun LinuxNotice(onOpenRuntimes: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.lg)) {
            Text(
                text = stringResource(R.string.lsp_linux_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(MobileIdeSpacing.sm))
            TextButton(onClick = onOpenRuntimes) {
                Text(stringResource(R.string.lsp_open_runtimes))
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String, isError: Boolean, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(MobileIdeSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.lsp_dismiss))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerCard(
    server: LspServerInfo,
    progress: LspInstallProgress?,
    linuxInstalled: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onRestart: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onLogs: () -> Unit,
    onArgs: () -> Unit,
    onCancelInstall: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = server.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = server.languages.joinToString(" · ") { it.displayName },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StateChip(server.state)
            }

            if (server.state == LspServerState.UNSUPPORTED) {
                Spacer(Modifier.height(MobileIdeSpacing.sm))
                Text(
                    text = server.stateDetail.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            server.version?.let { version ->
                Spacer(Modifier.height(MobileIdeSpacing.sm))
                Text(
                    text = stringResource(R.string.lsp_version, version),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (server.installed && server.sizeMb > 0) {
                Text(
                    text = stringResource(R.string.lsp_size, server.sizeMb) +
                        if (server.sessionCount > 0) {
                            " · " + pluralStringResource(
                                R.plurals.lsp_running_count,
                                server.sessionCount,
                                server.sessionCount
                            )
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!server.installed && server.stateDetail != null) {
                Text(
                    text = server.stateDetail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            server.lastError?.let { error ->
                Spacer(Modifier.height(MobileIdeSpacing.xs))
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (progress != null) {
                Spacer(Modifier.height(MobileIdeSpacing.md))
                InstallProgress(progress, onCancelInstall)
            }

            Spacer(Modifier.height(MobileIdeSpacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.lsp_enabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onToggle,
                    enabled = server.installed
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MobileIdeSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(MobileIdeSpacing.xs)
            ) {
                if (!server.installed) {
                    Button(onClick = onInstall, enabled = linuxInstalled && progress == null) {
                        Text(stringResource(R.string.lsp_install))
                    }
                } else {
                    TextButton(onClick = onRestart, enabled = progress == null) {
                        Text(stringResource(R.string.lsp_restart))
                    }
                    if (server.installKind == dev.mobileide.domain.lsp.LspInstallKind.ARCHIVE) {
                        TextButton(onClick = onRemove, enabled = progress == null) {
                            Text(stringResource(R.string.lsp_remove))
                        }
                    }
                    TextButton(onClick = onLogs) {
                        Text(stringResource(R.string.lsp_logs))
                    }
                    TextButton(onClick = onArgs) {
                        Text(stringResource(R.string.lsp_extra_args))
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallProgress(progress: LspInstallProgress, onCancel: () -> Unit) {
    val stage = when (progress.stage) {
        LspInstallStage.DOWNLOADING -> stringResource(R.string.lsp_progress_downloading)
        LspInstallStage.VERIFYING -> stringResource(R.string.lsp_progress_verifying)
        LspInstallStage.EXTRACTING -> stringResource(R.string.lsp_progress_extracting)
        LspInstallStage.INSTALLING -> stringResource(R.string.lsp_progress_installing)
        LspInstallStage.DONE -> stringResource(R.string.lsp_state_installing)
        LspInstallStage.FAILED -> stringResource(R.string.lsp_state_crashed)
    }
    Column {
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append(stage)
                    progress.message?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.lsp_cancel))
            }
        }
    }
}

@Composable
private fun StateChip(state: LspServerState) {
    val (label, colors) = when (state) {
        LspServerState.READY -> stringResource(R.string.lsp_state_running) to
            MaterialTheme.colorScheme.primaryContainer

        LspServerState.STARTING, LspServerState.INSTALLING ->
            stringResource(R.string.lsp_state_starting) to MaterialTheme.colorScheme.tertiaryContainer

        LspServerState.CRASHED -> stringResource(R.string.lsp_state_crashed) to
            MaterialTheme.colorScheme.errorContainer

        LspServerState.STOPPED -> stringResource(R.string.lsp_state_stopped) to
            MaterialTheme.colorScheme.secondaryContainer

        LspServerState.UNSUPPORTED -> stringResource(R.string.lsp_state_unsupported) to
            MaterialTheme.colorScheme.surfaceVariant

        LspServerState.NOT_INSTALLED -> stringResource(R.string.lsp_not_installed) to
            MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(shape = CircleShape, color = colors) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ArgsDialog(
    server: LspServerInfo,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by rememberSaveable(server.id) {
        mutableStateOf(server.extraArgs.joinToString("\n"))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lsp_extra_args)) },
        text = {
            Column {
                Text(
                    text = server.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(MobileIdeSpacing.sm))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text(stringResource(R.string.lsp_extra_args_hint)) },
                    singleLine = false,
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text(stringResource(R.string.lsp_save_args))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.lsp_cancel)) }
        }
    )
}

@Composable
private fun LogsDialog(
    title: String,
    logs: List<LspLogEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (logs.isEmpty()) {
                Text(
                    text = stringResource(R.string.lsp_logs_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(logs) { entry ->
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = when (entry.level) {
                                LspLogLevel.ERROR -> MaterialTheme.colorScheme.error
                                LspLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.lsp_dismiss)) }
        },
        dismissButton = {
            TextButton(onClick = onClear) { Text(stringResource(R.string.lsp_clear_logs)) }
        }
    )
}
