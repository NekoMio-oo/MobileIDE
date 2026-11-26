package dev.mobileide.feature.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.feature.editor.model.EditorLspAction
import dev.mobileide.feature.editor.model.EditorLspMenu

@Composable
fun EditorTopBar(
    fileName: String,
    meta: String,
    isDirty: Boolean,
    isSaving: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectAll: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onFind: () -> Unit,
    onBuildProject: () -> Unit,
    onTerminal: () -> Unit,
    lspMenu: EditorLspMenu,
    onLspAction: (EditorLspAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.action_back)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isDirty) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(Modifier.size(2.dp))
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(16.dp))
        } else {
            IconButton(onClick = onSave, enabled = canSave) {
                Icon(
                    painter = painterResource(R.drawable.ic_save),
                    contentDescription = stringResource(R.string.editor_save),
                    tint = if (canSave) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    }
                )
            }
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.action_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                val dismissThen: (() -> Unit) -> Unit = { action ->
                    menuExpanded = false
                    action()
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.build_project_action)) },
                    onClick = { dismissThen(onBuildProject) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.terminal_open)) },
                    onClick = { dismissThen(onTerminal) }
                )
                if (lspMenu.hasAny) {
                    HorizontalDivider()
                    if (lspMenu.canComplete) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lsp_action_complete)) },
                            onClick = { dismissThen { onLspAction(EditorLspAction.COMPLETE) } }
                        )
                    }
                    if (lspMenu.canHover) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lsp_action_hover)) },
                            onClick = { dismissThen { onLspAction(EditorLspAction.HOVER) } }
                        )
                    }
                    if (lspMenu.canDefinition) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lsp_action_definition)) },
                            onClick = { dismissThen { onLspAction(EditorLspAction.DEFINITION) } }
                        )
                    }
                    if (lspMenu.canReferences) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lsp_action_references)) },
                            onClick = { dismissThen { onLspAction(EditorLspAction.REFERENCES) } }
                        )
                    }
                    if (lspMenu.canSymbols) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lsp_action_symbols)) },
                            onClick = { dismissThen { onLspAction(EditorLspAction.SYMBOLS) } }
                        )
                    }
                    if (lspMenu.canFormat) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.lsp_action_format)) },
                            onClick = { dismissThen { onLspAction(EditorLspAction.FORMAT) } }
                        )
                    }
                    if (lspMenu.problemCount > 0) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        R.string.lsp_action_problems,
                                        lspMenu.problemCount
                                    )
                                )
                            },
                            onClick = { dismissThen { onLspAction(EditorLspAction.PROBLEMS) } }
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_undo)) },
                    onClick = { dismissThen(onUndo) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_redo)) },
                    onClick = { dismissThen(onRedo) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_select_all)) },
                    onClick = { dismissThen(onSelectAll) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_cut)) },
                    onClick = { dismissThen(onCut) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_copy)) },
                    onClick = { dismissThen(onCopy) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_paste)) },
                    onClick = { dismissThen(onPaste) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_find)) },
                    onClick = { dismissThen(onFind) }
                )
            }
        }
    }
}
