package dev.mobileide.feature.build.components

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.EmptyState
import dev.mobileide.domain.build.BuildLogLevel
import dev.mobileide.domain.build.BuildLogLine

/**
 * Monospace log with auto-scroll. Dragging the list turns auto-scroll off so
 * reading older output is not fought by incoming lines.
 */
@Composable
fun BuildLogPanel(
    logs: List<BuildLogLine>,
    autoScroll: Boolean,
    truncated: Boolean,
    onAutoScrollChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState, autoScroll, logs.size) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && autoScroll) {
                onAutoScrollChange(false)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { onAutoScrollChange(!autoScroll) }) {
                Text(
                    text = stringResource(R.string.build_autoscroll),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (autoScroll) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            TextButton(onClick = onClear, enabled = logs.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.build_clear_log),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        if (truncated) {
            Text(
                text = stringResource(R.string.build_log_truncated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (logs.isEmpty()) {
            EmptyState(
                iconRes = R.drawable.ic_file_text,
                title = stringResource(R.string.build_tab_log),
                description = stringResource(R.string.build_empty_log)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 16.dp
                )
            ) {
                itemsIndexed(logs) { index, line ->
                    Text(
                        text = line.text.ifEmpty { " " },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        ),
                        color = when (line.level) {
                            BuildLogLevel.ERROR -> MaterialTheme.colorScheme.error
                            BuildLogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                            BuildLogLevel.COMMAND -> MaterialTheme.colorScheme.primary
                            BuildLogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                top = if (index == 0) 2.dp else 0.dp,
                                bottom = 1.dp
                            )
                    )
                }
            }
        }
    }
}
