package dev.mobileide.data.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitBranchParserTest {

    @Test
    fun `parses local branches with sync state and gone upstream`() {
        val output = buildString {
            append("main\u001f*\u001fabc1234\u001forigin/main\u001f[ahead 2, behind 1]\n")
            append("feature\u001f \u001fdef5678\u001f\u001f\n")
            append("old\u001f \u001f1234567\u001forigin/old\u001f[gone]\n")
        }

        val branches = GitBranchParser.parseLocal(output)

        assertEquals(3, branches.size)
        assertTrue(branches[0].isCurrent)
        assertEquals("main", branches[0].name)
        assertEquals("origin/main", branches[0].upstream)
        assertEquals(2, branches[0].ahead)
        assertEquals(1, branches[0].behind)
        assertFalse(branches[1].isCurrent)
        assertNull(branches[1].upstream)
        assertEquals(0, branches[1].ahead)
        assertTrue(branches[2].upstreamGone)
    }

    @Test
    fun `parses remote branches and drops the symbolic HEAD`() {
        val output = buildString {
            append("origin/main\u001fabc1234\n")
            append("origin/HEAD\u001fdef5678\n")
            append("origin/feature\u001fghi9012\n")
        }

        val branches = GitBranchParser.parseRemote(output)

        assertEquals(2, branches.size)
        assertEquals("origin/main", branches[0].name)
        assertTrue(branches[0].isRemote)
        assertEquals("origin/feature", branches[1].name)
    }

    @Test
    fun `skips malformed lines and handles empty output`() {
        assertTrue(GitBranchParser.parseLocal("").isEmpty())
        assertTrue(GitBranchParser.parseRemote("").isEmpty())
        assertTrue(GitBranchParser.parseLocal("main\u001f*").isEmpty())
        assertTrue(GitBranchParser.parseRemote("origin/main").isEmpty())
    }
}
