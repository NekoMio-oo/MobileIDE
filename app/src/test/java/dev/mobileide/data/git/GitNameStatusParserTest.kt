package dev.mobileide.data.git

import dev.mobileide.domain.git.GitChangeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitNameStatusParserTest {

    @Test
    fun `parses ordinary and rename records`() {
        val output = buildString {
            append("A\u0000a.txt\u0000")
            append("M\u0000b.txt\u0000")
            append("D\u0000c.txt\u0000")
            append("R100\u0000old.txt\u0000new.txt\u0000")
            append("C75\u0000src.txt\u0000copy.txt\u0000")
        }

        val result = GitNameStatusParser.parse(output)

        assertFalse(result.truncated)
        assertEquals(5, result.files.size)
        assertEquals(GitChangeKind.ADDED, result.files[0].change)
        assertEquals("a.txt", result.files[0].path)
        assertEquals(GitChangeKind.MODIFIED, result.files[1].change)
        assertEquals(GitChangeKind.DELETED, result.files[2].change)
        assertEquals(GitChangeKind.RENAMED, result.files[3].change)
        assertEquals("old.txt", result.files[3].oldPath)
        assertEquals("new.txt", result.files[3].path)
        assertEquals(GitChangeKind.COPIED, result.files[4].change)
        assertEquals("src.txt", result.files[4].oldPath)
        assertEquals("copy.txt", result.files[4].path)
    }

    @Test
    fun `detects a truncated final record`() {
        val result = GitNameStatusParser.parse("A\u0000a.txt\u0000M\u0000partial")

        assertTrue(result.truncated)
        assertEquals(1, result.files.size)
        assertEquals("a.txt", result.files[0].path)
    }

    @Test
    fun `caps the number of files`() {
        val output = buildString {
            repeat(5) { index ->
                append("A\u0000file").append(index).append(".txt\u0000")
            }
        }

        val result = GitNameStatusParser.parse(output, limit = 2)

        assertTrue(result.truncated)
        assertEquals(2, result.files.size)
    }
}
