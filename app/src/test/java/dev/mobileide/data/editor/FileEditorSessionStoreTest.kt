package dev.mobileide.data.editor

import dev.mobileide.domain.editor.PersistedEditorSession
import dev.mobileide.domain.editor.PersistedTab
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileEditorSessionStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun store() = FileEditorSessionStore(temporaryFolder.newFolder())

    private fun tab(
        relativePath: String = "src/a.kt",
        dirty: Boolean = false,
        text: String? = null
    ) = PersistedTab(
        relativePath = relativePath,
        name = relativePath.substringAfterLast('/'),
        lineEnding = "\r\n",
        cursorLine = 4,
        cursorColumn = 7,
        scrollY = 210,
        baselineModified = 1234L,
        baselineSize = 99L,
        dirty = dirty,
        missing = false,
        text = text
    )

    @Test
    fun `session round trips metadata and dirty buffers`() = runTest {
        val store = store()
        val session = PersistedEditorSession(
            projectId = 42L,
            activeRelativePath = "src/a.kt",
            tabs = listOf(
                tab(dirty = true, text = "unsaved 内容"),
                tab(relativePath = "src/b.kt")
            )
        )

        store.save(session)
        val loaded = store.load(42L)

        assertEquals("src/a.kt", loaded?.activeRelativePath)
        assertEquals(2, loaded?.tabs?.size)
        val first = loaded!!.tabs[0]
        assertEquals("unsaved 内容", first.text)
        assertTrue(first.dirty)
        assertEquals("\r\n", first.lineEnding)
        assertEquals(4, first.cursorLine)
        assertEquals(210, first.scrollY)
        assertNull(loaded.tabs[1].text)
    }

    @Test
    fun `sessions are per project`() = runTest {
        val store = store()
        store.save(PersistedEditorSession(1L, null, listOf(tab())))
        store.save(PersistedEditorSession(2L, "src/b.kt", listOf(tab(relativePath = "src/b.kt"))))

        assertEquals(listOf("src/a.kt"), store.load(1L)!!.tabs.map { it.relativePath })
        assertEquals(listOf("src/b.kt"), store.load(2L)!!.tabs.map { it.relativePath })
    }

    @Test
    fun `delete removes the session`() = runTest {
        val store = store()
        store.save(PersistedEditorSession(1L, null, listOf(tab())))
        store.delete(1L)

        assertNull(store.load(1L))
    }

    @Test
    fun `corrupt files are ignored`() = runTest {
        val directory = temporaryFolder.newFolder()
        File(directory, "project-9.session").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val store = FileEditorSessionStore(directory)

        assertNull(store.load(9L))
    }

    @Test
    fun `loading a missing project returns null`() = runTest {
        assertNull(store().load(404L))
    }
}
