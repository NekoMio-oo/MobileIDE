package dev.mobileide.data.lsp

import dev.mobileide.domain.lsp.LspSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LspStoresTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `manifest round trips installed servers`() {
        val store = LspManifestStore(File(folder.root, "installed.properties"))
        store.put(
            InstalledLspServer(
                id = "jdtls",
                version = "Eclipse JDT LS 1.31",
                installedAt = 1_700_000_000_000L,
                path = "/data/lsp/servers/jdtls",
                sizeBytes = 123_456L,
                packages = listOf("openjdk-17-jdk-headless")
            )
        )
        store.put(
            InstalledLspServer(
                id = "clangd",
                version = "Debian clangd version 14.0.6",
                installedAt = 1_700_000_001_000L,
                path = "",
                sizeBytes = 1L,
                packages = listOf("clangd")
            )
        )

        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals("Eclipse JDT LS 1.31", loaded["jdtls"]?.version)
        assertEquals(123_456L, loaded["jdtls"]?.sizeBytes)
        assertEquals(listOf("openjdk-17-jdk-headless"), loaded["jdtls"]?.packages)
        assertEquals(1_700_000_001_000L, loaded["clangd"]?.installedAt)

        store.remove("jdtls")
        assertNull(store.load()["jdtls"])
        assertEquals(1, store.load().size)
    }

    @Test
    fun `a corrupt manifest is ignored instead of crashing`() {
        val file = File(folder.root, "installed.properties")
        file.writeText("not a properties file at all")
        assertTrue(LspManifestStore(file).load().isEmpty())
    }

    @Test
    fun `settings round trip switches and extra arguments`() {
        val file = File(folder.root, "settings.properties")
        val store = LspSettingsStore(file)
        store.save(
            LspStoredSettings(
                settings = LspSettings(enabled = false, autoStart = false, maxSessions = 3),
                enabled = mapOf("clangd" to false, "pylsp" to true),
                extraArgs = mapOf("clangd" to listOf("--log=verbose", "--pch-storage=disk"))
            )
        )

        val loaded = store.load()
        assertFalse(loaded.settings.enabled)
        assertFalse(loaded.settings.autoStart)
        assertEquals(3, loaded.settings.maxSessions)
        assertFalse(loaded.isEnabled("clangd"))
        assertTrue(loaded.isEnabled("pylsp"))
        assertTrue(loaded.isEnabled("unknown"))
        assertEquals(listOf("--log=verbose", "--pch-storage=disk"), loaded.argsFor("clangd"))
        assertTrue(loaded.argsFor("pylsp").isEmpty())
    }

    @Test
    fun `session count is clamped to a sane range`() {
        val file = File(folder.root, "settings.properties")
        file.writeText("maxSessions=99\n")
        assertEquals(3, LspSettingsStore(file).load().settings.maxSessions)

        file.writeText("maxSessions=0\n")
        assertEquals(1, LspSettingsStore(file).load().settings.maxSessions)
    }

    @Test
    fun `missing settings file falls back to defaults`() {
        val loaded = LspSettingsStore(File(folder.root, "missing.properties")).load()
        assertTrue(loaded.settings.enabled)
        assertTrue(loaded.settings.autoStart)
        assertEquals(2, loaded.settings.maxSessions)
    }
}
