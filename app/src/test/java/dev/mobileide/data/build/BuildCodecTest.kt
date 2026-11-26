package dev.mobileide.data.build

import dev.mobileide.domain.build.BuildArtifact
import dev.mobileide.domain.build.BuildConfig
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildMode
import dev.mobileide.domain.build.BuildState
import dev.mobileide.domain.build.BuildTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildCodecTest {

    @Test
    fun `build config survives a round trip`() {
        val config = BuildConfig(
            language = BuildLanguage.CPP,
            mode = BuildMode.RELEASE,
            compiler = "g++",
            standard = "c++17",
            sourcesAuto = false,
            sources = listOf("main file.cpp", "src/util.cpp"),
            entryFile = "main file.cpp",
            outputName = "demo",
            includeDirs = listOf("include", "third party"),
            libraryDirs = listOf("libs"),
            libraries = listOf("m"),
            compileFlags = listOf("-Wall", "-Wextra"),
            linkFlags = listOf("-s"),
            classpath = listOf("libs/a.jar"),
            target = "ES2020"
        )

        val decoded = BuildConfigCodec.parse(BuildConfigCodec.encode(config))

        assertEquals(config, decoded)
    }

    @Test
    fun `missing config falls back to usable defaults`() {
        val decoded = BuildConfigCodec.parse("")

        assertEquals(BuildLanguage.C, decoded.language)
        assertEquals(BuildMode.DEBUG, decoded.mode)
        assertEquals("app", decoded.outputName)
        assertTrue(decoded.sourcesAuto)
        assertTrue(decoded.sources.isEmpty())
        assertNull(decoded.entryFile)
    }

    @Test
    fun `build task survives a round trip with artifacts`() {
        val task = BuildTask(
            id = "20261126-120000-001",
            projectId = 7L,
            language = BuildLanguage.C,
            mode = BuildMode.DEBUG,
            state = BuildState.FAILED,
            startedAt = 1_900_000_000_000L,
            finishedAt = 1_900_000_003_500L,
            durationMs = 3_500L,
            exitCode = 1,
            errorCount = 2,
            warningCount = 3,
            artifacts = listOf(
                BuildArtifact(
                    path = "build/debug/app",
                    name = "app",
                    sizeBytes = 12_345L,
                    modifiedAt = 1_900_000_003_000L
                )
            ),
            outputPath = "build/debug/app",
            failureMessage = "Build failed with exit code 1"
        )

        val decoded = BuildTaskCodec.decode(BuildTaskCodec.encode(task))

        assertEquals(task.id, decoded.id)
        assertEquals(task.language, decoded.language)
        assertEquals(task.mode, decoded.mode)
        assertEquals(task.state, decoded.state)
        assertEquals(task.startedAt, decoded.startedAt)
        assertEquals(task.durationMs, decoded.durationMs)
        assertEquals(task.exitCode, decoded.exitCode)
        assertEquals(task.errorCount, decoded.errorCount)
        assertEquals(task.warningCount, decoded.warningCount)
        assertEquals(task.artifacts, decoded.artifacts)
        assertEquals(task.outputPath, decoded.outputPath)
        assertEquals(task.failureMessage, decoded.failureMessage)
    }

    @Test
    fun `successful task has no failure message after decoding`() {
        val task = BuildTask(
            id = "id",
            projectId = 1L,
            language = BuildLanguage.GO,
            mode = BuildMode.RELEASE,
            state = BuildState.SUCCEEDED,
            startedAt = 10L,
            finishedAt = 20L,
            durationMs = 10L,
            exitCode = 0
        )
        val decoded = BuildTaskCodec.decode(BuildTaskCodec.encode(task))
        assertNull(decoded.failureMessage)
        assertNull(decoded.outputPath)
        assertFalse(decoded.artifacts.isNotEmpty())
    }
}
