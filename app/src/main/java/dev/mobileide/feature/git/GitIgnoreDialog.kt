package dev.mobileide.feature.git

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.domain.git.GitIgnoreTemplate
import dev.mobileide.feature.git.model.GitIgnoreUiState

/** Create/edit .gitignore; templates append instead of replacing existing text. */
@Composable
internal fun GitIgnoreDialog(
    state: GitIgnoreUiState,
    onContentChanged: (String) -> Unit,
    onTemplate: (GitIgnoreTemplate) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    var templateMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        title = { Text(stringResource(R.string.git_gitignore_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(MobileIdeSpacing.lg)
                    )
                } else {
                    Text(
                        text = stringResource(
                            if (state.exists) {
                                R.string.git_gitignore_existing
                            } else {
                                R.string.git_gitignore_new
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    Box {
                        TextButton(onClick = { templateMenu = true }) {
                            Text(stringResource(R.string.git_gitignore_template))
                        }
                        DropdownMenu(
                            expanded = templateMenu,
                            onDismissRequest = { templateMenu = false }
                        ) {
                            GitIgnoreTemplate.entries.forEach { template ->
                                DropdownMenuItem(
                                    text = { Text(template.displayName) },
                                    onClick = {
                                        onTemplate(template)
                                        templateMenu = false
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.content,
                        onValueChange = onContentChanged,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 10,
                        maxLines = 20,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        enabled = !state.isSaving
                    )
                    Spacer(Modifier.height(MobileIdeSpacing.xs))
                    Text(
                        text = stringResource(R.string.git_gitignore_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    state.error?.let { error ->
                        Spacer(Modifier.height(MobileIdeSpacing.sm))
                        Text(
                            text = error.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = !state.isLoading &&
                    !state.isSaving &&
                    state.content.isNotBlank()
            ) {
                Text(stringResource(R.string.git_gitignore_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.isSaving) {
                Text(stringResource(R.string.git_cancel))
            }
        }
    )
}
