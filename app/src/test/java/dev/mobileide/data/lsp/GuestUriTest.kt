package dev.mobileide.data.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestUriTest {

    @Test
    fun `encodes spaces and non ascii names`() {
        assertEquals("file:///workspace/src/a%20b.c", GuestUri.encode("src/a b.c"))
        assertEquals(
            "file:///workspace/%E4%B8%AD%E6%96%87.c",
            GuestUri.encode("中文.c")
        )
        assertEquals("file:///workspace", GuestUri.encode(""))
    }

    @Test
    fun `decodes what it encoded`() {
        val paths = listOf("src/a b.c", "中文/文件.h", "lib/x+y.rs", "a#b?.kt")
        paths.forEach { path ->
            assertEquals(path, GuestUri.relativePath(GuestUri.encode(path)))
        }
    }

    @Test
    fun `rejects uris outside the workspace`() {
        assertNull(GuestUri.relativePath("file:///etc/passwd"))
        assertNull(GuestUri.relativePath("file:///workspace/../etc/passwd"))
        assertNull(GuestUri.relativePath("file:///workspace/%2e%2e/etc/passwd"))
        assertNull(GuestUri.relativePath("content://com.example/doc"))
        assertNull(GuestUri.relativePath("https://example.com/workspace/a.c"))
        assertNull(GuestUri.relativePath("not a uri"))
    }

    @Test
    fun `rejects malformed escapes`() {
        assertNull(GuestUri.relativePath("file:///workspace/%ZZ"))
        assertNull(GuestUri.relativePath("file:///workspace/%4"))
    }

    @Test
    fun `accepts the workspace root itself`() {
        assertEquals("", GuestUri.relativePath("file:///workspace"))
        assertEquals("", GuestUri.relativePath("file:///workspace/"))
    }

    @Test
    fun `builds guest paths`() {
        assertEquals("/workspace", GuestUri.guestPath(""))
        assertEquals("/workspace/src/main.c", GuestUri.guestPath("/src/main.c/"))
    }
}
