package dev.mobileide.feature.build

import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildMode
import dev.mobileide.feature.build.model.BuildConfigUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigUiStateTest {

    @Test
    fun `automatic sources are not persisted as an explicit list`() {
        val state = BuildConfigUiState(
            sourcesAuto = true,
            sources = listOf("b.c", "a.c"),
            selectedSources = setOf("b.c", "a.c")
        )

        assertTrue(state.toConfig().sources.isEmpty())
    }

    @Test
    fun `manual sources are sorted before saving`() {
        val state = BuildConfigUiState(
            sourcesAuto = false,
            selectedSources = setOf("main.c", "util.c", "a.c")
        )

        assertEquals(listOf("a.c", "main.c", "util.c"), state.toConfig().sources)
    }

    @Test
    fun `multiline fields are trimmed and blank lines dropped`() {
        val state = BuildConfigUiState(
            includeDirs = " include \n\n src/inc \n",
            libraries = " m \n pthread ",
            compileFlags = "-O2\n\n-Wall"
        )

        val config = state.toConfig()

        assertEquals(listOf("include", "src/inc"), config.includeDirs)
        assertEquals(listOf("m", "pthread"), config.libraries)
        assertEquals(listOf("-O2", "-Wall"), config.compileFlags)
    }

    @Test
    fun `blank entry file becomes null and output name is trimmed`() {
        val config = BuildConfigUiState(entryFile = "  ", outputName = " app ").toConfig()

        assertNull(config.entryFile)
        assertEquals("app", config.outputName)
    }

    @Test
    fun `selected language, mode and target are carried into the config`() {
        val config = BuildConfigUiState(
            language = BuildLanguage.TYPESCRIPT,
            mode = BuildMode.RELEASE,
            target = "ES2020"
        ).toConfig()

        assertEquals(BuildLanguage.TYPESCRIPT, config.language)
        assertEquals(BuildMode.RELEASE, config.mode)
        assertEquals("ES2020", config.target)
    }

    @Test
    fun `classpath is only offered for jvm languages`() {
        assertTrue(BuildConfigUiState(language = BuildLanguage.JAVA).supportsClasspath)
        assertTrue(BuildConfigUiState(language = BuildLanguage.KOTLIN).supportsClasspath)
        assertFalse(BuildConfigUiState(language = BuildLanguage.C).supportsClasspath)
        assertFalse(BuildConfigUiState(language = BuildLanguage.GO).supportsClasspath)
    }
}
