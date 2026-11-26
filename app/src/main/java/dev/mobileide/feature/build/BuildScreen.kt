package dev.mobileide.feature.build

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobileide.R
import dev.mobileide.domain.build.BuildSnapshot
import dev.mobileide.domain.build.BuildState
import dev.mobileide.domain.build.ToolchainStatus
import dev.mobileide.feature.build.components.BuildArtifactsPanel
import dev.mobileide.feature.build.components.BuildHistoryDialog
import dev.mobileide.feature.build.components.BuildLogPanel
import dev.mobileide.feature.build.components.BuildProblemsPanel
import dev.mobileide.feature.build.components.BuildTopBar
import dev.mobileide.feature.build.components.DeleteArtifactDialog
import dev.mobileide.feature.build.components.UnsavedFilesDialog
import dev.mobileide.feature.build.model.BuildPanel

/**
 * Build screen: toolbar, status, and three switchable panels (log, problems,
 * artifacts). The build itself runs in the app-scoped manager, so navigating
 * away does not stop it.
 */
@Composable
fun BuildScreen(
    onBack: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenEditor: (String) -> Unit,
    onOpenArtifacts: (String) -> Unit,
    onOpenRuntimes: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BuildViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snapshot = uiState.snapshot

    Column(modifier = modifier.fillMaxSize()) {
        BuildTopBar(
            projectName = snapshot.projectName.ifBlank { stringResource(R.string.build_title) },
            subtitle = "${uiState.config.language.displayName} · " +
                uiState.config.mode.displayName,
            isBuilding = uiState.isBuilding,
            canBuild = !uiState.isBuilding && !snapshot.isLoading &&
                snapshot.toolchain.status != ToolchainStatus.UNSUPPORTED,
            onBack = onBack,
            onBuild = viewModel::requestBuild,
            onCancel = viewModel::cancelBuild,
            onSettings = onOpenConfig,
            onTerminal = onOpenTerminal
        )

        uiState.message?.let { message ->
            MessageBanner(message = message, onDismiss = viewModel::dismissMessage)
        }

        BuildStatusCard(
            snapshot = snapshot,
            errorCount = uiState.errorCount,
            warningCount = uiState.warningCount,
            onRefreshToolchain = viewModel::refreshToolchain,
            onOpenRuntimes = onOpenRuntimes,
            onHistory = { viewModel.showHistory(true) },
            onRebuild = viewModel::retry,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        BuildPanelTabs(
            selected = uiState.panel,
            problemCount = uiState.problems.size,
            artifactCount = snapshot.artifacts.size,
            onSelect = viewModel::selectPanel
        )

        Box(modifier = Modifier.weight(1f)) {
            when (uiState.panel) {
                BuildPanel.LOG -> BuildLogPanel(
                    logs = snapshot.logs,
                    autoScroll = uiState.autoScroll,
                    truncated = snapshot.logTruncated,
                    onAutoScrollChange = viewModel::setAutoScroll,
                    onClear = viewModel::clearLogs
                )

                BuildPanel.PROBLEMS -> BuildProblemsPanel(
                    problems = snapshot.problems,
                    onOpen = { problem ->
                        viewModel.revealProblem(problem)
                        problem.path?.let(onOpenEditor)
                    }
                )

                BuildPanel.ARTIFACTS -> BuildArtifactsPanel(
                    artifacts = snapshot.artifacts,
                    onOpenFolder = onOpenArtifacts,
                    onDelete = viewModel::requestDeleteArtifact
                )
            }
        }
    }

    uiState.unsavedPrompt?.let { prompt ->
        UnsavedFilesDialog(
            fileNames = prompt.fileNames,
            onBuildAnyway = viewModel::buildAnyway,
            onDismiss = viewModel::dismissUnsavedPrompt
        )
    }

    uiState.deleteRequest?.let { artifact ->
        DeleteArtifactDialog(
            artifact = artifact,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete
        )
    }

    if (uiState.showHistory) {
        BuildHistoryDialog(
            tasks = snapshot.history,
            onViewLog = viewModel::openTaskLog,
            onDismiss = { viewModel.showHistory(false) }
        )
    }
}

@Composable
private fun MessageBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.runtimes_dismiss))
            }
        }
    }
}

@Composable
private fun BuildStatusCard(
    snapshot: BuildSnapshot,
    errorCount: Int,
    warningCount: Int,
    onRefreshToolchain: () -> Unit,
    onOpenRuntimes: () -> Unit,
    onHistory: () -> Unit,
    onRebuild: () -> Unit,
    modifier: Modifier = Modifier
) {
    val task = snapshot.task
    val state = task?.state ?: BuildState.IDLE
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateIcon(state = state, running = snapshot.isBuilding)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stateLabel(state),
                        style = MaterialTheme.typography.titleSmall,
                        color = stateColor(state)
                    )
                    Text(
                        text = buildString {
                            append(snapshot.config.language.displayName)
                            append(" · ")
                            append(snapshot.config.mode.displayName)
                            task?.outputPath?.let {
                                append(" · ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (task != null && !snapshot.isBuilding) {
                    TextButton(onClick = onRebuild) {
                        Text(
                            text = stringResource(R.string.build_retry),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                TextButton(onClick = onHistory) {
                    Text(
                        text = stringResource(R.string.build_history),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (snapshot.isBuilding) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
                Text(
                    text = stringResource(R.string.build_keep_open),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (task != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    StatColumn(
                        label = stringResource(R.string.build_stat_duration),
                        value = formatBuildDuration(task.durationMs)
                    )
                    StatColumn(
                        label = stringResource(R.string.build_stat_errors),
                        value = errorCount.toString(),
                        highlight = errorCount > 0
                    )
                    StatColumn(
                        label = stringResource(R.string.build_stat_warnings),
                        value = warningCount.toString()
                    )
                }
                Text(
                    text = formatBuildTime(task.startedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            snapshot.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (snapshot.toolchain.status != ToolchainStatus.READY &&
                snapshot.toolchain.status != ToolchainStatus.UNKNOWN
            ) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = snapshot.toolchain.message
                            ?: toolchainLabel(snapshot.toolchain.status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefreshToolchain) {
                        Text(
                            text = stringResource(R.string.runtimes_check),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(onClick = onOpenRuntimes) {
                        Text(
                            text = stringResource(R.string.settings_runtimes),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, highlight: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun StateIcon(state: BuildState, running: Boolean) {
    if (running) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp
        )
        return
    }
    val icon = when (state) {
        BuildState.SUCCEEDED -> R.drawable.ic_check_circle
        BuildState.FAILED -> R.drawable.ic_error
        BuildState.CANCELLED, BuildState.INTERRUPTED -> R.drawable.ic_stop
        else -> R.drawable.ic_build
    }
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = stateColor(state),
        modifier = Modifier.size(18.dp)
    )
}

@Composable
private fun stateColor(state: BuildState) = when (state) {
    BuildState.SUCCEEDED -> MaterialTheme.colorScheme.primary
    BuildState.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun stateLabel(state: BuildState): String = stringResource(buildStateLabelRes(state))

@Composable
private fun toolchainLabel(status: ToolchainStatus): String = stringResource(
    when (status) {
        ToolchainStatus.READY -> R.string.build_toolchain_ready
        ToolchainStatus.LINUX_MISSING -> R.string.build_toolchain_linux_missing
        ToolchainStatus.TOOLCHAIN_MISSING -> R.string.build_toolchain_missing
        ToolchainStatus.UNSUPPORTED -> R.string.build_toolchain_unsupported
        ToolchainStatus.UNKNOWN -> R.string.build_toolchain_unknown
    }
)

@Composable
private fun BuildPanelTabs(
    selected: BuildPanel,
    problemCount: Int,
    artifactCount: Int,
    onSelect: (BuildPanel) -> Unit
) {
    val tabs = listOf(
        BuildPanel.LOG to stringResource(R.string.build_tab_log),
        BuildPanel.PROBLEMS to buildString {
            append(stringResource(R.string.build_tab_problems))
            if (problemCount > 0) append(" ($problemCount)")
        },
        BuildPanel.ARTIFACTS to buildString {
            append(stringResource(R.string.build_tab_artifacts))
            if (artifactCount > 0) append(" ($artifactCount)")
        }
    )
    TabRow(
        selectedTabIndex = tabs.indexOfFirst { it.first == selected }.coerceAtLeast(0),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        tabs.forEach { (panel, label) ->
            Tab(
                selected = panel == selected,
                onClick = { onSelect(panel) },
                text = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            )
        }
    }
}
