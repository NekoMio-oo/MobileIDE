package dev.mobileide.data.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitLogParserTest {

    @Test
    fun `parses commits refs and parents`() {
        val output = buildString {
            append(
                "hash1\u001fabc1234\u001fAlice\u001falice@example.com" +
                    "\u001f2026-11-27T10:00:00+08:00\u001fInitial commit" +
                    "\u001fHEAD -> main, origin/main\u001f\u001e\n"
            )
            append(
                "hash2\u001fdef5678\u001fBob\u001fbob@example.com" +
                    "\u001f2026-11-27T11:00:00+08:00\u001fMerge branch" +
                    "\u001f\u001fhash1 hash3\u001e"
            )
        }

        val commits = GitLogParser.parse(output)

        assertEquals(2, commits.size)
        assertEquals("hash1", commits[0].hash)
        assertEquals("abc1234", commits[0].shortHash)
        assertEquals("Alice", commits[0].authorName)
        assertEquals("alice@example.com", commits[0].authorEmail)
        assertEquals("Initial commit", commits[0].subject)
        assertEquals(listOf("HEAD -> main", "origin/main"), commits[0].refs)
        assertTrue(commits[0].parents.isEmpty())
        assertFalse(commits[0].isMerge)

        assertEquals(listOf("hash1", "hash3"), commits[1].parents)
        assertTrue(commits[1].isMerge)
        assertTrue(commits[1].refs.isEmpty())
    }

    @Test
    fun `skips records without all fields`() {
        val output = "hash1\u001fabc\u001fAlice\u001f\u001e" +
            "hash2\u001fdef\u001fBob\u001fbob@example.com" +
            "\u001f2026-11-27T10:00:00+08:00\u001fSubject\u001f\u001fparent\u001e"

        val commits = GitLogParser.parse(output)

        assertEquals(1, commits.size)
        assertEquals("hash2", commits[0].hash)
    }

    @Test
    fun `empty output yields no commits`() {
        assertTrue(GitLogParser.parse("").isEmpty())
        assertNull(GitLogParser.parseSingle(""))
    }
}
