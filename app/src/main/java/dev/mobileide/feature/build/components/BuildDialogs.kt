package dev.mobileide.feature.build.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.domain.build.BuildArtifact
import dev.mobileide.domain.build.BuildState
import dev.mobileide.domain.build.BuildTask
import dev.mobileide.feature.build.buildStateLabelRes
import dev.mobileide.feature.build.formatBuildDuration
import dev.mobileide.feature.build.formatBuildTime

@Composable
fun BuildHistoryDialog(
    tasks: List<BuildTask>,
    onViewLog: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.build_history_title)) },
        text = {
            if (tasks.isEmpty()) {
                Text(stringResource(R.string.build_history_empty))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(tasks) { task ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onViewLog(task.id) }
                                .padding(vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(buildStateLabelRes(task.state)),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when (task.state) {
                                        BuildState.SUCCEEDED -> MaterialTheme.colorScheme.primary
                                        BuildState.FAILED -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.width(84.dp)
                                )
                                Text(
                                    text = "${task.language.displayName} · ${task.mode.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = formatBuildDuration(task.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = pluralStringResource(
                                    R.plurals.build_history_stats,
                                    task.errorCount,
                                    formatBuildTime(task.startedAt),
                                    task.errorCount,
                                    task.warningCount
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            task.failureMessage?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
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
fun DeleteArtifactDialog(
    artifact: BuildArtifact,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.build_artifact_delete_title)) },
        text = { Text(stringResource(R.string.build_artifact_delete_message, artifact.path)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.build_artifact_delete),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.runtimes_cancel))
            }
        }
    )
}

@Composable
fun UnsavedFilesDialog(
    fileNames: List<String>,
    onBuildAnyway: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.build_unsaved_title)) },
        text = {
            Column {
                Text(
                    pluralStringResource(
                        R.plurals.build_unsaved_message,
                        fileNames.size,
                        fileNames.size
                    )
                )
                Spacer(Modifier.height(8.dp))
                fileNames.take(8).forEach { name ->
                    Text(
                        text = "• $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onBuildAnyway) {
                Text(stringResource(R.string.build_unsaved_build))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.runtimes_cancel))
            }
        }
    )
}
