package dev.mobileide.data.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitProgressParserTest {

    @Test
    fun `parses percentage progress`() {
        assertEquals(
            0.42f,
            GitProgressParser.parse("Receiving objects:  42% (123/456), 1.2 MiB")
        )
        assertEquals(
            1f,
            GitProgressParser.parse("Resolving deltas: 100% (10/10), done.")
        )
    }

    @Test
    fun `returns null when no percentage is present`() {
        assertNull(GitProgressParser.parse("Enumerating objects: 12, done."))
        assertNull(GitProgressParser.parse(""))
    }
}
