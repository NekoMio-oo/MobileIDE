package dev.mobileide.data.lsp

import dev.mobileide.domain.editor.EditorLanguage
import dev.mobileide.domain.lsp.LspDocumentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LspDocumentTrackerTest {

    private val key = LspDocumentKey(7L, "src/main.c")

    @Test
    fun `opening a document starts at version one`() {
        val tracker = LspDocumentTracker()
        val update = tracker.open(
            projectId = 7L,
            projectPath = "/storage/project",
            relativePath = "src/main.c",
            language = EditorLanguage.C,
            serverId = "clangd",
            languageId = "c",
            text = "int main() {}"
        )

        assertTrue(update.opened)
        assertFalse(update.changed)
        assertEquals(1, update.document.version)
        assertEquals("file:///workspace/src/main.c", update.document.uri)
    }

    @Test
    fun `reopening with the same text does not bump the version`() {
        val tracker = LspDocumentTracker()
        tracker.open(7L, "/p", "src/main.c", EditorLanguage.C, "clangd", "c", "int x;")
        val again = tracker.open(7L, "/p", "src/main.c", EditorLanguage.C, "clangd", "c", "int x;")

        assertFalse(again.opened)
        assertFalse(again.changed)
        assertEquals(1, again.document.version)
    }

    @Test
    fun `changing text advances the version monotonically`() {
        val tracker = LspDocumentTracker()
        tracker.open(7L, "/p", "src/main.c", EditorLanguage.C, "clangd", "c", "a")
        val first = tracker.change(key, "ab")!!
        val second = tracker.change(key, "abc")!!

        assertTrue(first.changed)
        assertTrue(second.changed)
        assertEquals(2, first.document.version)
        assertEquals(3, second.document.version)
    }

    @Test
    fun `identical text is not sent twice`() {
        val tracker = LspDocumentTracker()
        tracker.open(7L, "/p", "src/main.c", EditorLanguage.C, "clangd", "c", "a")
        val update = tracker.change(key, "a")!!

        assertFalse(update.changed)
        assertEquals(1, update.document.version)
    }

    @Test
    fun `documents over the limit are not synchronized`() {
        val tracker = LspDocumentTracker(maxSyncChars = 4)
        val open = tracker.open(7L, "/p", "src/main.c", EditorLanguage.C, "clangd", "c", "12345")
        assertTrue(open.skippedLarge)

        val changed = tracker.change(key, "123456")!!
        assertTrue(changed.skippedLarge)
        assertEquals(1, changed.document.version)
    }

    @Test
    fun `switching the serving server resets the document`() {
        val tracker = LspDocumentTracker()
        tracker.open(7L, "/p", "src/main.c", EditorLanguage.C, "clangd", "c", "a")
        tracker.change(key, "ab")
        val switched = tracker.open(7L, "/p", "src/main.c", EditorLanguage.C, "pylsp", "python", "ab")

        assertTrue(switched.opened)
        assertEquals(1, switched.document.version)
        assertEquals("pylsp", switched.document.serverId)
    }

    @Test
    fun `closing and removing projects drop the documents`() {
        val tracker = LspDocumentTracker()
        tracker.open(7L, "/p", "a.c", EditorLanguage.C, "clangd", "c", "a")
        tracker.open(7L, "/p", "b.c", EditorLanguage.C, "clangd", "c", "b")
        tracker.open(8L, "/q", "c.c", EditorLanguage.C, "clangd", "c", "c")

        assertEquals(2, tracker.documentsFor(7L, "clangd").size)
        assertNotNull(tracker.close(LspDocumentKey(7L, "a.c")))
        assertNull(tracker.get(LspDocumentKey(7L, "a.c")))

        assertEquals(1, tracker.removeProject(7L).size)
        assertEquals(0, tracker.documentsForProject(7L).size)
        assertEquals(1, tracker.allDocuments().size)
    }
}
