package dev.mobileide.feature.terminal.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mobileide.domain.terminal.TerminalKey

/**
 * Auxiliary keys for a phone keyboard. CTRL expands a row of common control
 * codes; ALT prefixes the next printable/control key with ESC. Combinations the
 * shell does not support are never faked.
 */
@Composable
fun TerminalKeyBar(
    onKey: (TerminalKey) -> Unit,
    onAltKey: (TerminalKey) -> Unit,
    modifier: Modifier = Modifier
) {
    var ctrlRow by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }

    fun emit(key: TerminalKey) {
        if (altArmed) {
            altArmed = false
            onAltKey(key)
        } else {
            onKey(key)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton("ESC") { emit(TerminalKey.ESCAPE) }
            KeyButton("TAB") { emit(TerminalKey.TAB) }
            KeyButton("CTRL", active = ctrlRow) { ctrlRow = !ctrlRow }
            KeyButton("ALT", active = altArmed) { altArmed = !altArmed }
            KeyButton("←") { emit(TerminalKey.ARROW_LEFT) }
            KeyButton("↑") { emit(TerminalKey.ARROW_UP) }
            KeyButton("↓") { emit(TerminalKey.ARROW_DOWN) }
            KeyButton("→") { emit(TerminalKey.ARROW_RIGHT) }
            KeyButton("/") { emit(TerminalKey.SLASH) }
            KeyButton("-") { emit(TerminalKey.DASH) }
        }
        if (ctrlRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                KeyButton("^C") { emit(TerminalKey.CTRL_C) }
                KeyButton("^D") { emit(TerminalKey.CTRL_D) }
                KeyButton("^L") { emit(TerminalKey.CTRL_L) }
                KeyButton("^Z") { emit(TerminalKey.CTRL_Z) }
                KeyButton("^A") { emit(TerminalKey.CTRL_A) }
                KeyButton("^E") { emit(TerminalKey.CTRL_E) }
                KeyButton("^U") { emit(TerminalKey.CTRL_U) }
                KeyButton("^K") { emit(TerminalKey.CTRL_K) }
            }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            ),
            color = if (active) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
