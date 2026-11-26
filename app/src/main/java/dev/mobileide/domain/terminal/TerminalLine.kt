package dev.mobileide.domain.terminal

/** Attribute bits stored per cell; keeps the screen buffer compact. */
object CellFlags {
    const val BOLD = 1
    const val UNDERLINE = 2
    const val INVERSE = 4
    const val WIDE = 8

    const val DEFAULT_FG = -1
    const val DEFAULT_BG = -1
}

/**
 * One screen row. Parallel arrays instead of an object per cell: a 60×40 screen
 * plus 2000 scrollback lines stays small and allocation-free while it scrolls.
 */
class TerminalLine(cols: Int) {
    var cols: Int = cols
        private set
    var text: CharArray = CharArray(cols) { ' ' }
        private set
    var fg: IntArray = IntArray(cols) { CellFlags.DEFAULT_FG }
        private set
    var bg: IntArray = IntArray(cols) { CellFlags.DEFAULT_BG }
        private set
    var flags: ByteArray = ByteArray(cols)
        private set

    fun resize(newCols: Int) {
        if (newCols == cols) return
        text = text.copyOf(newCols).also { copy ->
            for (index in cols until newCols) copy[index] = ' '
        }
        fg = fg.copyOf(newCols)
        bg = bg.copyOf(newCols)
        flags = flags.copyOf(newCols)
        if (newCols > cols) {
            for (index in cols until newCols) fg[index] = CellFlags.DEFAULT_FG
            for (index in cols until newCols) bg[index] = CellFlags.DEFAULT_BG
        }
        cols = newCols
    }

    fun clear(from: Int = 0, to: Int = cols) {
        val end = to.coerceAtMost(cols)
        for (index in from.coerceAtLeast(0) until end) {
            text[index] = ' '
            fg[index] = CellFlags.DEFAULT_FG
            bg[index] = CellFlags.DEFAULT_BG
            flags[index] = 0
        }
    }

    fun copyFrom(other: TerminalLine) {
        if (cols != other.cols) resize(other.cols)
        other.text.copyInto(text)
        other.fg.copyInto(fg)
        other.bg.copyInto(bg)
        other.flags.copyInto(flags)
    }

    fun duplicate(): TerminalLine {
        val line = TerminalLine(cols)
        line.copyFrom(this)
        return line
    }

    fun isBlank(): Boolean {
        for (index in 0 until cols) {
            if (text[index] != ' ' || flags[index].toInt() != 0) return false
        }
        return true
    }

    fun textString(): String = String(text)
}

/** A run of cells that share one style, ready for AnnotatedString rendering. */
data class TerminalRun(
    val text: String,
    val fg: Int,
    val bg: Int,
    val bold: Boolean,
    val underline: Boolean,
    val inverse: Boolean
)

data class TerminalSnapshot(
    val lines: List<List<TerminalRun>>,
    /** Index in [lines] where the live screen starts (scrollback is before it). */
    val liveStart: Int,
    val cursorLine: Int,
    val cursorColumn: Int,
    val cursorVisible: Boolean,
    val cols: Int,
    val rows: Int,
    val revision: Long
)
