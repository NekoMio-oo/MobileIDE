package dev.mobileide.data.runtime

import dev.mobileide.domain.runtime.RuntimeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.util.Properties

class RuntimeCatalogLoaderTest {

    private val sha = "a".repeat(64)
    private val loader = RuntimeCatalogLoader(openDefault = { null })

    private fun properties(text: String): Properties = Properties().apply {
        load(StringReader(text))
    }

    @Test
    fun `catalog parses linux environment and languages`() {
        val catalog = loader.parse(
            properties(
                """
                version=1
                linux.name=Debian 12 (bookworm) arm64
                linux.version=12
                linux.rootfs.url=https://example.com/rootfs.tar.xz
                linux.rootfs.sha256=$sha
                linux.rootfs.filename=rootfs.tar.xz
                linux.rootfs.size=100
                linux.proot.url=https://example.com/proot.deb
                linux.proot.sha256=$sha
                linux.proot.filename=proot.deb
                linux.prootlib.count=1
                linux.prootlib.1.url=https://example.com/libtalloc.deb
                linux.prootlib.1.sha256=$sha
                linux.prootlib.1.filename=libtalloc.deb
                language.count=2
                language.1.id=PYTHON
                language.1.name=Python 3
                language.1.tool=python3
                language.1.versionArgs=--version
                language.1.apt=python3
                language.2.id=KOTLIN
                language.2.name=Kotlin
                language.2.unsupported=Debian does not package the Kotlin compiler
                """.trimIndent()
            )
        )

        assertEquals(1, catalog.version)
        val linux = catalog.linux!!
        assertNotNull(linux)
        assertEquals("Debian 12 (bookworm) arm64", linux.name)
        assertEquals("rootfs.tar.xz", linux.rootfs.fileName)
        assertEquals(100L, linux.rootfs.sizeBytes)
        assertEquals(1, linux.prootLibs.size)
        assertEquals(2, catalog.languages.size)
        assertEquals(RuntimeId.PYTHON, catalog.languages[0].id)
        assertEquals(listOf("--version"), catalog.languages[0].versionArgs)
        assertEquals(listOf("python3"), catalog.languages[0].aptPackages)
        assertEquals(
            "Debian does not package the Kotlin compiler",
            catalog.languages[1].unsupportedReason
        )
    }

    @Test
    fun `entries without a checksum are rejected`() {
        val catalog = loader.parse(
            properties(
                """
                version=1
                linux.rootfs.url=https://example.com/rootfs.tar.xz
                linux.rootfs.sha256=1234
                linux.proot.url=https://example.com/proot.deb
                linux.proot.sha256=$sha
                """.trimIndent()
            )
        )

        assertNull(catalog.linux)
        assertTrue(catalog.languages.isEmpty())
    }

    @Test
    fun `missing linux section yields no environment`() {
        val catalog = loader.parse(properties("version=1\nlanguage.count=0"))
        assertNull(catalog.linux)
    }
}
