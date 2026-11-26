package dev.mobileide.feature.terminal

import androidx.compose.ui.graphics.Color

/**
 * xterm-256 palette with light/dark variants. Index -1 means "default", which
 * the UI renders with the theme's surface/onSurface colours.
 */
class TerminalPalette(dark: Boolean) {

    private val base: IntArray = if (dark) DARK else LIGHT

    fun color(index: Int): Color? = when {
        index < 0 -> null
        index < 16 -> Color(base[index])
        index < 232 -> Color(cube(index - 16))
        index < 256 -> Color(gray(index - 232))
        else -> null
    }

    private fun cube(value: Int): Int {
        val r = value / 36
        val g = (value % 36) / 6
        val b = value % 6
        fun level(component: Int) = if (component == 0) 0 else 55 + 40 * component
        return argb(level(r), level(g), level(b))
    }

    private fun gray(value: Int): Int {
        val level = 8 + 10 * value
        return argb(level, level, level)
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b

    private companion object {
        val DARK = intArrayOf(
            0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFECEFF4.toInt(),
            0xFF6C7A96.toInt(), 0xFFD08770.toInt(), 0xFFB7CFA4.toInt(), 0xFFF0D9A0.toInt(),
            0xFF94B3D1.toInt(), 0xFFC9A6C4.toInt(), 0xFF9FD3DF.toInt(), 0xFFFFFFFF.toInt()
        )

        val LIGHT = intArrayOf(
            0xFF24292F.toInt(), 0xFFCF222E.toInt(), 0xFF116329.toInt(), 0xFF7A5C00.toInt(),
            0xFF0550AE.toInt(), 0xFF8250DF.toInt(), 0xFF1B7C83.toInt(), 0xFF6E7781.toInt(),
            0xFF57606A.toInt(), 0xFFA40E26.toInt(), 0xFF1A7F37.toInt(), 0xFF9A6700.toInt(),
            0xFF0969DA.toInt(), 0xFFA475F9.toInt(), 0xFF3192AA.toInt(), 0xFF8C959F.toInt()
        )
    }
}
