package dev.mobileide.core.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.domain.model.Project
import dev.mobileide.domain.validation.ProjectNameError
import dev.mobileide.domain.validation.ProjectNameValidator

@StringRes
fun projectNameErrorMessage(error: ProjectNameError): Int = when (error) {
    ProjectNameError.EMPTY -> R.string.name_error_empty
    ProjectNameError.TOO_LONG -> R.string.name_error_too_long
    ProjectNameError.INVALID_CHARACTERS -> R.string.name_error_invalid
    ProjectNameError.RESERVED -> R.string.name_error_reserved
}

/**
 * Rename dialog. The name is validated locally before it reaches the repository,
 * and the hint states up front that the folder may keep its old name.
 */
@Composable
fun RenameProjectDialog(
    project: Project,
    isWorking: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(project.id) { mutableStateOf(project.name) }
    var showError by remember(project.id) { mutableStateOf(false) }
    val error = ProjectNameValidator.validate(name)

    AlertDialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    showError = true
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.create_project_name_label)) },
                isError = showError && error != null,
                supportingText = {
                    Text(
                        text = if (showError && error != null) {
                            stringResource(projectNameErrorMessage(error))
                        } else {
                            stringResource(R.string.rename_folder_hint)
                        }
                    )
                }
            )
        },
        confirmButton = {
            TextButton(
                enabled = !isWorking,
                onClick = {
                    showError = true
                    if (error == null) {
                        onConfirm(ProjectNameValidator.normalize(name))
                    }
                }
            ) {
                Text(stringResource(R.string.rename_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isWorking) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Remove confirmation. Removing the record is the default; deleting user files
 * is a separate, explicitly labelled action.
 */
@Composable
fun RemoveProjectDialog(
    project: Project,
    allowFileDeletion: Boolean,
    onDismiss: () -> Unit,
    onRemoveOnly: () -> Unit,
    onDeleteFiles: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_title)) },
        text = {
            Column {
                Text(stringResource(R.string.remove_message, project.name))
                if (allowFileDeletion) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onDeleteFiles,
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.project_menu_delete_files),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRemoveOnly) {
                Text(stringResource(R.string.remove_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Second, irreversible confirmation before user files are deleted. */
@Composable
fun DeleteProjectFilesDialog(
    project: Project,
    isWorking: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        title = { Text(stringResource(R.string.delete_files_confirm_title)) },
        text = { Text(stringResource(R.string.delete_files_confirm_message, project.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isWorking) {
                Text(
                    text = stringResource(R.string.delete_files_confirm),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isWorking) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Shown when a project URI is no longer readable or writable. */
@Composable
fun ProjectAccessErrorDialog(
    project: Project,
    onDismiss: () -> Unit,
    onRemove: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.access_error_title)) },
        text = { Text(stringResource(R.string.access_error_message, project.name)) },
        confirmButton = {
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.access_error_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
