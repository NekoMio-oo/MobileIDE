package dev.mobileide.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.core.util.formatRelativeTime
import dev.mobileide.domain.model.Project

/**
 * Row used by both the home recents block and the projects list.
 *
 * Actions that do not apply (legacy rows without a path, home shortcuts) are
 * passed as null and hidden from the menu.
 */
@Composable
fun ProjectListItem(
    project: Project,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onCopyPath: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDeleteFiles: (() -> Unit)? = null,
    onRelink: (() -> Unit)? = null
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(
                start = MobileIdeSpacing.sm,
                end = MobileIdeSpacing.xs,
                top = MobileIdeSpacing.sm,
                bottom = MobileIdeSpacing.sm
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(MobileIdeDimens.projectIconSize)
                .clip(RoundedCornerShape(MobileIdeSpacing.md))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(MobileIdeSpacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(MobileIdeSpacing.sm))
                Text(
                    text = formatRelativeTime(project.lastOpenedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(2.dp))
            if (project.needsRelink) {
                Text(
                    text = stringResource(R.string.project_needs_relocation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = buildString {
                        append(project.type.label)
                        if (project.locationName.isNotBlank()) {
                            append(" · ")
                            append(project.locationName)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                if (onRelink != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.project_menu_relocate), style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            menuExpanded = false
                            onRelink()
                        }
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_open), style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            menuExpanded = false
                            onOpen()
                        }
                    )
                }
                if (onRename != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.project_menu_rename), style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                }
                if (onCopyPath != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_copy_location), style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            menuExpanded = false
                            onCopyPath()
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.project_menu_remove), style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        menuExpanded = false
                        onRemove()
                    }
                )
                if (onDeleteFiles != null) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.project_menu_delete_files),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDeleteFiles()
                        }
                    )
                }
            }
        }
    }
}
