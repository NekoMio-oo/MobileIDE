package dev.mobileide.data.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LinuxRuntimeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun installedRuntime(): Pair<RuntimePaths, LinuxRuntime> {
        val paths = RuntimePaths(
            filesDir = temporaryFolder.newFolder("files"),
            cacheDir = temporaryFolder.newFolder("cache")
        )
        paths.ensureBaseDirs()
        paths.prootBinary.parentFile?.mkdirs()
        paths.prootBinary.writeText("proot")
        paths.prootBinary.setExecutable(true)
        paths.prootLoader.parentFile?.mkdirs()
        paths.prootLoader.writeText("loader")
        paths.rootfsShell.parentFile?.mkdirs()
        paths.rootfsShell.writeText("sh")
        return paths to LinuxRuntime(paths)
    }

    @Test
    fun `apt configuration disables release expiry inside the container`() {
        val (paths, runtime) = installedRuntime()

        assertTrue(runtime.ensureAptConfiguration())
        val file = File(paths.rootfsDir, "etc/apt/apt.conf.d/99mobileide")
        assertTrue(file.isFile)
        val content = file.readText()
        assertTrue(content.contains("Acquire::Check-Valid-Until"))
        assertTrue(content.contains("false"))

        // Idempotent: a second call must not fail or duplicate.
        assertTrue(runtime.ensureAptConfiguration())
    }

    @Test
    fun `no command is built before the environment is installed`() {
        val paths = RuntimePaths(
            filesDir = temporaryFolder.newFolder("files"),
            cacheDir = temporaryFolder.newFolder("cache")
        )

        assertNull(
            LinuxRuntime(paths).buildExecution(listOf("/bin/sh", "-lc", "echo hi"))
        )
    }

    @Test
    fun `proot command carries loader, libraries and binds`() {
        val (paths, runtime) = installedRuntime()

        val request = runtime.buildExecution(
            guestCommand = listOf("/bin/sh", "-lc", "echo hi"),
            fakeRoot = true,
            bindPaths = listOf("/storage/emulated/0")
        )!!

        assertTrue(request.command.contains("--link2symlink"))
        assertTrue(request.command.contains("-0"))
        assertTrue(request.command.contains(paths.rootfsDir.absolutePath))
        assertTrue(request.command.contains("/storage/emulated/0"))
        assertTrue(request.command.contains("/usr/bin/env"))
        assertEquals(paths.prootLoader.absolutePath, request.environment["PROOT_LOADER"])
        assertEquals(paths.tempDir.absolutePath, request.environment["PROOT_TMP_DIR"])
        assertEquals(paths.prootLibDir.absolutePath, request.environment["LD_LIBRARY_PATH"])
        assertTrue(request.workingDirectory?.isDirectory == true)
    }

    @Test
    fun `fake root is omitted for normal execution`() {
        val (_, runtime) = installedRuntime()

        val request = runtime.buildExecution(listOf("/bin/true"))!!

        assertTrue(!request.command.contains("-0"))
    }
}
