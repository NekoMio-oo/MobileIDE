package dev.mobileide.feature.editor.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import dev.mobileide.domain.editor.EditorLanguage
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Builds a Sora color scheme from the Material palette so the editor follows
 * the app's light/dark theme without a second theme system.
 */
@Composable
fun rememberEditorColorScheme(): EditorColorScheme {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    return remember(colors, dark) { buildEditorColorScheme(colors, dark) }
}

/**
 * Sora 0.23 only ships a maintained Java lexer. C-like languages reuse it
 * (real tokenizer, not a regex), Python has a small scanner, and the remaining
 * languages stay plain text until TextMate/Tree-sitter lands.
 */
fun EditorLanguage.toSoraLanguage(): Language = when (this) {
    EditorLanguage.C,
    EditorLanguage.CPP,
    EditorLanguage.JAVA,
    EditorLanguage.KOTLIN,
    EditorLanguage.JAVASCRIPT,
    EditorLanguage.TYPESCRIPT -> JavaLanguage()

    EditorLanguage.PYTHON -> PythonLanguage()

    else -> EmptyLanguage()
}

private fun buildEditorColorScheme(colors: ColorScheme, dark: Boolean): EditorColorScheme {
    val keyword = if (dark) Color(0xFFC792EA) else Color(0xFF7C3AED)
    val string = if (dark) Color(0xFF9ECE6A) else Color(0xFF0A7D32)
    val comment = if (dark) Color(0xFF7C8899) else Color(0xFF6B7785)
    val operator = if (dark) Color(0xFF7DD3FC) else Color(0xFF0E7490)
    val function = if (dark) Color(0xFF82AAFF) else Color(0xFF2D6CDF)
    val annotation = if (dark) Color(0xFFFFCB6B) else Color(0xFFB45309)

    return EditorColorScheme().apply {
        setColor(EditorColorScheme.WHOLE_BACKGROUND, colors.background.toArgb())
        setColor(EditorColorScheme.TEXT_NORMAL, colors.onSurface.toArgb())

        setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.background.toArgb())
        setColor(EditorColorScheme.LINE_NUMBER, colors.onSurfaceVariant.copy(alpha = 0.75f).toArgb())
        setColor(EditorColorScheme.LINE_NUMBER_CURRENT, colors.primary.toArgb())
        setColor(EditorColorScheme.LINE_DIVIDER, colors.outlineVariant.copy(alpha = 0.5f).toArgb())

        setColor(EditorColorScheme.CURRENT_LINE, colors.primary.copy(alpha = if (dark) 0.10f else 0.05f).toArgb())
        setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, colors.primary.copy(alpha = 0.25f).toArgb())
        setColor(EditorColorScheme.SELECTION_HANDLE, colors.primary.toArgb())
        setColor(EditorColorScheme.SELECTION_INSERT, colors.primary.toArgb())
        setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, colors.tertiary.copy(alpha = 0.3f).toArgb())

        setColor(EditorColorScheme.KEYWORD, keyword.toArgb())
        setColor(EditorColorScheme.LITERAL, string.toArgb())
        setColor(EditorColorScheme.COMMENT, comment.toArgb())
        setColor(EditorColorScheme.OPERATOR, operator.toArgb())
        setColor(EditorColorScheme.FUNCTION_NAME, function.toArgb())
        setColor(EditorColorScheme.ANNOTATION, annotation.toArgb())
        setColor(EditorColorScheme.IDENTIFIER_NAME, colors.onSurface.toArgb())
        setColor(EditorColorScheme.IDENTIFIER_VAR, colors.onSurface.toArgb())
        setColor(EditorColorScheme.HTML_TAG, keyword.toArgb())
        setColor(EditorColorScheme.ATTRIBUTE_NAME, function.toArgb())
        setColor(EditorColorScheme.ATTRIBUTE_VALUE, string.toArgb())

        setColor(EditorColorScheme.SCROLL_BAR_THUMB, colors.outline.copy(alpha = 0.6f).toArgb())
        setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, colors.outline.toArgb())
        setColor(EditorColorScheme.SCROLL_BAR_TRACK, colors.background.toArgb())
        setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, colors.primary.toArgb())
        setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, Color.Transparent.toArgb())
        setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE, colors.primary.toArgb())
    }
}
