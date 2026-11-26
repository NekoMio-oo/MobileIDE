package dev.mobileide.feature.fileexplorer.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.core.ui.entryFailureMessage
import dev.mobileide.core.ui.entryNameErrorMessage
import dev.mobileide.core.ui.formatFileSize
import dev.mobileide.domain.files.FileType
import dev.mobileide.domain.model.Project
import dev.mobileide.domain.repository.ProjectFileEntry
import dev.mobileide.feature.fileexplorer.model.DeleteRequestState
import dev.mobileide.feature.fileexplorer.model.EntryDialogMode
import dev.mobileide.feature.fileexplorer.model.EntryDialogState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun EntryNameDialog(
    dialog: EntryDialogState,
    location: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val titleRes = when (dialog.mode) {
        EntryDialogMode.CREATE_FILE -> R.string.explorer_create_file_title
        EntryDialogMode.CREATE_FOLDER -> R.string.explorer_create_folder_title
        EntryDialogMode.RENAME -> R.string.explorer_rename_title
    }
    val confirmRes = if (dialog.mode == EntryDialogMode.RENAME) {
        R.string.explorer_rename_confirm
    } else {
        R.string.explorer_create_confirm
    }
    val placeholderRes = when {
        dialog.mode == EntryDialogMode.CREATE_FOLDER -> R.string.explorer_name_folder_hint
        dialog.mode == EntryDialogMode.CREATE_FILE -> R.string.explorer_name_file_hint
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.explorer_name_location, location),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(MobileIdeSpacing.md))
                OutlinedTextField(
                    value = dialog.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.explorer_name_label)) },
                    placeholder = if (placeholderRes != null) {
                        { Text(stringResource(placeholderRes)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    enabled = !dialog.isSubmitting,
                    isError = dialog.nameError != null || dialog.failure != null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                    modifier = Modifier.fillMaxWidth()
                )
                val message = when {
                    dialog.nameError != null -> stringResource(
                        entryNameErrorMessage(dialog.nameError)
                    )

                    dialog.failure != null -> stringResource(
                        entryFailureMessage(dialog.failure)
                    )

                    else -> null
                }
                if (message != null) {
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (dialog.failureDetail != null && dialog.nameError == null) {
                    Text(
                        text = dialog.failureDetail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !dialog.isSubmitting) {
                Text(stringResource(confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !dialog.isSubmitting) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun DeleteEntryDialog(
    request: DeleteRequestState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val entry = request.entry
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (entry.isDirectory) {
                        R.string.explorer_delete_folder_title
                    } else {
                        R.string.explorer_delete_file_title
                    }
                )
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        if (entry.isDirectory) {
                            R.string.explorer_delete_folder_message
                        } else {
                            R.string.explorer_delete_file_message
                        },
                        entry.name
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (entry.isDirectory) {
                    val count = request.descendantCount
                    if (count != null && count > 0) {
                        Spacer(Modifier.height(MobileIdeSpacing.sm))
                        Text(
                            text = pluralStringResource(
                                R.plurals.explorer_delete_folder_contents,
                                count,
                                count
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (request.failure != null) {
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    Text(
                        text = stringResource(entryFailureMessage(request.failure)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (request.failureDetail != null) {
                    Text(
                        text = request.failureDetail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !request.isDeleting) {
                Text(
                    text = stringResource(
                        if (request.isDeleting) {
                            R.string.explorer_deleting
                        } else {
                            R.string.explorer_delete_confirm
                        }
                    ),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !request.isDeleting) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun EntryInfoDialog(
    entry: ProjectFileEntry,
    projectPath: String?,
    onDismiss: () -> Unit
) {
    val absolutePath = if (projectPath.isNullOrBlank()) {
        entry.path
    } else {
        "$projectPath/${entry.path}"
    }
    val typeLabel = if (entry.isDirectory) {
        stringResource(R.string.explorer_info_folder)
    } else {
        FileType.fromFileName(entry.name).displayName
    }
    val permissions = when {
        entry.canRead && entry.canWrite -> stringResource(R.string.explorer_info_read_write)
        entry.canRead -> stringResource(R.string.explorer_info_read_only)
        else -> stringResource(R.string.explorer_info_no_access)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_info_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                InfoRow(
                    label = stringResource(R.string.explorer_info_name),
                    value = entry.name
                )
                InfoRow(
                    label = stringResource(R.string.explorer_info_type),
                    value = typeLabel
                )
                InfoRow(
                    label = stringResource(R.string.explorer_info_size),
                    value = if (entry.isDirectory) "—" else formatFileSize(entry.size)
                )
                InfoRow(
                    label = stringResource(R.string.explorer_info_modified),
                    value = if (entry.lastModified > 0L) {
                        formatTimestamp(entry.lastModified)
                    } else {
                        stringResource(R.string.explorer_info_unknown)
                    }
                )
                InfoRow(
                    label = stringResource(R.string.explorer_info_permissions),
                    value = permissions
                )
                Spacer(Modifier.height(MobileIdeSpacing.sm))
                Text(
                    text = stringResource(R.string.explorer_info_path),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SelectionContainer {
                    Text(
                        text = absolutePath,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.explorer_info_close))
            }
        }
    )
}

@Composable
fun ProjectInfoDialog(project: Project, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_info_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                InfoRow(
                    label = stringResource(R.string.explorer_info_name),
                    value = project.name
                )
                InfoRow(
                    label = stringResource(R.string.explorer_project_type),
                    value = project.type.displayName
                )
                InfoRow(
                    label = stringResource(R.string.explorer_info_location),
                    value = project.path ?: stringResource(R.string.explorer_info_unknown)
                )
                InfoRow(
                    label = stringResource(R.string.explorer_info_created),
                    value = if (project.createdAt > 0L) {
                        formatTimestamp(project.createdAt)
                    } else {
                        stringResource(R.string.explorer_info_unknown)
                    }
                )
                InfoRow(
                    label = stringResource(R.string.explorer_info_opened),
                    value = if (project.lastOpenedAt > 0L) {
                        formatTimestamp(project.lastOpenedAt)
                    } else {
                        stringResource(R.string.explorer_info_unknown)
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.explorer_info_close))
            }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp)
        )
        Spacer(Modifier.width(MobileIdeSpacing.sm))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatTimestamp(epochMillis: Long): String {
    val formatter = DateTimeFormatter
        .ofPattern("MMM d, yyyy HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(epochMillis))
}
