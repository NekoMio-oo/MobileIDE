package dev.mobileide.feature.terminal.components

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mobileide.domain.terminal.TerminalRun
import dev.mobileide.domain.terminal.TerminalSnapshot
import dev.mobileide.feature.terminal.TerminalPalette

/**
 * Renders the emulator grid: one Text per line, styled runs inside an
 * AnnotatedString. Auto-scroll follows new output until the user drags.
 */
@Composable
fun TerminalOutput(
    snapshot: TerminalSnapshot,
    fontSizeSp: Int,
    autoScroll: Boolean,
    onAutoScrollChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = isSystemInDarkTheme()
    val palette = remember(dark) { TerminalPalette(dark) }
    val defaultForeground = MaterialTheme.colorScheme.onSurface
    val defaultBackground = MaterialTheme.colorScheme.surface
    val listState = rememberLazyListState()

    LaunchedEffect(snapshot.revision, snapshot.lines.size, autoScroll) {
        if (autoScroll && snapshot.lines.isNotEmpty()) {
            listState.scrollToItem(snapshot.lines.lastIndex)
        }
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && autoScroll) onAutoScrollChange(false)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        itemsIndexed(snapshot.lines) { _, runs ->
            val annotated = remember(runs, palette, defaultForeground) {
                buildLine(runs, palette, defaultForeground, defaultBackground)
            }
            Text(
                text = annotated,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.28f).sp,
                style = TextStyle(
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                softWrap = false,
                maxLines = 1,
                color = defaultForeground
            )
        }
    }
}

private fun buildLine(
    runs: List<TerminalRun>,
    palette: TerminalPalette,
    defaultForeground: Color,
    defaultBackground: Color
): AnnotatedString = buildAnnotatedString {
    runs.forEach { run ->
        val foreground = palette.color(run.fg) ?: defaultForeground
        val background = palette.color(run.bg)
        val inverseForeground: Color = background ?: defaultBackground
        val style = SpanStyle(
            color = if (run.inverse) inverseForeground else foreground,
            background = if (run.inverse) foreground else (background ?: Color.Unspecified),
            fontWeight = if (run.bold) {
                androidx.compose.ui.text.font.FontWeight.Bold
            } else {
                null
            },
            textDecoration = if (run.underline) TextDecoration.Underline else null
        )
        withStyle(style) {
            append(run.text)
        }
    }
}
