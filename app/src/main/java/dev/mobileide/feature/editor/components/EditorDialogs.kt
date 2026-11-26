package dev.mobileide.feature.editor.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.core.ui.projectFileErrorMessage
import dev.mobileide.feature.editor.model.CloseDialogState
import dev.mobileide.feature.editor.model.ExternalChangeState

@Composable
fun EditorUnsavedDialog(
    dialog: CloseDialogState,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    val single = dialog.dirtyNames.size == 1
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.editor_unsaved_title)) },
        text = {
            Column {
                Text(
                    text = if (single) {
                        stringResource(
                            R.string.editor_unsaved_one,
                            dialog.dirtyNames.firstOrNull().orEmpty()
                        )
                    } else {
                        pluralStringResource(
                            R.plurals.editor_unsaved_many,
                            dialog.dirtyNames.size,
                            dialog.dirtyNames.size
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (dialog.dirtyNames.size > 1) {
                    Spacer(Modifier.height(MobileIdeSpacing.xs))
                    Text(
                        text = dialog.dirtyNames.take(6).joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (dialog.failedNames.isNotEmpty()) {
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    Text(
                        text = stringResource(
                            R.string.editor_unsaved_failed,
                            dialog.failedNames.joinToString(", ")
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (dialog.changedNames.isNotEmpty()) {
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    Text(
                        text = stringResource(
                            R.string.editor_unsaved_changed,
                            dialog.changedNames.joinToString(", ")
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !dialog.isSaving) {
                Text(
                    stringResource(
                        if (dialog.isSaving) {
                            R.string.editor_saving
                        } else if (single) {
                            R.string.editor_unsaved_save
                        } else {
                            R.string.editor_unsaved_save_all
                        }
                    )
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel, enabled = !dialog.isSaving) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(onClick = onDiscard, enabled = !dialog.isSaving) {
                    Text(
                        text = stringResource(
                            if (single) {
                                R.string.editor_unsaved_discard
                            } else {
                                R.string.editor_unsaved_discard_all
                            }
                        ),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}

@Composable
fun EditorExternalChangeDialog(
    state: ExternalChangeState,
    onOverwrite: () -> Unit,
    onReload: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.editor_external_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.editor_external_message, state.fileName),
                    style = MaterialTheme.typography.bodyMedium
                )
                state.error?.let { error ->
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    Text(
                        text = stringResource(projectFileErrorMessage(error)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                state.errorDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onReload, enabled = !state.isWorking) {
                    Text(stringResource(R.string.editor_external_reload))
                }
                TextButton(onClick = onOverwrite, enabled = !state.isWorking) {
                    Text(
                        text = stringResource(
                            if (state.isWorking) {
                                R.string.editor_saving
                            } else {
                                R.string.editor_external_overwrite
                            }
                        ),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !state.isWorking) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun EditorTabLimitDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_tab_limit_title)) },
        text = { Text(stringResource(R.string.editor_tab_limit_message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_dismiss))
            }
        }
    )
}
