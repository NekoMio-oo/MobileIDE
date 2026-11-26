package dev.mobileide.feature.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.domain.lsp.LspDiagnostic
import dev.mobileide.domain.lsp.LspLocation
import dev.mobileide.domain.lsp.LspSeverity

data class LspSymbolRow(
    val name: String,
    val detail: String?,
    val depth: Int,
    val line: Int,
    val column: Int
)

/** Content shown in the bottom sheet opened from the editor menu. */
sealed interface LspPanel {
    val title: String

    data class Text(override val title: String, val body: String) : LspPanel

    data class Locations(
        override val title: String,
        val items: List<LspLocation>
    ) : LspPanel

    data class Symbols(
        override val title: String,
        val path: String,
        val items: List<LspSymbolRow>
    ) : LspPanel

    data class Problems(
        override val title: String,
        val path: String,
        val items: List<LspDiagnostic>
    ) : LspPanel
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LspPanelSheet(
    panel: LspPanel,
    onDismiss: () -> Unit,
    onNavigate: (relativePath: String, line: Int, column: Int) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
        ) {
            Text(
                text = panel.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            when (panel) {
                is LspPanel.Text -> Text(
                    text = panel.body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )

                is LspPanel.Locations -> if (panel.items.isEmpty()) {
                    EmptyPanel(stringResource(R.string.lsp_no_results))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(
                            panel.items,
                            key = { index, location ->
                                "$index:${location.relativePath}:${location.range.start.line}:" +
                                    "${location.range.start.character}"
                            }
                        ) { _, location ->
                            LocationRow(location) {
                                onNavigate(
                                    location.relativePath,
                                    location.range.start.line,
                                    location.range.start.character
                                )
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }

                is LspPanel.Symbols -> if (panel.items.isEmpty()) {
                    EmptyPanel(stringResource(R.string.lsp_no_results))
                } else {
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        itemsIndexed(
                            panel.items,
                            key = { index, symbol ->
                                "$index:${symbol.line}:${symbol.column}:${symbol.name}"
                            }
                        ) { _, symbol ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onNavigate(panel.path, symbol.line, symbol.column)
                                    }
                                    .padding(
                                        start = (8 + symbol.depth * 14).dp,
                                        top = 10.dp,
                                        bottom = 10.dp,
                                        end = 8.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = symbol.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                symbol.detail?.let { detail ->
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                is LspPanel.Problems -> if (panel.items.isEmpty()) {
                    EmptyPanel(stringResource(R.string.lsp_no_problems))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(panel.items) { diagnostic ->
                            DiagnosticRow(diagnostic) {
                                onNavigate(
                                    panel.path,
                                    diagnostic.range.start.line,
                                    diagnostic.range.start.character
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun EmptyPanel(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp)
    )
}

@Composable
private fun LocationRow(location: LspLocation, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_file_code),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = location.relativePath.substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${location.relativePath} : ${location.range.start.line + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DiagnosticRow(diagnostic: LspDiagnostic, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        val color = when (diagnostic.severity) {
            LspSeverity.ERROR -> MaterialTheme.colorScheme.error
            LspSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = diagnostic.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = buildString {
                    append("Line ").append(diagnostic.range.start.line + 1)
                    diagnostic.source?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
