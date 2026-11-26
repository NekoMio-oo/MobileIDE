package dev.mobileide.core.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.mobileide.R
import dev.mobileide.domain.model.Project

/**
 * Explains that a record came from the SAF era and offers to bind it to a real
 * folder. Removing only deletes the record; files are never touched.
 */
@Composable
fun ProjectRelinkDialog(
    project: Project,
    onDismiss: () -> Unit,
    onLocate: () -> Unit,
    onRemove: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.relink_title)) },
        text = {
            Text(stringResource(R.string.relink_message, project.name))
        },
        confirmButton = {
            TextButton(onClick = onLocate) {
                Text(stringResource(R.string.relink_locate))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(onClick = onRemove) {
                    Text(
                        text = stringResource(R.string.access_error_remove),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    )
}
