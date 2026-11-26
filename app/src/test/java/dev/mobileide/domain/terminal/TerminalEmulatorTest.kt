package dev.mobileide.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    private fun emulator(cols: Int = 20, rows: Int = 5) = TerminalEmulator(cols, rows)

    private fun TerminalEmulator.feedText(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `plain text and crlf advance rows`() {
        val terminal = emulator()
        terminal.feedText("hello\r\nworld")

        assertEquals("hello", terminal.rowText(0).trimEnd())
        assertEquals("world", terminal.rowText(1).trimEnd())
    }

    @Test
    fun `carriage return overwrites the line`() {
        val terminal = emulator()
        terminal.feedText("abcdef\rXY")

        assertEquals("XYcdef", terminal.rowText(0).trimEnd())
    }

    @Test
    fun `backspace and tab move the cursor`() {
        val terminal = emulator()
        terminal.feedText("abc\bZ")
        assertEquals("abZ", terminal.rowText(0).trimEnd())

        terminal.feedText("\r\tX")
        assertEquals('X', terminal.rowText(0)[8])
    }

    @Test
    fun `sgr colours are applied to the matching cells`() {
        val terminal = emulator()
        terminal.feedText("\u001b[31mred\u001b[0m plain")

        val firstLine = terminal.snapshot().lines[terminal.snapshot().liveStart]
        assertEquals("red", firstLine.first().text)
        assertEquals(1, firstLine.first().fg)
        assertTrue(firstLine.drop(1).any { it.text.contains("plain") && it.fg == -1 })
    }

    @Test
    fun `cursor addressing places text at the requested cell`() {
        val terminal = emulator()
        terminal.feedText("\u001b[2;3HX")

        assertEquals('X', terminal.rowText(1)[2])
    }

    @Test
    fun `erase display and line clear the buffer`() {
        val terminal = emulator()
        terminal.feedText("abc\ndef")
        terminal.feedText("\u001b[2J")

        assertTrue(terminal.rowText(0).isBlank())
        assertTrue(terminal.rowText(1).isBlank())

        terminal.feedText("\u001b[1;1Hhello\u001b[K")
        assertEquals("hello", terminal.rowText(0).trimEnd())

        terminal.feedText("\u001b[1;1H\u001b[K")
        assertEquals("", terminal.rowText(0).trimEnd())
    }

    @Test
    fun `delete characters shifts the rest of the line`() {
        val terminal = emulator()
        terminal.feedText("abcdef\u001b[1;2H\u001b[2P")

        assertEquals("adef", terminal.rowText(0).trimEnd())
    }

    @Test
    fun `scrolling pushes completed lines into scrollback`() {
        val terminal = emulator(cols = 20, rows = 5)
        repeat(10) { index -> terminal.feedText("line$index\n") }

        assertTrue(terminal.scrollbackSize() >= 4)
        val snapshot = terminal.snapshot()
        assertEquals(terminal.scrollbackSize(), snapshot.liveStart)
        assertEquals(terminal.scrollbackSize() + 5, snapshot.lines.size)
    }

    @Test
    fun `alternate screen hides and restores the main screen`() {
        val terminal = emulator()
        terminal.feedText("main")

        terminal.feedText("\u001b[?1049h")
        terminal.feedText("alt")
        assertEquals("alt", terminal.rowText(0).trimEnd())

        terminal.feedText("\u001b[?1049l")
        assertEquals("main", terminal.rowText(0).trimEnd())
    }

    @Test
    fun `cjk characters occupy two cells`() {
        val terminal = emulator()
        terminal.feedText("中")

        assertEquals('中', terminal.rowText(0)[0])
        val snapshot = terminal.snapshot()
        val runs = snapshot.lines[snapshot.liveStart]
        assertEquals("中", runs.first().text)
        assertEquals(2, snapshot.cursorColumn)
    }

    @Test
    fun `osc titles are consumed without printing`() {
        val terminal = emulator()
        terminal.feedText("\u001b]0;my title\u0007abc")

        assertEquals("abc", terminal.rowText(0).trimEnd())
    }

    @Test
    fun `device status report answers with the cursor position`() {
        val terminal = emulator()
        val responses = mutableListOf<String>()
        terminal.onResponse = { responses += String(it) }

        terminal.feedText("\u001b[3;4H\u001b[6n")

        assertEquals(listOf("\u001b[3;4R"), responses)
    }

    @Test
    fun `resize keeps existing content`() {
        val terminal = emulator(cols = 10, rows = 3)
        terminal.feedText("abc")
        terminal.resize(30, 6)

        assertEquals("abc", terminal.rowText(0).trimEnd())
        assertEquals(30, terminal.columnCount())
        assertEquals(6, terminal.rowCount())
    }

    @Test
    fun `wide character widths cover cjk and ascii`() {
        assertEquals(1, TerminalEmulator.wcwidth('a'.code))
        assertEquals(2, TerminalEmulator.wcwidth('中'.code))
        assertEquals(2, TerminalEmulator.wcwidth('한'.code))
        assertEquals(0, TerminalEmulator.wcwidth(0x0301))
    }

    @Test
    fun `snapshot revision changes after every feed`() {
        val terminal = emulator()
        val before = terminal.snapshot().revision
        terminal.feedText("x")
        assertTrue(terminal.snapshot().revision > before)
    }

    @Test
    fun `cursor is drawn as an inverse cell`() {
        val terminal = emulator()
        terminal.feedText("a")
        val snapshot = terminal.snapshot()
        val runs = snapshot.lines[snapshot.liveStart]
        assertTrue(snapshot.cursorVisible)
        assertTrue(runs.any { it.inverse })
        assertFalse(snapshot.lines.isEmpty())
    }
}
