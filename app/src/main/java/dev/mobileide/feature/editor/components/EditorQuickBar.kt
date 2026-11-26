package dev.mobileide.feature.editor.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.feature.editor.EditorController
import io.github.rosemoe.sora.widget.SelectionMovement

private data class QuickKey(
    val label: String,
    val open: String,
    val close: String? = null
)

/**
 * Keyboard accessory row. It sits inside the IME padding of the screen, so it
 * always stays above the soft keyboard, and scrolls horizontally on narrow
 * devices.
 */
@Composable
fun EditorQuickBar(
    controller: EditorController,
    modifier: Modifier = Modifier
) {
    val keys = remember {
        listOf(
            QuickKey("Tab", "\t"),
            QuickKey("{ }", "{", "}"),
            QuickKey("( )", "(", ")"),
            QuickKey("[ ]", "[", "]"),
            QuickKey(";", ";"),
            QuickKey(":", ":"),
            QuickKey("\"\"", "\"", "\""),
            QuickKey("''", "'", "'"),
            QuickKey("=", "=")
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            keys.forEach { key ->
                QuickKeyButton(label = key.label) {
                    val close = key.close
                    if (close == null) {
                        controller.insert(key.open)
                    } else {
                        controller.insertPair(key.open, close)
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            MovementButton(label = "←", contentDescription = stringResource(R.string.editor_move_left)) {
                controller.move(SelectionMovement.LEFT)
            }
            MovementButton(label = "→", contentDescription = stringResource(R.string.editor_move_right)) {
                controller.move(SelectionMovement.RIGHT)
            }
            MovementButton(label = "↑", contentDescription = stringResource(R.string.editor_move_up)) {
                controller.move(SelectionMovement.UP)
            }
            MovementButton(label = "↓", contentDescription = stringResource(R.string.editor_move_down)) {
                controller.move(SelectionMovement.DOWN)
            }
        }
    }
}

@Composable
private fun QuickKeyButton(label: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun MovementButton(
    label: String,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = contentDescription, onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
