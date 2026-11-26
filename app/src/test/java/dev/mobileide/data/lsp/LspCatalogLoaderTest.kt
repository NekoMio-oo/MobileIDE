package dev.mobileide.data.lsp

import dev.mobileide.domain.editor.EditorLanguage
import dev.mobileide.domain.lsp.LspInstallKind
import dev.mobileide.domain.lsp.LspLaunchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class LspCatalogLoaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val catalog = """
        server.count=4
        server.1.id=clangd
        server.1.name=clangd 14
        server.1.languages=C,CPP
        server.1.kind=apt
        server.1.packages=clangd
        server.1.binary=/usr/bin/clangd
        server.1.args=--background-index=0|--limit-results=100
        server.1.versionArgs=--version
        server.1.sizeMb=164
        server.1.capabilities=completion,hover,definition,references,symbols,formatting

        server.2.id=jdtls
        server.2.languages=JAVA
        server.2.kind=archive
        server.2.packages=openjdk-17-jdk-headless
        server.2.url=https://example.com/jdtls.tar.gz
        server.2.sha256=abc123
        server.2.size=46523149
        server.2.mount=/opt/jdtls
        server.2.launch=jdtls
        server.2.binary=/usr/bin/java
        server.2.capabilities=completion,hover

        server.3.id=gopls
        server.3.languages=GO
        server.3.kind=unsupported
        server.3.unsupported=Go is not available.

        server.4.id=broken
        server.4.languages=RUST
        server.4.kind=apt
        server.4.packages=rust-analyzer
    """.trimIndent()

    @Test
    fun `parses apt servers with languages and arguments`() {
        val servers = loader(catalog).load()
        val clangd = servers.first { it.id == "clangd" }

        assertEquals("clangd 14", clangd.displayName)
        assertEquals(listOf(EditorLanguage.C, EditorLanguage.CPP), clangd.languages)
        assertEquals("c", clangd.languageIds[EditorLanguage.C])
        assertEquals("cpp", clangd.languageIds[EditorLanguage.CPP])
        assertEquals(listOf("--background-index=0", "--limit-results=100"), clangd.args)
        assertEquals(listOf("--version"), clangd.versionArgs)
        assertEquals(LspInstallKind.APT, clangd.installKind)
        assertEquals(LspLaunchKind.DIRECT, clangd.launchKind)
        assertTrue(clangd.declaredCapabilities.documentSymbol)
        assertTrue(clangd.supported)
    }

    @Test
    fun `parses archive servers with mount and sha`() {
        val servers = loader(catalog).load()
        val jdt = servers.first { it.id == "jdtls" }

        assertEquals(LspInstallKind.ARCHIVE, jdt.installKind)
        assertEquals(LspLaunchKind.JDTLS, jdt.launchKind)
        assertEquals("/opt/jdtls", jdt.guestMount)
        assertEquals("abc123", jdt.downloadSha256)
        assertEquals(46_523_149L, jdt.downloadSizeBytes)
        assertEquals(listOf("openjdk-17-jdk-headless"), jdt.packages)
        assertFalse(jdt.declaredCapabilities.formatting)
    }

    @Test
    fun `keeps unsupported entries and drops entries that cannot install`() {
        val servers = loader(catalog).load()

        val gopls = servers.first { it.id == "gopls" }
        assertFalse(gopls.supported)
        assertEquals("Go is not available.", gopls.unsupportedReason)
        assertNull(servers.firstOrNull { it.id == "broken" })
    }

    @Test
    fun `an override file wins over the bundled catalog`() {
        val override = File(folder.root, "catalog.properties").apply {
            writeText(
                """
                server.count=1
                server.1.id=pylsp
                server.1.languages=PYTHON
                server.1.kind=apt
                server.1.packages=python3-pylsp
                server.1.binary=/usr/bin/pylsp
                """.trimIndent()
            )
        }
        val loader = LspCatalogLoader(
            openDefault = { error("bundled catalog must not be read") },
            overrideFile = override
        )

        val servers = loader.load()
        assertEquals(1, servers.size)
        assertEquals("pylsp", servers.first().id)
        assertEquals("python", servers.first().languageIds[EditorLanguage.PYTHON])
    }

    private fun loader(text: String): LspCatalogLoader = LspCatalogLoader(
        openDefault = { ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) }
    )
}
