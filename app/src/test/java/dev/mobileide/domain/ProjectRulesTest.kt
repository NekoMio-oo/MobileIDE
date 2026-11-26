package dev.mobileide.domain

import dev.mobileide.domain.model.ProjectType
import dev.mobileide.domain.template.ProjectTemplates
import dev.mobileide.domain.validation.ProjectNameError
import dev.mobileide.domain.validation.ProjectNameValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectNameValidatorTest {

    @Test
    fun `blank names are rejected`() {
        assertEquals(ProjectNameError.EMPTY, ProjectNameValidator.validate(""))
        assertEquals(ProjectNameError.EMPTY, ProjectNameValidator.validate("   "))
    }

    @Test
    fun `names longer than the limit are rejected`() {
        assertEquals(ProjectNameError.TOO_LONG, ProjectNameValidator.validate("a".repeat(65)))
    }

    @Test
    fun `path separators and reserved characters are rejected`() {
        assertEquals(ProjectNameError.INVALID_CHARACTERS, ProjectNameValidator.validate("bad/name"))
        assertEquals(ProjectNameError.INVALID_CHARACTERS, ProjectNameValidator.validate("bad:name"))
        assertEquals(ProjectNameError.INVALID_CHARACTERS, ProjectNameValidator.validate("bad|name"))
    }

    @Test
    fun `dot names are reserved`() {
        assertEquals(ProjectNameError.RESERVED, ProjectNameValidator.validate("."))
        assertEquals(ProjectNameError.RESERVED, ProjectNameValidator.validate(".."))
        assertEquals(ProjectNameError.RESERVED, ProjectNameValidator.validate(".hidden"))
    }

    @Test
    fun `ordinary names are accepted and trimmed`() {
        assertNull(ProjectNameValidator.validate("  my-app  "))
        assertEquals("my-app", ProjectNameValidator.normalize("  my-app  "))
        assertNull(ProjectNameValidator.validate("hello_world 2"))
    }

    @Test
    fun `identifiers are filesystem friendly`() {
        assertEquals("my_app", ProjectNameValidator.toIdentifier("My App"))
        assertEquals("project", ProjectNameValidator.toIdentifier("!!!"))
    }
}

class ProjectTemplatesTest {

    @Test
    fun `c project has source include and readme`() {
        val paths = ProjectTemplates.forType(ProjectType.C, "demo")
            .filterNot { it.isDirectory }
            .map { it.path }

        assertTrue(paths.contains("src/main.c"))
        assertTrue(paths.contains("README.md"))
        assertTrue(ProjectTemplates.forType(ProjectType.C, "demo").any { it.path == "include" && it.isDirectory })
    }

    @Test
    fun `cpp project uses a cpp entry point`() {
        val files = ProjectTemplates.forType(ProjectType.CPP, "demo").filterNot { it.isDirectory }
        assertTrue(files.any { it.path == "src/main.cpp" })
    }

    @Test
    fun `web project contains html css and script`() {
        val paths = ProjectTemplates.forType(ProjectType.WEB, "demo")
            .filterNot { it.isDirectory }
            .map { it.path }

        assertTrue(paths.containsAll(listOf("index.html", "style.css", "script.js", "README.md")))
    }

    @Test
    fun `every creatable type produces at least one file including readme`() {
        ProjectType.creatable.forEach { type ->
            val entries = ProjectTemplates.forType(type, "demo")
            assertNotNull(entries.firstOrNull { it.path == "README.md" && !it.isDirectory })
            assertTrue("$type should contain a file", entries.any { !it.isDirectory && it.path != "README.md" } || type == ProjectType.EMPTY)
        }
    }

    @Test
    fun `readme mentions the project name`() {
        val readme = ProjectTemplates.forType(ProjectType.PYTHON, "notes-app")
            .first { it.path == "README.md" }
        assertTrue(readme.content.orEmpty().contains("notes-app"))
    }
}

class ProjectTypeDetectorTest {

    @Test
    fun `build files win over loose sources`() {
        assertEquals(ProjectType.RUST, ProjectTypeDetector.detect(listOf("Cargo.toml", "src")))
        assertEquals(ProjectType.GO, ProjectTypeDetector.detect(listOf("go.mod", "main.go")))
        assertEquals(ProjectType.TYPESCRIPT, ProjectTypeDetector.detect(listOf("tsconfig.json", "package.json")))
        assertEquals(ProjectType.JAVASCRIPT, ProjectTypeDetector.detect(listOf("package.json")))
        assertEquals(ProjectType.KOTLIN, ProjectTypeDetector.detect(listOf("build.gradle.kts")))
    }

    @Test
    fun `source extensions are used as fallback`() {
        assertEquals(ProjectType.PYTHON, ProjectTypeDetector.detect(listOf("main.py")))
        assertEquals(ProjectType.C, ProjectTypeDetector.detect(listOf("main.c", "include")))
        assertEquals(ProjectType.CPP, ProjectTypeDetector.detect(listOf("main.cpp")))
        assertEquals(ProjectType.WEB, ProjectTypeDetector.detect(listOf("index.html")))
        assertEquals(ProjectType.FOLDER, ProjectTypeDetector.detect(listOf("notes.txt")))
    }
}
