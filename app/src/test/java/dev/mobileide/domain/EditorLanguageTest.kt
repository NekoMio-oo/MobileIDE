package dev.mobileide.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorLanguageTest {

    @Test
    fun `known extensions map to their language`() {
        assertEquals(EditorLanguage.C, EditorLanguage.fromFileName("main.c"))
        assertEquals(EditorLanguage.C, EditorLanguage.fromFileName("stdio.h"))
        assertEquals(EditorLanguage.CPP, EditorLanguage.fromFileName("engine.cpp"))
        assertEquals(EditorLanguage.CPP, EditorLanguage.fromFileName("engine.hpp"))
        assertEquals(EditorLanguage.PYTHON, EditorLanguage.fromFileName("app.py"))
        assertEquals(EditorLanguage.JAVA, EditorLanguage.fromFileName("Main.java"))
        assertEquals(EditorLanguage.KOTLIN, EditorLanguage.fromFileName("Main.kt"))
        assertEquals(EditorLanguage.KOTLIN, EditorLanguage.fromFileName("build.gradle.kts"))
        assertEquals(EditorLanguage.JAVASCRIPT, EditorLanguage.fromFileName("app.js"))
        assertEquals(EditorLanguage.TYPESCRIPT, EditorLanguage.fromFileName("app.ts"))
        assertEquals(EditorLanguage.GO, EditorLanguage.fromFileName("main.go"))
        assertEquals(EditorLanguage.RUST, EditorLanguage.fromFileName("main.rs"))
        assertEquals(EditorLanguage.HTML, EditorLanguage.fromFileName("index.html"))
        assertEquals(EditorLanguage.CSS, EditorLanguage.fromFileName("style.css"))
    }

    @Test
    fun `extensions are matched case insensitively`() {
        assertEquals(EditorLanguage.KOTLIN, EditorLanguage.fromFileName("MAIN.KT"))
        assertEquals(EditorLanguage.HTML, EditorLanguage.fromFileName("Index.HTML"))
    }

    @Test
    fun `unknown and extensionless files fall back to plain text`() {
        assertEquals(EditorLanguage.PLAIN_TEXT, EditorLanguage.fromFileName("notes.txt"))
        assertEquals(EditorLanguage.PLAIN_TEXT, EditorLanguage.fromFileName("Makefile"))
        assertEquals(EditorLanguage.PLAIN_TEXT, EditorLanguage.fromFileName("README"))
        assertEquals(EditorLanguage.PLAIN_TEXT, EditorLanguage.fromFileName("archive.tar.gz"))
    }
}
