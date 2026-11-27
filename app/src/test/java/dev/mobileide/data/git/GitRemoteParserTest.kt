package dev.mobileide.data.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitRemoteParserTest {

    @Test
    fun `pairs fetch and push URLs`() {
        val output = buildString {
            append("origin\thttps://example.com/team/repo.git (fetch)\n")
            append("origin\thttps://example.com/team/repo.git (push)\n")
            append("backup\tgit@example.com:team/repo.git (fetch)\n")
            append("backup\tgit@example.com:backup/repo.git (push)\n")
        }

        val remotes = GitRemoteParser.parse(output)

        assertEquals(2, remotes.size)
        assertEquals("origin", remotes[0].name)
        assertNull(remotes[0].pushUrl)
        assertEquals("backup", remotes[1].name)
        assertEquals("git@example.com:backup/repo.git", remotes[1].pushUrl)
    }

    @Test
    fun `empty or malformed output yields no remotes`() {
        assertTrue(GitRemoteParser.parse("").isEmpty())
        assertTrue(GitRemoteParser.parse("not a remote line").isEmpty())
    }
}
