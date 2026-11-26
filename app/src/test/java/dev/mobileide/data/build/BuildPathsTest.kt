package dev.mobileide.data.build

import dev.mobileide.domain.build.BuildMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildPathsTest {

    @Test
    fun `relative paths are normalized and escapes rejected`() {
        assertEquals("src/main.c", BuildPaths.sanitizeRelative("./src/main.c"))
        assertEquals("src", BuildPaths.sanitizeRelative(" src/ "))
        assertEquals("a/b", BuildPaths.sanitizeRelative("a//b"))
        assertNull(BuildPaths.sanitizeRelative("../etc/passwd"))
        assertNull(BuildPaths.sanitizeRelative("a/../b"))
        assertNull(BuildPaths.sanitizeRelative("/etc/passwd"))
        assertNull(BuildPaths.sanitizeRelative("C:/Windows"))
        assertNull(BuildPaths.sanitizeRelative("a\\b"))
        assertNull(BuildPaths.sanitizeRelative(""))
        assertNull(BuildPaths.sanitizeRelative(null))
    }

    @Test
    fun `output paths always live under build`() {
        assertEquals("build/debug", BuildPaths.outputDir(BuildMode.DEBUG))
        assertEquals("build/release", BuildPaths.outputDir(BuildMode.RELEASE))
        assertTrue(BuildPaths.isInsideBuild("build/debug/app"))
        assertTrue(BuildPaths.isInsideBuild("build"))
        assertFalse(BuildPaths.isInsideBuild("builder/app"))
        assertFalse(BuildPaths.isInsideBuild("src/main.c"))
    }

    @Test
    fun `task and log paths are namespaced`() {
        assertEquals(
            ".mobileide/tasks/20261126-120000-001.properties",
            BuildPaths.taskPath("20261126-120000-001")
        )
        assertEquals(".mobileide/logs/20261126-120000-001.log", BuildPaths.logPath("20261126-120000-001"))
    }
}
