package dev.mobileide.data.git

import dev.mobileide.domain.git.GitDiffLineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitDiffParserTest {

    @Test
    fun `parses context added and removed lines with correct gutter numbers`() {
        val output = """
            diff --git a/main.c b/main.c
            index 1111111..2222222 100644
            --- a/main.c
            +++ b/main.c
            @@ -1,3 +1,4 @@
             int main() {
            -    return 1;
            +    return 0;
            +    // ok
             }
        """.trimIndent()

        val diff = GitDiffParser.parse(output, "main.c")
        val file = diff.files.single()
        val hunk = file.hunks.single()

        assertEquals("main.c", file.path)
        assertEquals("main.c", file.oldPath)
        assertEquals(1, hunk.oldStart)
        assertEquals(3, hunk.oldCount)
        assertEquals(1, hunk.newStart)
        assertEquals(4, hunk.newCount)
        assertEquals(5, hunk.lines.size)
        assertEquals(listOf(1, null, 2, 3, 4), hunk.lines.map { it.newLine })
        assertEquals(listOf(1, 2, null, null, 3), hunk.lines.map { it.oldLine })
        assertEquals(GitDiffLineKind.CONTEXT, hunk.lines[0].kind)
        assertEquals(GitDiffLineKind.REMOVED, hunk.lines[1].kind)
        assertEquals(GitDiffLineKind.ADDED, hunk.lines[2].kind)
        assertEquals(GitDiffLineKind.ADDED, hunk.lines[3].kind)
        assertEquals(GitDiffLineKind.CONTEXT, hunk.lines[4].kind)
        assertEquals(2, diff.addedLines)
        assertEquals(1, diff.removedLines)
        assertEquals(2, file.addedLines)
        assertEquals(1, file.removedLines)
    }

    @Test
    fun `parses a new file with an omitted hunk count`() {
        val output = """
            diff --git a/new.txt b/new.txt
            new file mode 100644
            index 0000000..1234567
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            +hello
            +world
        """.trimIndent()

        val diff = GitDiffParser.parse(output, "new.txt")
        val file = diff.files.single()
        val hunk = file.hunks.single()

        assertNull(file.oldPath)
        assertEquals(2, hunk.newCount)
        assertEquals(2, file.addedLines)
    }

    @Test
    fun `detects binary diffs`() {
        val output = """
            diff --git a/image.png b/image.png
            index 1111111..2222222 100644
            Binary files a/image.png and b/image.png differ
        """.trimIndent()

        val diff = GitDiffParser.parse(output, "image.png")

        val file = diff.files.single()
        assertTrue(file.isBinary)
        assertTrue(file.hunks.isEmpty())
        assertEquals(0, diff.addedLines)
    }

    @Test
    fun `skips no newline markers without shifting line numbers`() {
        val output = """
            @@ -1 +1 @@
            -old
            \ No newline at end of file
            +new
            \ No newline at end of file
        """.trimIndent()

        val diff = GitDiffParser.parse(output, "file.txt")
        val hunk = diff.files.single().hunks.single()

        assertEquals(2, hunk.lines.size)
        assertEquals(1, hunk.lines[0].oldLine)
        assertNull(hunk.lines[0].newLine)
        assertNull(hunk.lines[1].oldLine)
        assertEquals(1, hunk.lines[1].newLine)
    }

    @Test
    fun `caps very large diffs`() {
        val output = buildString {
            append("@@ -1,6000 +1,6000 @@\n")
            repeat(6_000) { index ->
                append("+line ").append(index).append('\n')
            }
        }

        val diff = GitDiffParser.parse(output, "big.txt")

        assertTrue(diff.truncated)
        assertEquals(5_000, diff.files.single().hunks.single().lines.size)
    }

    @Test
    fun `empty output yields an empty file`() {
        val diff = GitDiffParser.parse("", "empty.txt")

        val file = diff.files.single()
        assertEquals("empty.txt", file.path)
        assertTrue(file.hunks.isEmpty())
        assertFalse(diff.hasContent)
        assertFalse(diff.truncated)
    }
}
