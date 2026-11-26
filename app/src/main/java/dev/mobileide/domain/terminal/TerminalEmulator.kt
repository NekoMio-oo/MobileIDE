package dev.mobileide.domain.terminal

/**
 * VT100/ANSI terminal emulator: a real screen buffer fed by raw PTY bytes.
 *
 * Supported: cursor addressing and movement, erase (display/line/chars), insert
 * and delete of characters/lines, scroll regions, SGR colours (16/256/24-bit
 * mapped to the xterm palette), alternate screen, saved cursor, DSR/DA replies.
 * Not implemented: mouse reporting, Sixel, DEC line drawing, non-BMP glyphs.
 */
class TerminalEmulator(
    cols: Int = 80,
    rows: Int = 24,
    private val scrollbackLimit: Int = DEFAULT_SCROLLBACK
) {

    private var cols = cols.coerceAtLeast(1)
    private var rows = rows.coerceAtLeast(1)

    private var screen = Array(this.rows) { TerminalLine(this.cols) }
    private var altBuffer = Array(this.rows) { TerminalLine(this.cols) }
    private var usingAlt = false
    private val scrollback = ArrayDeque<TerminalLine>()

    private var cursorRow = 0
    private var cursorCol = 0
    private var savedRow = 0
    private var savedCol = 0
    private var cursorVisible = true
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    private var fg = CellFlags.DEFAULT_FG
    private var bg = CellFlags.DEFAULT_BG
    private var attributes = 0

    private var state = State.GROUND
    private val params = StringBuilder()

    private var utf8Remaining = 0
    private var utf8CodePoint = 0

    private var revision = 0L

    /** Raw replies (cursor position report, device attributes) for the PTY. */
    var onResponse: ((ByteArray) -> Unit)? = null

    // ---- Public API -------------------------------------------------------

    fun feed(bytes: ByteArray) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            when (state) {
                State.GROUND -> handleGround(value)
                State.ESCAPE -> handleEscape(value)
                State.CSI -> handleCsi(value)
                State.OSC -> if (value == 0x07) state = State.GROUND
                State.CHARSET -> state = State.GROUND
            }
        }
        revision++
    }

    fun resize(newCols: Int, newRows: Int) {
        val targetCols = newCols.coerceAtLeast(1)
        val targetRows = newRows.coerceAtLeast(1)
        if (targetCols == cols && targetRows == rows) return

        screen = Array(targetRows) { index ->
            if (index < rows) {
                screen[index].also { it.resize(targetCols) }
            } else {
                TerminalLine(targetCols)
            }
        }
        altBuffer = Array(targetRows) { index ->
            if (index < rows) {
                altBuffer[index].also { it.resize(targetCols) }
            } else {
                TerminalLine(targetCols)
            }
        }
        cols = targetCols
        rows = targetRows
        scrollback.forEach { it.resize(targetCols) }
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        scrollTop = 0
        scrollBottom = rows - 1
        revision++
    }

    fun columnCount(): Int = cols

    fun rowCount(): Int = rows

    fun rowText(row: Int): String = currentScreen().getOrNull(row)?.textString() ?: ""

    fun scrollbackSize(): Int = scrollback.size

    fun snapshot(): TerminalSnapshot {
        val lines = ArrayList<List<TerminalRun>>(scrollback.size + rows)
        scrollback.forEach { lines += runsFor(it, -1) }
        val liveStart = lines.size
        for (row in 0 until rows) {
            lines += runsFor(screen[row], row)
        }
        return TerminalSnapshot(
            lines = lines,
            liveStart = liveStart,
            cursorLine = liveStart + cursorRow,
            cursorColumn = cursorCol,
            cursorVisible = cursorVisible && cursorCol < cols,
            cols = cols,
            rows = rows,
            revision = revision
        )
    }

    // ---- Input handling ---------------------------------------------------

    private fun handleGround(value: Int) {
        if (utf8Remaining > 0) {
            if (value and 0xC0 == 0x80) {
                utf8CodePoint = (utf8CodePoint shl 6) or (value and 0x3F)
                utf8Remaining--
                if (utf8Remaining == 0) emitCodePoint(utf8CodePoint)
            } else {
                utf8Remaining = 0
            }
            return
        }
        when {
            value == 0x1B -> state = State.ESCAPE
            value == 0x0D -> cursorCol = 0
            value == 0x0A || value == 0x0B || value == 0x0C -> lineFeed()
            value == 0x08 -> if (cursorCol > 0) cursorCol--
            value == 0x09 -> cursorCol = ((cursorCol / 8) + 1) * 8
            value < 0x20 -> Unit
            value < 0x80 -> putChar(value.toChar(), 1)
            value and 0xE0 == 0xC0 -> {
                utf8CodePoint = value and 0x1F
                utf8Remaining = 1
            }

            value and 0xF0 == 0xE0 -> {
                utf8CodePoint = value and 0x0F
                utf8Remaining = 2
            }

            value and 0xF8 == 0xF0 -> {
                utf8CodePoint = value and 0x07
                utf8Remaining = 3
            }

            else -> Unit
        }
    }

    private fun handleEscape(value: Int) {
        state = State.GROUND
        when (value.toChar()) {
            '[' -> {
                state = State.CSI
                params.setLength(0)
            }

            ']' -> state = State.OSC
            'P', '^', '_' -> state = State.OSC
            '(', ')', '*', '+' -> state = State.CHARSET
            '7' -> {
                savedRow = cursorRow
                savedCol = cursorCol
            }

            '8' -> {
                cursorRow = savedRow.coerceIn(0, rows - 1)
                cursorCol = savedCol.coerceIn(0, cols - 1)
            }

            'D' -> lineFeed()
            'M' -> reverseIndex()
            'E' -> {
                cursorCol = 0
                lineFeed()
            }

            'c' -> reset()
            else -> Unit
        }
    }

    private fun handleCsi(value: Int) {
        when {
            value in 0x30..0x3F -> params.append(value.toChar())
            value in 0x20..0x2F -> Unit
            value in 0x40..0x7E -> {
                state = State.GROUND
                dispatchCsi(value.toChar())
            }

            else -> state = State.GROUND
        }
    }

    private fun dispatchCsi(final: Char) {
        val raw = params.toString()
        val private = raw.startsWith("?") || raw.startsWith(">")
        val body = if (private) raw.substring(1) else raw
        val numbers = body.split(';').map { it.toIntOrNull() ?: 0 }
        fun arg(index: Int, default: Int = 1): Int =
            numbers.getOrNull(index)?.takeIf { it > 0 } ?: default

        when (final) {
            '@' -> insertChars(arg(0))
            'A' -> cursorRow = (cursorRow - arg(0)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + arg(0)).coerceAtMost(rows - 1)
            'C' -> cursorCol = (cursorCol + arg(0)).coerceAtMost(cols - 1)
            'D' -> cursorCol = (cursorCol - arg(0)).coerceAtLeast(0)
            'E' -> {
                cursorRow = (cursorRow + arg(0)).coerceAtMost(rows - 1)
                cursorCol = 0
            }

            'F' -> {
                cursorRow = (cursorRow - arg(0)).coerceAtLeast(0)
                cursorCol = 0
            }

            'G', '`' -> cursorCol = (arg(0) - 1).coerceIn(0, cols - 1)
            'd' -> cursorRow = (arg(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                cursorRow = (arg(0) - 1).coerceIn(0, rows - 1)
                cursorCol = (arg(1) - 1).coerceIn(0, cols - 1)
            }

            'J' -> eraseDisplay(numbers.getOrNull(0) ?: 0)
            'K' -> eraseLine(numbers.getOrNull(0) ?: 0)
            'L' -> insertLines(arg(0))
            'M' -> deleteLines(arg(0))
            'P' -> deleteChars(arg(0))
            'S' -> scrollUp(arg(0))
            'T' -> scrollDown(arg(0))
            'X' -> eraseChars(arg(0))
            'm' -> applySgr(numbers)
            'h' -> setMode(private, numbers, true)
            'l' -> setMode(private, numbers, false)
            'n' -> deviceStatus(numbers)
            'c' -> if (numbers.firstOrNull() == 0) respond("\u001b[?1;2c")
            'r' -> setScrollRegion(numbers)
            's' -> {
                savedRow = cursorRow
                savedCol = cursorCol
            }

            'u' -> {
                cursorRow = savedRow.coerceIn(0, rows - 1)
                cursorCol = savedCol.coerceIn(0, cols - 1)
            }

            'e' -> cursorRow = (cursorRow + arg(0)).coerceAtMost(rows - 1)
            else -> Unit
        }
    }

    // ---- Screen operations ------------------------------------------------

    private fun putChar(char: Char, width: Int) {
        if (width <= 0) return
        if (cursorCol + width > cols) {
            cursorCol = 0
            lineFeed()
        }
        val line = currentScreen()[cursorRow]
        line.text[cursorCol] = char
        line.fg[cursorCol] = fg
        line.bg[cursorCol] = bg
        line.flags[cursorCol] = attributes.toByte()
        if (width == 2 && cursorCol + 1 < cols) {
            line.text[cursorCol + 1] = WIDE_CONTINUATION
            line.fg[cursorCol + 1] = fg
            line.bg[cursorCol + 1] = bg
            line.flags[cursorCol + 1] = (attributes or CellFlags.WIDE).toByte()
        }
        cursorCol += width
    }

    private fun emitCodePoint(codePoint: Int) {
        if (codePoint < 0x20) return
        if (codePoint > 0xFFFF) {
            // Surrogates do not fit the one-char-per-cell grid.
            putChar('?', 1)
            return
        }
        putChar(codePoint.toChar(), wcwidth(codePoint))
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun reverseIndex() {
        if (cursorRow == scrollTop) {
            scrollDown(1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    private fun scrollUp(count: Int) {
        repeat(count.coerceAtLeast(1)) {
            val top = scrollTop.coerceIn(0, rows - 1)
            val bottom = scrollBottom.coerceIn(top, rows - 1)
            if (top == 0 && !usingAlt) {
                pushScrollback(screen[0])
            }
            for (row in top until bottom) {
                screen[row].copyFrom(screen[row + 1])
            }
            screen[bottom].clear()
        }
    }

    private fun scrollDown(count: Int) {
        repeat(count.coerceAtLeast(1)) {
            val top = scrollTop.coerceIn(0, rows - 1)
            val bottom = scrollBottom.coerceIn(top, rows - 1)
            for (row in bottom downTo top + 1) {
                screen[row].copyFrom(screen[row - 1])
            }
            screen[top].clear()
        }
    }

    private fun pushScrollback(line: TerminalLine) {
        scrollback.addLast(line.duplicate())
        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (row in cursorRow + 1 until rows) screen[row].clear()
            }

            1 -> {
                eraseLine(1)
                for (row in 0 until cursorRow) screen[row].clear()
            }

            2 -> screen.forEach { it.clear() }
            3 -> scrollback.clear()
        }
    }

    private fun eraseLine(mode: Int) {
        val line = currentScreen()[cursorRow]
        when (mode) {
            0 -> line.clear(cursorCol)
            1 -> line.clear(0, cursorCol + 1)
            2 -> line.clear()
        }
    }

    private fun eraseChars(count: Int) {
        currentScreen()[cursorRow].clear(cursorCol, (cursorCol + count).coerceAtMost(cols))
    }

    private fun insertChars(count: Int) {
        val line = currentScreen()[cursorRow]
        val amount = count.coerceAtMost(cols - cursorCol)
        for (index in cols - 1 downTo cursorCol + amount) {
            line.text[index] = line.text[index - amount]
            line.fg[index] = line.fg[index - amount]
            line.bg[index] = line.bg[index - amount]
            line.flags[index] = line.flags[index - amount]
        }
        line.clear(cursorCol, cursorCol + amount)
    }

    private fun deleteChars(count: Int) {
        val line = currentScreen()[cursorRow]
        val amount = count.coerceAtMost(cols - cursorCol)
        for (index in cursorCol until cols - amount) {
            line.text[index] = line.text[index + amount]
            line.fg[index] = line.fg[index + amount]
            line.bg[index] = line.bg[index + amount]
            line.flags[index] = line.flags[index + amount]
        }
        line.clear(cols - amount, cols)
    }

    private fun insertLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(count.coerceAtMost(rows)) {
            for (row in scrollBottom downTo cursorRow + 1) {
                screen[row].copyFrom(screen[row - 1])
            }
            screen[cursorRow].clear()
        }
    }

    private fun deleteLines(count: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        repeat(count.coerceAtMost(rows)) {
            for (row in cursorRow until scrollBottom) {
                screen[row].copyFrom(screen[row + 1])
            }
            screen[scrollBottom].clear()
        }
    }

    private fun setScrollRegion(numbers: List<Int>) {
        val top = (numbers.getOrNull(0)?.takeIf { it > 0 } ?: 1) - 1
        val bottom = (numbers.getOrNull(1)?.takeIf { it > 0 } ?: rows) - 1
        if (top >= bottom) return
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(0, rows - 1)
        cursorRow = scrollTop
        cursorCol = 0
    }

    private fun setMode(private: Boolean, numbers: List<Int>, enabled: Boolean) {
        if (!private) return
        numbers.forEach { mode ->
            when (mode) {
                25 -> cursorVisible = enabled
                47, 1047, 1049 -> switchAltScreen(enabled)
                else -> Unit
            }
        }
    }

    private fun switchAltScreen(enabled: Boolean) {
        if (enabled == usingAlt) return
        if (enabled) {
            savedRow = cursorRow
            savedCol = cursorCol
            altBuffer.forEach { it.clear() }
            usingAlt = true
            cursorRow = 0
            cursorCol = 0
        } else {
            usingAlt = false
            cursorRow = savedRow.coerceIn(0, rows - 1)
            cursorCol = savedCol.coerceIn(0, cols - 1)
        }
    }

    private fun deviceStatus(numbers: List<Int>) {
        when (numbers.firstOrNull()) {
            5 -> respond("\u001b[0n")
            6 -> respond("\u001b[${cursorRow + 1};${cursorCol + 1}R")
        }
    }

    private fun respond(text: String) {
        onResponse?.invoke(text.toByteArray(Charsets.UTF_8))
    }

    private fun reset() {
        screen.forEach { it.clear() }
        altBuffer.forEach { it.clear() }
        scrollback.clear()
        usingAlt = false
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        fg = CellFlags.DEFAULT_FG
        bg = CellFlags.DEFAULT_BG
        attributes = 0
        cursorVisible = true
        state = State.GROUND
    }

    // ---- SGR --------------------------------------------------------------

    private fun applySgr(numbers: List<Int>) {
        if (numbers.isEmpty()) {
            resetAttributes()
            return
        }
        var index = 0
        while (index < numbers.size) {
            when (val code = numbers[index]) {
                0 -> resetAttributes()
                1 -> attributes = attributes or CellFlags.BOLD
                4 -> attributes = attributes or CellFlags.UNDERLINE
                7 -> attributes = attributes or CellFlags.INVERSE
                22 -> attributes = attributes and CellFlags.BOLD.inv()
                24 -> attributes = attributes and CellFlags.UNDERLINE.inv()
                27 -> attributes = attributes and CellFlags.INVERSE.inv()
                39 -> fg = CellFlags.DEFAULT_FG
                49 -> bg = CellFlags.DEFAULT_BG
                in 30..37 -> fg = code - 30
                in 90..97 -> fg = code - 90 + 8
                in 40..47 -> bg = code - 40
                in 100..107 -> bg = code - 100 + 8
                38, 48 -> {
                    val isForeground = code == 38
                    val consumed = when (numbers.getOrNull(index + 1)) {
                        5 -> {
                            val color = (numbers.getOrNull(index + 2) ?: 0).coerceIn(0, 255)
                            if (isForeground) fg = color else bg = color
                            2
                        }

                        2 -> {
                            val color = rgbToPalette(
                                numbers.getOrNull(index + 2) ?: 0,
                                numbers.getOrNull(index + 3) ?: 0,
                                numbers.getOrNull(index + 4) ?: 0
                            )
                            if (isForeground) fg = color else bg = color
                            4
                        }

                        else -> 0
                    }
                    index += consumed
                }

                else -> Unit
            }
            index++
        }
    }

    private fun resetAttributes() {
        fg = CellFlags.DEFAULT_FG
        bg = CellFlags.DEFAULT_BG
        attributes = 0
    }

    // ---- Rendering --------------------------------------------------------

    private fun currentScreen(): Array<TerminalLine> = if (usingAlt) altBuffer else screen

    private fun runsFor(line: TerminalLine, row: Int): List<TerminalRun> {
        val runs = ArrayList<TerminalRun>(line.cols / 4 + 1)
        var startCol = 0
        var current = styleAt(line, 0, row)
        var text = StringBuilder()
        for (col in 0 until line.cols) {
            val style = styleAt(line, col, row)
            if (style.currentStyle != current.currentStyle) {
                runs += current.toRun(text.toString())
                current = style
                text = StringBuilder()
            }
            text.append(style.text)
        }
        runs += current.toRun(text.toString())
        return runs
    }

    private data class CellStyle(
        val text: String,
        val fg: Int,
        val bg: Int,
        val bold: Boolean,
        val underline: Boolean,
        val inverse: Boolean
    ) {
        val currentStyle: StyleKey get() = StyleKey(fg, bg, bold, underline, inverse)

        fun toRun(value: String) = TerminalRun(
            text = value,
            fg = fg,
            bg = bg,
            bold = bold,
            underline = underline,
            inverse = inverse
        )
    }

    private data class StyleKey(
        val fg: Int,
        val bg: Int,
        val bold: Boolean,
        val underline: Boolean,
        val inverse: Boolean
    )

    private fun styleAt(line: TerminalLine, col: Int, row: Int): CellStyle {
        val isCursor = cursorVisible && row == cursorRow && col == cursorCol
        var flags = line.flags[col].toInt()
        if (isCursor) flags = flags xor CellFlags.INVERSE
        val char = line.text[col]
        val text = if (char == WIDE_CONTINUATION) "" else char.toString()
        return CellStyle(
            text = text,
            fg = line.fg[col],
            bg = line.bg[col],
            bold = flags and CellFlags.BOLD != 0,
            underline = flags and CellFlags.UNDERLINE != 0,
            inverse = flags and CellFlags.INVERSE != 0
        )
    }

    private enum class State {
        GROUND,
        ESCAPE,
        CSI,
        OSC,
        CHARSET
    }

    companion object {
        const val DEFAULT_SCROLLBACK = 2_000
        const val WIDE_CONTINUATION = '\u0000'

        /** Enough of East Asian width to keep CJK output aligned. */
        fun wcwidth(codePoint: Int): Int = when {
            codePoint == 0 -> 0
            codePoint < 32 || (codePoint in 0x7F..0x9F) -> 0
            codePoint in 0x0300..0x036F -> 0
            codePoint in 0x1100..0x115F -> 2
            codePoint == 0x2329 || codePoint == 0x232A -> 2
            codePoint in 0x2E80..0xA4CF && codePoint != 0x303F -> 2
            codePoint in 0xAC00..0xD7A3 -> 2
            codePoint in 0xF900..0xFAFF -> 2
            codePoint in 0xFE10..0xFE19 -> 2
            codePoint in 0xFE30..0xFE6F -> 2
            codePoint in 0xFF00..0xFF60 -> 2
            codePoint in 0xFFE0..0xFFE6 -> 2
            codePoint in 0x1F300..0x1F64F -> 2
            codePoint in 0x1F900..0x1F9FF -> 2
            codePoint in 0x20000..0x3FFFD -> 2
            else -> 1
        }

        /** Maps a 24-bit colour onto the xterm-256 cube so rendering stays bounded. */
        fun rgbToPalette(r: Int, g: Int, b: Int): Int {
            fun level(value: Int): Int {
                val index = ((value.coerceIn(0, 255) - 35) / 40)
                return index.coerceIn(0, 5)
            }

            return 16 + 36 * level(r) + 6 * level(g) + level(b)
        }
    }
}
