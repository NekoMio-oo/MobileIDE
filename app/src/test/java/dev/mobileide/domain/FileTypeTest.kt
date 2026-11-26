package dev.mobileide.domain.files

import dev.mobileide.domain.editor.EditorLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTypeTest {

    @Test
    fun `required extensions map to their file type`() {
        assertEquals(FileType.C, FileType.fromFileName("main.c"))
        assertEquals(FileType.C_HEADER, FileType.fromFileName("stdio.h"))
        assertEquals(FileType.CPP, FileType.fromFileName("engine.cpp"))
        assertEquals(FileType.CPP_HEADER, FileType.fromFileName("engine.hpp"))
        assertEquals(FileType.PYTHON, FileType.fromFileName("app.py"))
        assertEquals(FileType.JAVA, FileType.fromFileName("Main.java"))
        assertEquals(FileType.KOTLIN, FileType.fromFileName("Main.kt"))
        assertEquals(FileType.KOTLIN_SCRIPT, FileType.fromFileName("build.gradle.kts"))
        assertEquals(FileType.JAVASCRIPT, FileType.fromFileName("app.js"))
        assertEquals(FileType.TYPESCRIPT, FileType.fromFileName("app.ts"))
        assertEquals(FileType.GO, FileType.fromFileName("main.go"))
        assertEquals(FileType.RUST, FileType.fromFileName("main.rs"))
        assertEquals(FileType.HTML, FileType.fromFileName("index.html"))
        assertEquals(FileType.CSS, FileType.fromFileName("style.css"))
        assertEquals(FileType.JSON, FileType.fromFileName("package.json"))
        assertEquals(FileType.XML, FileType.fromFileName("layout.xml"))
        assertEquals(FileType.MARKDOWN, FileType.fromFileName("README.md"))
        assertEquals(FileType.TEXT, FileType.fromFileName("notes.txt"))
    }

    @Test
    fun `headers keep the editor language of their family`() {
        assertEquals(EditorLanguage.C, FileType.C_HEADER.language)
        assertEquals(EditorLanguage.CPP, FileType.CPP_HEADER.language)
        assertEquals(EditorLanguage.KOTLIN, FileType.KOTLIN_SCRIPT.language)
        assertEquals(EditorLanguage.PLAIN_TEXT, FileType.JSON.language)
    }

    @Test
    fun `extension matching is case insensitive`() {
        assertEquals(FileType.KOTLIN, FileType.fromFileName("MAIN.KT"))
        assertEquals(FileType.HTML, FileType.fromFileName("Index.HTML"))
    }

    @Test
    fun `hidden and extensionless files stay unknown`() {
        assertEquals(FileType.UNKNOWN, FileType.fromFileName(".gitignore"))
        assertEquals(FileType.UNKNOWN, FileType.fromFileName(".env"))
        assertEquals(FileType.UNKNOWN, FileType.fromFileName("Makefile"))
        assertEquals(FileType.UNKNOWN, FileType.fromFileName("README"))
    }

    @Test
    fun `binary types are kept away from the text editor`() {
        assertTrue(FileType.isProbablyBinary("icon.png"))
        assertTrue(FileType.isProbablyBinary("app.apk"))
        assertTrue(FileType.isProbablyBinary("archive.tar.gz"))
        assertTrue(FileType.isProbablyBinary("libnative.so"))
        assertFalse(FileType.isProbablyBinary("main.kt"))
        assertFalse(FileType.isProbablyBinary("logo.svg"))
        assertFalse(FileType.isProbablyBinary("Makefile"))
    }
}
