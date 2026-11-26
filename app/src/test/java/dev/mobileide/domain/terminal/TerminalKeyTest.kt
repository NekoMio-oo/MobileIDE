package dev.mobileide.domain.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalKeyTest {

    @Test
    fun `control keys send the terminal control codes`() {
        assertArrayEquals(byteArrayOf(0x03), TerminalKey.CTRL_C.bytes())
        assertArrayEquals(byteArrayOf(0x04), TerminalKey.CTRL_D.bytes())
        assertArrayEquals(byteArrayOf(0x0C), TerminalKey.CTRL_L.bytes())
        assertArrayEquals(byteArrayOf(0x1A), TerminalKey.CTRL_Z.bytes())
        assertArrayEquals(byteArrayOf(0x1B), TerminalKey.ESCAPE.bytes())
        assertArrayEquals(byteArrayOf(0x09), TerminalKey.TAB.bytes())
        assertArrayEquals(byteArrayOf(0x0D), TerminalKey.ENTER.bytes())
    }

    @Test
    fun `arrows use the ansi cursor sequences`() {
        assertEquals("\u001b[A", String(TerminalKey.ARROW_UP.bytes()))
        assertEquals("\u001b[B", String(TerminalKey.ARROW_DOWN.bytes()))
        assertEquals("\u001b[C", String(TerminalKey.ARROW_RIGHT.bytes()))
        assertEquals("\u001b[D", String(TerminalKey.ARROW_LEFT.bytes()))
    }

    @Test
    fun `plain characters are sent literally`() {
        assertEquals("/", String(TerminalKey.SLASH.bytes()))
        assertEquals("-", String(TerminalKey.DASH.bytes()))
    }
}
