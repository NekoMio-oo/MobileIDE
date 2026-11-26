package dev.mobileide.core.filesystem

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeFileSystemImplTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var storage: File
    private lateinit var fileSystem: NativeFileSystemImpl

    @Before
    fun setUp() {
        storage = temporaryFolder.newFolder("storage")
        fileSystem = NativeFileSystemImpl(listOf(storage.absolutePath))
    }

    @Test
    fun `creates directories and files and reads them back`() = runTest {
        val directory = fileSystem.createDirectory("${storage.absolutePath}/projects/demo")
        assertTrue(directory.isDirectory)

        val file = fileSystem.createFile(
            "${storage.absolutePath}/projects/demo/main.c",
            "int main() { return 0; }"
        )
        assertEquals("main.c", file.name)
        assertEquals("int main() { return 0; }", fileSystem.readFile(file.path))

        val entries = fileSystem.listFiles("${storage.absolutePath}/projects/demo")
        assertTrue(entries.any { it.name == "main.c" && !it.isDirectory })
    }

    @Test
    fun `createFile refuses to overwrite unless asked`() = runTest {
        val path = "${storage.absolutePath}/notes.txt"
        fileSystem.createFile(path, "one")

        val failure = runCatching { fileSystem.createFile(path, "two") }.exceptionOrNull()
        assertTrue(failure is NativeFileException)
        assertEquals("one", fileSystem.readFile(path))

        fileSystem.createFile(path, "two", overwrite = true)
        assertEquals("two", fileSystem.readFile(path))
    }

    @Test
    fun `writeFile replaces content atomically`() = runTest {
        val path = "${storage.absolutePath}/main.kt"
        fileSystem.createFile(path, "old")

        fileSystem.writeFile(path, "fun main() {}")

        assertEquals("fun main() {}", fileSystem.readFile(path))
    }

    @Test
    fun `rename moves the entry`() = runTest {
        val path = "${storage.absolutePath}/draft.txt"
        fileSystem.createFile(path, "x")

        val renamed = fileSystem.renameFile(path, "final.txt")

        assertEquals("final.txt", renamed.name)
        assertFalse(File(path).exists())
        assertTrue(File(renamed.path).isFile)
    }

    @Test
    fun `rename refuses an existing target`() = runTest {
        fileSystem.createFile("${storage.absolutePath}/a.txt", "a")
        fileSystem.createFile("${storage.absolutePath}/b.txt", "b")

        val failure = runCatching {
            fileSystem.renameFile("${storage.absolutePath}/a.txt", "b.txt")
        }.exceptionOrNull()

        assertTrue(failure is NativeFileException)
        assertEquals("a", fileSystem.readFile("${storage.absolutePath}/a.txt"))
    }

    @Test
    fun `delete removes a directory tree`() = runTest {
        val project = File(storage, "demo/src").apply { mkdirs() }
        File(project, "main.c").writeText("x")

        fileSystem.deleteFile("${storage.absolutePath}/demo", recursive = true)

        assertFalse(File(storage, "demo").exists())
    }

    @Test
    fun `recursive delete does not follow symlinks out of the tree`() = runTest {
        val outside = File(storage, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("secret")
        val demo = File(storage, "demo").apply { mkdirs() }
        val link = File(demo, "link")
        val supported = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        org.junit.Assume.assumeTrue("Filesystem does not support symlinks", supported)

        fileSystem.deleteFile(demo.absolutePath, recursive = true)

        assertFalse(demo.exists())
        assertTrue(File(outside, "secret.txt").isFile)
    }

    @Test
    fun `delete refuses directories without the recursive flag`() = runTest {
        File(storage, "demo").mkdirs()

        val failure = runCatching {
            fileSystem.deleteFile("${storage.absolutePath}/demo")
        }.exceptionOrNull()

        assertTrue(failure is NativeFileException)
        assertTrue(File(storage, "demo").isDirectory)
    }

    @Test
    fun `paths outside the allowed roots are rejected`() = runTest {
        val outside = temporaryFolder.newFolder("outside")
        val failure = runCatching {
            fileSystem.createFile("${outside.absolutePath}/oops.txt", "x")
        }.exceptionOrNull()

        assertTrue(failure is NativeFileException)
        assertFalse(File(outside, "oops.txt").exists())
    }

    @Test
    fun `path traversal cannot escape the root`() = runTest {
        val outside = temporaryFolder.newFolder("outside")
        File(outside, "secret.txt").writeText("secret")

        val failure = runCatching {
            fileSystem.readFile("${storage.absolutePath}/../outside/secret.txt")
        }.exceptionOrNull()

        assertTrue(failure is NativeFileException)
    }

    @Test
    fun `android data is rejected`() = runTest {
        val restricted = File(storage, "Android/data/com.example/files").apply { mkdirs() }

        val failure = runCatching {
            fileSystem.createFile("${restricted.absolutePath}/a.txt", "x")
        }.exceptionOrNull()

        assertTrue(failure is NativeFileException)
    }

    @Test
    fun `search matches hidden files but does not walk hidden folders`() = runTest {
        File(storage, "demo/src").mkdirs()
        File(storage, "demo/src/main.c").writeText("x")
        File(storage, "demo/src/Main.kt").writeText("x")
        File(storage, "demo/.hidden-main.txt").writeText("x")
        File(storage, "demo/.git/hidden-main.c").mkdirs()

        val results = fileSystem.searchFiles(storage.absolutePath, "main")

        assertTrue(results.any { it.name == "main.c" })
        assertTrue(results.any { it.name == "Main.kt" })
        assertTrue(results.any { it.name == ".hidden-main.txt" })
        assertFalse(results.any { it.name == "hidden-main.c" })
    }

    @Test
    fun `search can be limited to extensions`() = runTest {
        File(storage, "demo/src").mkdirs()
        File(storage, "demo/src/main.c").writeText("x")
        File(storage, "demo/src/main.kt").writeText("x")
        File(storage, "demo/src/main.md").writeText("x")

        val results = fileSystem.searchFiles(
            rootPath = storage.absolutePath,
            query = "main",
            extensions = setOf("kt")
        )

        assertEquals(listOf("main.kt"), results.map { it.name })
    }

    @Test
    fun `search respects the depth limit`() = runTest {
        File(storage, "demo/a/b/c").mkdirs()
        File(storage, "demo/a/b/c/deep-main.c").writeText("x")

        val shallow = fileSystem.searchFiles(storage.absolutePath, "deep", maxDepth = 1)
        val full = fileSystem.searchFiles(storage.absolutePath, "deep", maxDepth = 4)

        assertTrue(shallow.isEmpty())
        assertEquals(1, full.size)
    }

    @Test
    fun `count entries reports descendants and stops at the limit`() = runTest {
        File(storage, "demo/src").mkdirs()
        File(storage, "demo/src/a.c").writeText("x")
        File(storage, "demo/b.md").writeText("x")

        assertEquals(3, fileSystem.countEntries("${storage.absolutePath}/demo"))
        assertEquals(1, fileSystem.countEntries("${storage.absolutePath}/demo", limit = 1))
    }

    @Test
    fun `search returns nothing for a blank query`() = runTest {
        File(storage, "demo").mkdirs()
        assertTrue(fileSystem.searchFiles(storage.absolutePath, "   ").isEmpty())
    }

    @Test
    fun `getFileInfo reports metadata`() = runTest {
        val path = "${storage.absolutePath}/info.txt"
        fileSystem.createFile(path, "hello")

        val info = fileSystem.getFileInfo(path)

        assertNotNull(info)
        assertEquals(5L, info!!.size)
        assertFalse(info.isDirectory)
        assertTrue(fileSystem.exists(path))
    }
}
