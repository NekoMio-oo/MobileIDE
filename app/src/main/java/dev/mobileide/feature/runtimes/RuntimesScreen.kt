package dev.mobileide.feature.runtimes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.DetailTopBar
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.core.ui.formatFileSize
import dev.mobileide.domain.runtime.RuntimeId
import dev.mobileide.domain.runtime.RuntimeInfo
import dev.mobileide.domain.runtime.RuntimeProgress
import dev.mobileide.domain.runtime.RuntimeSnapshot
import dev.mobileide.domain.runtime.RuntimeStage
import dev.mobileide.domain.runtime.RuntimeState

private sealed interface ConfirmAction {
    data object InstallLinux : ConfirmAction
    data object UninstallLinux : ConfirmAction
    data class InstallToolchain(val info: RuntimeInfo) : ConfirmAction
}

@Composable
fun RuntimesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RuntimesViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }
    var showLogs by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        DetailTopBar(title = stringResource(R.string.runtimes_title), onBack = onBack)

        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(top = MobileIdeSpacing.xxxl)
                        .size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = MobileIdeDimens.screenHorizontalPadding,
                    end = MobileIdeDimens.screenHorizontalPadding,
                    bottom = MobileIdeSpacing.xxl
                ),
                verticalArrangement = Arrangement.spacedBy(MobileIdeSpacing.sm)
            ) {
                state.error?.let { message ->
                    item(key = "error") {
                        Notice(message = message, isError = true, onDismiss = viewModel::clearError)
                    }
                }
                state.message?.let { message ->
                    item(key = "message") {
                        Notice(message = message, isError = false, onDismiss = viewModel::clearMessage)
                    }
                }
                if (state.isBusy) {
                    item(key = "busy") {
                        BusyCard(
                            state = state,
                            onViewLog = { showLogs = true },
                            onCancel = viewModel::cancelWork
                        )
                    }
                }

                item(key = "linux-header") {
                    SectionHeader(
                        title = stringResource(R.string.runtimes_linux_section),
                        onRefresh = viewModel::refresh,
                        refreshEnabled = !state.isBusy
                    )
                }
                val linux = state.runtimes.firstOrNull { it.id == RuntimeId.LINUX }
                if (linux == null) {
                    item(key = "linux-missing") {
                        Text(
                            text = stringResource(R.string.runtimes_no_packages),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    item(key = "linux") {
                        LinuxCard(
                            info = linux,
                            busy = state.isBusy,
                            onInstall = { confirm = ConfirmAction.InstallLinux },
                            onUninstall = { confirm = ConfirmAction.UninstallLinux },
                            onCheck = viewModel::verifyLinux
                        )
                    }
                }

                item(key = "languages-header") {
                    SectionHeader(title = stringResource(R.string.runtimes_languages_section))
                }
                val languages = state.runtimes.filter { it.requiresLinux }
                items(items = languages, key = { it.id.name }) { info ->
                    LanguageRow(
                        info = info,
                        linuxInstalled = linux?.state == RuntimeState.INSTALLED,
                        busy = state.isBusy,
                        onInstall = { confirm = ConfirmAction.InstallToolchain(info) },
                        onCheck = { viewModel.checkToolchain(info.id) }
                    )
                }

                item(key = "builtin-header") {
                    SectionHeader(title = stringResource(R.string.runtimes_builtin_section))
                }
                state.runtimes.firstOrNull { it.id == RuntimeId.HTML }?.let { html ->
                    item(key = "html") { BuiltinRow(html) }
                }
            }
        }
    }

    when (val action = confirm) {
        null -> Unit

        ConfirmAction.InstallLinux -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.runtimes_install_linux_title)) },
            text = { Text(stringResource(R.string.runtimes_install_linux_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        viewModel.installLinux()
                    }
                ) { Text(stringResource(R.string.runtimes_install)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(stringResource(R.string.runtimes_cancel))
                }
            }
        )

        ConfirmAction.UninstallLinux -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.runtimes_uninstall_linux_title)) },
            text = { Text(stringResource(R.string.runtimes_uninstall_linux_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        viewModel.uninstallLinux()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.runtimes_uninstall),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(stringResource(R.string.runtimes_cancel))
                }
            }
        )

        is ConfirmAction.InstallToolchain -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    stringResource(
                        R.string.runtimes_install_toolchain_title,
                        action.info.displayName
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.runtimes_install_toolchain_message,
                        action.info.packages.joinToString(", ")
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        viewModel.installToolchain(action.info.id)
                    }
                ) { Text(stringResource(R.string.runtimes_install)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) {
                    Text(stringResource(R.string.runtimes_cancel))
                }
            }
        )
    }

    if (showLogs) {
        LogDialog(lines = state.logs, onDismiss = {
            showLogs = false
            viewModel.clearLogs()
        })
    }
}

@Composable
private fun SectionHeader(
    title: String,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MobileIdeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (onRefresh != null) {
            TextButton(onClick = onRefresh, enabled = refreshEnabled) {
                Text(stringResource(R.string.runtimes_refresh))
            }
        }
    }
}

@Composable
private fun LinuxCard(
    info: RuntimeInfo,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onCheck: () -> Unit
) {
    RuntimeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_folder_open),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(MobileIdeSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = info.runtimeName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StateChip(info.state)
        }

        info.version?.let { version ->
            Spacer(Modifier.height(MobileIdeSpacing.xs))
            Text(
                text = stringResource(R.string.runtimes_version, version),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        info.sizeBytes?.let { size ->
            Text(
                text = stringResource(R.string.runtimes_size, formatFileSize(size)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        info.installPath?.let { path ->
            Text(
                text = stringResource(R.string.runtimes_path, path),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        info.message?.let { message ->
            Spacer(Modifier.height(MobileIdeSpacing.xs))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(MobileIdeSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(MobileIdeSpacing.sm)) {
            if (info.state == RuntimeState.INSTALLED) {
                Button(onClick = onInstall, enabled = !busy) {
                    Text(stringResource(R.string.runtimes_reinstall))
                }
                TextButton(onClick = onUninstall, enabled = !busy) {
                    Text(
                        text = stringResource(R.string.runtimes_uninstall),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                TextButton(onClick = onCheck, enabled = !busy) {
                    Text(stringResource(R.string.runtimes_check))
                }
            } else {
                Button(
                    onClick = onInstall,
                    enabled = !busy && info.canInstall
                ) {
                    Text(stringResource(R.string.runtimes_install))
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(
    info: RuntimeInfo,
    linuxInstalled: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onCheck: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MobileIdeSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_file_code),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(MobileIdeSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = info.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(MobileIdeSpacing.sm))
                    StateChip(info.state)
                }
                Text(
                    text = info.runtimeName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                info.version?.let { version ->
                    Text(
                        text = version,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (info.state != RuntimeState.INSTALLED) {
                    Text(
                        text = info.message ?: if (linuxInstalled) {
                            stringResource(R.string.runtimes_state_not_installed)
                        } else {
                            stringResource(R.string.runtimes_requires_linux)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            when {
                info.state == RuntimeState.INSTALLED -> TextButton(
                    onClick = onCheck,
                    enabled = !busy
                ) { Text(stringResource(R.string.runtimes_check)) }

                info.state == RuntimeState.UNSUPPORTED -> Unit

                info.canInstall && linuxInstalled -> TextButton(
                    onClick = onInstall,
                    enabled = !busy
                ) { Text(stringResource(R.string.runtimes_install)) }
            }
        }
    }
}

@Composable
private fun BuiltinRow(info: RuntimeInfo) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MobileIdeSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_file),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(MobileIdeSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = info.runtimeName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                info.version?.let { version ->
                    Text(
                        text = version,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                info.message?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            StateChip(info.state)
        }
    }
}

@Composable
private fun BusyCard(
    state: RuntimeSnapshot,
    onViewLog: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.md)) {
            Text(
                text = stageLabel(state.progress),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.height(MobileIdeSpacing.sm))
            val total = state.progress?.bytesTotal
            val done = state.progress?.bytesDone ?: 0L
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.runtimes_progress_bytes,
                        formatFileSize(done),
                        formatFileSize(total)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = stringResource(R.string.runtimes_busy_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row {
                if (state.logs.isNotEmpty()) {
                    TextButton(onClick = onViewLog) {
                        Text(stringResource(R.string.runtimes_view_log))
                    }
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.runtimes_cancel))
                }
            }
        }
    }
}

@Composable
private fun stageLabel(progress: RuntimeProgress?): String {
    if (progress == null) return stringResource(R.string.runtimes_stage_finalizing)
    val file = progress.fileName.orEmpty()
    return when (progress.stage) {
        RuntimeStage.DOWNLOADING -> stringResource(R.string.runtimes_stage_downloading, file)
        RuntimeStage.VERIFYING -> stringResource(R.string.runtimes_stage_verifying, file)
        RuntimeStage.EXTRACTING -> stringResource(R.string.runtimes_stage_extracting, file)
        RuntimeStage.FINALIZING -> stringResource(R.string.runtimes_stage_finalizing)
    }
}

@Composable
private fun StateChip(state: RuntimeState) {
    val label = when (state) {
        RuntimeState.NOT_INSTALLED -> R.string.runtimes_state_not_installed
        RuntimeState.DOWNLOADING,
        RuntimeState.INSTALLING -> R.string.runtimes_state_installing

        RuntimeState.INSTALLED -> R.string.runtimes_state_installed
        RuntimeState.CHECKING -> R.string.runtimes_state_checking
        RuntimeState.FAILED -> R.string.runtimes_state_failed
        RuntimeState.UNSUPPORTED -> R.string.runtimes_state_unsupported
        RuntimeState.UPDATE_AVAILABLE -> R.string.runtimes_state_installed
    }
    val container: Color
    val content: Color
    when (state) {
        RuntimeState.INSTALLED -> {
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.onPrimaryContainer
        }

        RuntimeState.FAILED -> {
            container = MaterialTheme.colorScheme.errorContainer
            content = MaterialTheme.colorScheme.onErrorContainer
        }

        RuntimeState.CHECKING,
        RuntimeState.DOWNLOADING,
        RuntimeState.INSTALLING -> {
            container = MaterialTheme.colorScheme.secondaryContainer
            content = MaterialTheme.colorScheme.onSecondaryContainer
        }

        else -> {
            container = MaterialTheme.colorScheme.surfaceContainerHigh
            content = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Surface(color = container, shape = CircleShape) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun Notice(message: String, isError: Boolean, onDismiss: () -> Unit) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(shape = RoundedCornerShape(12.dp), color = container) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = MobileIdeSpacing.md, end = MobileIdeSpacing.sm, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = content,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.runtimes_dismiss), color = content)
            }
        }
    }
}

@Composable
private fun LogDialog(lines: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.runtimes_log_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                HorizontalDivider()
                if (lines.isEmpty()) {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.runtimes_dismiss))
            }
        }
    )
}

@Composable
private fun RuntimeCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.md), content = content)
    }
}
