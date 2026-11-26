package dev.mobileide.feature.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.core.ui.fileTypeIcon
import dev.mobileide.domain.editor.EditorTab
import dev.mobileide.domain.editor.EditorTabStatus

/**
 * Compact, horizontally scrollable tab strip with a fixed overflow menu.
 * Kept at 40 dp so it never squeezes the editing area on a phone.
 */
@Composable
fun EditorTabBar(
    tabs: List<EditorTab>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onCloseCurrent: () -> Unit,
    onCloseOthers: () -> Unit,
    onCloseAll: () -> Unit,
    onSave: () -> Unit,
    onSaveAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return

    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val activeIndex = tabs.indexOfFirst { it.id == activeTabId }
    val hasDirty = tabs.any { it.isDirty }
    val activeDirty = tabs.firstOrNull { it.id == activeTabId }
        ?.let { it.isDirty && it.status == EditorTabStatus.READY } == true

    LaunchedEffect(activeTabId) {
        if (activeIndex >= 0) listState.animateScrollToItem(activeIndex)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(items = tabs, key = { it.id }) { tab ->
                EditorTabChip(
                    tab = tab,
                    isActive = tab.id == activeTabId,
                    onClick = { onSelect(tab.id) },
                    onClose = { onClose(tab.id) }
                )
            }
        }

        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.editor_tabs_menu),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val run: (() -> Unit) -> Unit = { action ->
                    menuOpen = false
                    action()
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_save)) },
                    enabled = activeDirty,
                    onClick = { run(onSave) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_tabs_save_all)) },
                    enabled = hasDirty,
                    onClick = { run(onSaveAll) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_tabs_close_current)) },
                    enabled = activeTabId != null,
                    onClick = { run(onCloseCurrent) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_tabs_close_others)) },
                    enabled = tabs.size > 1,
                    onClick = { run(onCloseOthers) }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_tabs_close_all)) },
                    onClick = { run(onCloseAll) }
                )
            }
        }
    }
}

@Composable
private fun EditorTabChip(
    tab: EditorTab,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val background = if (isActive) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        Color.Transparent
    }
    Column(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(fileTypeIcon(tab.name)),
                contentDescription = null,
                tint = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = tab.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 118.dp)
            )
            if (tab.isDirty) {
                Spacer(Modifier.width(5.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Spacer(Modifier.width(2.dp))
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.editor_tab_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary else Color.Transparent
                )
        )
    }
}
