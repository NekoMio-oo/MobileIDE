package dev.mobileide.data.editor

import dev.mobileide.domain.editor.EditorSessionStore
import dev.mobileide.domain.editor.EditorSnapshot
import dev.mobileide.domain.editor.EditorTabStatus
import dev.mobileide.domain.editor.OpenTabResult
import dev.mobileide.domain.editor.PersistedEditorSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditorTabManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val root = "/storage/emulated/0/AndroidProjects/demo"

    private fun manager(store: EditorSessionStore = InMemorySessionStore()) =
        EditorTabManager(store)

    private fun EditorTabManager.openTab(
        projectId: Long = 1L,
        relativePath: String,
        text: String = "",
        modified: Long = 1L
    ): String {
        val relative = relativePath
        open(projectId, root, relative, relative.substringAfterLast('/'))
        val id = activeTab(projectId)!!.id
        onLoaded(projectId, id, text, "\n", modified, text.length.toLong())
        return id
    }

    @Test
    fun `opening the same file reuses its tab`() = runTest {
        val manager = manager()
        manager.restore(1L, root)

        val first = manager.open(1L, root, "src/Main.kt", "Main.kt")
        val second = manager.open(1L, root, "src/Main.kt", "Main.kt")

        assertTrue(first is OpenTabResult.Created)
        assertTrue(second is OpenTabResult.AlreadyOpen)
        assertEquals(1, manager.tabs(1L).size)
        assertEquals("$root/src/Main.kt", manager.activeTab(1L)?.id)
    }

    @Test
    fun `each tab keeps its own text and dirty flag`() = runTest {
        val manager = manager()
        manager.restore(1L, root)
        val a = manager.openTab(relativePath = "a.txt", text = "hello a")
        val b = manager.openTab(relativePath = "b.txt", text = "hello b")

        manager.markDirty(1L, b)
        manager.activate(1L, a, EditorSnapshot("hello b edited", 1, 2, 30))

        assertEquals(a, manager.activeTab(1L)?.id)
        assertEquals("hello a", manager.buffer(a)?.text)
        assertEquals("hello b edited", manager.buffer(b)?.text)
        assertFalse(manager.tabs(1L).first { it.id == a }.isDirty)
        assertTrue(manager.tabs(1L).first { it.id == b }.isDirty)
    }

    @Test
    fun `reopening a tab restores its cached text`() = runTest {
        val manager = manager()
        manager.restore(1L, root)
        val a = manager.openTab(relativePath = "a.txt", text = "aaa")
        val b = manager.openTab(relativePath = "b.txt", text = "bbb")
        manager.activate(1L, a, EditorSnapshot("bbb edited", 0, 0, 0))

        manager.activate(1L, b, EditorSnapshot("aaa", 0, 0, 0))

        assertEquals("aaa", manager.buffer(a)?.text)
        assertEquals("bbb edited", manager.buffer(b)?.text)
    }

    @Test
    fun `closing the active tab activates its neighbour`() = runTest {
        val manager = manager()
        manager.restore(1L, root)
        val a = manager.openTab(relativePath = "a.txt")
        val b = manager.openTab(relativePath = "b.txt")
        val c = manager.openTab(relativePath = "c.txt")

        manager.close(1L, c)
        assertEquals(b, manager.activeTab(1L)?.id)
        manager.close(1L, b)
        assertEquals(a, manager.activeTab(1L)?.id)
        manager.close(1L, a)
        assertNull(manager.activeTab(1L))
        assertTrue(manager.tabs(1L).isEmpty())
    }

    @Test
    fun `close others keeps only the active tab`() = runTest {
        val manager = manager()
        manager.restore(1L, root)
        val a = manager.openTab(relativePath = "a.txt")
        val b = manager.openTab(relativePath = "b.txt")

        manager.closeOthers(1L)

        assertEquals(listOf(b), manager.tabs(1L).map { it.id })
        assertNull(manager.buffer(a))
        assertNotNull(manager.buffer(b))
    }

    @Test
    fun `close all clears tabs and buffers`() = runTest {
        val manager = manager()
        manager.restore(1L, root)
        manager.openTab(relativePath = "a.txt")
        manager.openTab(relativePath = "b.txt")

        manager.closeAll(1L)

        assertTrue(manager.tabs(1L).isEmpty())
        assertNull(manager.buffer("$root/a.txt"))
    }

    @Test
    fun `tab count is capped`() = runTest {
        val manager = manager()
        manager.restore(1L, root)
        repeat(12) { index ->
            assertTrue(
                manager.open(1L, root, "file$index.txt", "file$index.txt")
                    is OpenTabResult.Created
            )
        }

        assertEquals(OpenTabResult.LimitReached, manager.open(1L, root, "extra.txt", "extra.txt"))
    }

    @Test
    fun `renaming a folder re-keys every tab below it`() = runTest {
        val manager = manager()
        manager.restore(1L, root)
        val a = manager.openTab(relativePath = "src/a.kt", text = "aaa")
        val b = manager.openTab(relativePath = "src/b.kt", text = "bbb")

        val changed = manager.onEntryRenamed(1L, root, "src", "lib")

        assertTrue(changed)
        assertEquals(
            listOf("$root/lib/a.kt", "$root/lib/b.kt"),
            manager.tabs(1L).map { it.id }
        )
        assertEquals("$root/lib/b.kt", manager.activeTab(1L)?.id)
        assertEquals("bbb", manager.buffer("$root/lib/b.kt")?.text)
        assertNull(manager.buffer(b))
        assertEquals("aaa", manager.buffer("$root/lib/a.kt")?.text)
        assertNull(manager.buffer(a))
    }

    @Test
    fun `renaming an unrelated file leaves tabs untouched`() = runTest {
        val manager = manager()
        manager.restore(1L, root)
        manager.openTab(relativePath = "src/a.kt")

        assertFalse(manager.onEntryRenamed(1L, root, "other", "renamed"))
        assertEquals(listOf("$root/src/a.kt"), manager.tabs(1L).map { it.id })
    }

    @Test
    fun `deleting a folder marks its tabs missing but keeps unsaved text`() = runTest {
        val manager = manager()
        manager.restore(1L, root)
        val a = manager.openTab(relativePath = "src/a.kt", text = "draft")
        manager.markDirty(1L, a)

        val changed = manager.onEntryDeleted(1L, "src")

        assertTrue(changed)
        val tab = manager.tabs(1L).single()
        assertEquals(EditorTabStatus.MISSING, tab.status)
        assertTrue(tab.isDirty)
        assertEquals("draft", manager.buffer(a)?.text)
    }

    @Test
    fun `persisted dirty text is restored by a fresh manager`() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = FileEditorSessionStore(directory)
        val first = EditorTabManager(store)
        first.restore(7L, root)
        first.open(7L, root, "src/a.kt", "a.kt")
        val id = first.activeTab(7L)!!.id
        first.onLoaded(7L, id, "original", "\r\n", 100L, 8L)
        first.markDirty(7L, id)
        first.updateActive(7L, EditorSnapshot("edited text", 3, 5, 120))
        first.persist(7L)

        val second = EditorTabManager(store)
        second.restore(7L, root)

        val tab = second.tabs(7L).single()
        assertEquals(id, tab.id)
        assertTrue(tab.isDirty)
        assertEquals(EditorTabStatus.READY, tab.status)
        assertEquals("edited text", second.buffer(tab.id)?.text)
        assertEquals(3, tab.cursorLine)
        assertEquals(5, tab.cursorColumn)
        assertEquals(120, tab.scrollY)
        assertEquals("\r\n", tab.lineEnding)
    }

    @Test
    fun `clean tabs are reloaded instead of persisted`() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = FileEditorSessionStore(directory)
        val first = EditorTabManager(store)
        first.restore(7L, root)
        first.open(7L, root, "src/clean.kt", "clean.kt")
        val id = first.activeTab(7L)!!.id
        first.onLoaded(7L, id, "content", "\n", 10L, 7L)
        first.persist(7L)

        val second = EditorTabManager(store)
        second.restore(7L, root)

        val tab = second.tabs(7L).single()
        assertEquals(EditorTabStatus.LOADING, tab.status)
        assertNull(second.buffer(tab.id))
    }

    @Test
    fun `closing all tabs deletes the persisted session`() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = FileEditorSessionStore(directory)
        val manager = EditorTabManager(store)
        manager.restore(7L, root)
        manager.openTab(projectId = 7L, relativePath = "src/a.kt", text = "draft")
        manager.persist(7L)
        assertNotNull(store.load(7L))

        manager.closeAll(7L)
        manager.persist(7L)

        assertNull(store.load(7L))
    }

    @Test
    fun `restore ignores a session from a different project root`() = runTest {
        val directory = temporaryFolder.newFolder()
        val store = FileEditorSessionStore(directory)
        val first = EditorTabManager(store)
        first.restore(7L, root)
        first.open(7L, root, "src/a.kt", "a.kt")
        first.persist(7L)

        val second = EditorTabManager(store)
        second.restore(7L, "/storage/emulated/0/AndroidProjects/moved")

        assertEquals(
            listOf("/storage/emulated/0/AndroidProjects/moved/src/a.kt"),
            second.tabs(7L).map { it.id }
        )
    }
}

private class InMemorySessionStore : EditorSessionStore {

    private val sessions = mutableMapOf<Long, PersistedEditorSession>()

    override suspend fun load(projectId: Long): PersistedEditorSession? = sessions[projectId]

    override suspend fun save(session: PersistedEditorSession) {
        sessions[session.projectId] = session
    }

    override suspend fun delete(projectId: Long) {
        sessions.remove(projectId)
    }
}
