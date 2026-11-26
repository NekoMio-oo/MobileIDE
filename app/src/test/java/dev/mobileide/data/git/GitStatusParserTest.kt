package dev.mobileide.data.git

import dev.mobileide.domain.git.GitChangeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitStatusParserTest {

    private val oid = "a".repeat(40)

    @Test
    fun `parses branch headers and tracked changes`() {
        val output = buildString {
            append("# branch.oid $oid\u0000")
            append("# branch.head main\u0000")
            append("# branch.upstream origin/main\u0000")
            append("# branch.ab +2 -1\u0000")
            append("1 M. N... 100644 100644 100644 $oid $oid src/Main.kt\u0000")
            append("1 .M N... 100644 100644 100644 $oid $oid README.md\u0000")
            append("1 D. N... 100644 100644 100644 $oid $oid gone.txt\u0000")
            append("? notes with space.txt\u0000")
            append("! ignored.log\u0000")
        }

        val status = GitStatusParser.parse(output)

        assertEquals("main", status.branch)
        assertEquals(oid, status.headCommit)
        assertEquals("origin/main", status.upstream)
        assertEquals(2, status.ahead)
        assertEquals(1, status.behind)
        assertEquals(4, status.changes.size)
        assertEquals(listOf("src/Main.kt", "gone.txt"), status.staged.map { it.path })
        assertEquals(listOf("README.md"), status.unstaged.map { it.path })
        assertEquals(listOf("notes with space.txt"), status.untracked.map { it.path })
        assertEquals(GitChangeKind.MODIFIED, status.staged.first().indexChange)
        assertEquals(GitChangeKind.DELETED, status.staged.last().indexChange)
        assertFalse(status.truncated)
    }

    @Test
    fun `parses renamed entries with the second NUL path`() {
        val output = buildString {
            append("# branch.head main\u0000")
            append(
                "2 R. N... 100644 100644 100644 $oid $oid R100 new/Main.kt\u0000" +
                    "old/Main.kt\u0000"
            )
        }

        val status = GitStatusParser.parse(output)

        val change = status.staged.single()
        assertEquals("new/Main.kt", change.path)
        assertEquals("old/Main.kt", change.originalPath)
        assertEquals(GitChangeKind.RENAMED, change.displayChange)
        assertTrue(change.isStaged)
    }

    @Test
    fun `parses unmerged entries as conflicts`() {
        val output = buildString {
            append("# branch.head main\u0000")
            append(
                "u UU N... 100644 100644 100644 100644 $oid $oid $oid conflict.txt\u0000"
            )
        }

        val status = GitStatusParser.parse(output)

        val change = status.changes.single()
        assertTrue(change.conflicted)
        assertEquals(GitChangeKind.CONFLICTED, change.displayChange)
        assertFalse(change.isStaged)
        assertFalse(change.hasUnstaged)
        assertEquals(listOf("conflict.txt"), status.conflicted.map { it.path })
    }

    @Test
    fun `keeps a file that is staged and modified in both lists`() {
        val output = "1 MM N... 100644 100644 100644 $oid $oid both.txt\u0000"

        val status = GitStatusParser.parse(output)

        val change = status.changes.single()
        assertTrue(change.isStaged)
        assertTrue(change.hasUnstaged)
        assertEquals(GitChangeKind.MODIFIED, change.indexChange)
        assertEquals(GitChangeKind.MODIFIED, change.workTreeChange)
    }

    @Test
    fun `handles detached head and an unborn branch`() {
        val detached = GitStatusParser.parse(
            "# branch.oid $oid\u0000# branch.head (detached)\u0000"
        )
        assertTrue(detached.detached)
        assertNull(detached.branch)
        assertEquals(oid, detached.headCommit)

        val initial = GitStatusParser.parse(
            "# branch.oid (initial)\u0000# branch.head main\u0000"
        )
        assertFalse(initial.detached)
        assertEquals("main", initial.branch)
        assertNull(initial.headCommit)
    }

    @Test
    fun `drops an incomplete record when the output was truncated`() {
        val status = GitStatusParser.parse(
            "# branch.head main\u0000? partial-file",
            truncated = false
        )

        assertTrue(status.truncated)
        assertEquals("main", status.branch)
        assertTrue(status.changes.isEmpty())
    }

    @Test
    fun `ignores unknown records and keeps the truncated flag`() {
        val status = GitStatusParser.parse(
            "# branch.head main\u0000X unknown\u0000",
            truncated = true
        )

        assertEquals("main", status.branch)
        assertTrue(status.changes.isEmpty())
        assertTrue(status.truncated)
    }
}
