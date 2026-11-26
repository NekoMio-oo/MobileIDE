package dev.mobileide.feature.build.model

import dev.mobileide.domain.build.BuildConfig
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildMode
import dev.mobileide.domain.build.BuildStandards

data class BuildConfigUiState(
    val isLoading: Boolean = true,
    val isProbing: Boolean = false,
    val isSaving: Boolean = false,
    val language: BuildLanguage = BuildLanguage.C,
    val mode: BuildMode = BuildMode.DEBUG,
    val compiler: String = "",
    val compilers: List<String> = emptyList(),
    val standard: String = "",
    val outputName: String = "",
    val sourcesAuto: Boolean = true,
    val sources: List<String> = emptyList(),
    val selectedSources: Set<String> = emptySet(),
    val entryFile: String = "",
    val includeDirs: String = "",
    val libraryDirs: String = "",
    val libraries: String = "",
    val compileFlags: String = "",
    val linkFlags: String = "",
    val classpath: String = "",
    val target: String = "",
    val error: String? = null,
    val saved: Boolean = false
) {
    val standards: List<String> get() = BuildStandards.forLanguage(language)
    val supportsClasspath: Boolean
        get() = language == BuildLanguage.JAVA || language == BuildLanguage.KOTLIN

    fun toConfig(): BuildConfig = BuildConfig(
        language = language,
        mode = mode,
        compiler = compiler,
        standard = standard,
        sourcesAuto = sourcesAuto,
        sources = if (sourcesAuto) emptyList() else selectedSources.sorted(),
        entryFile = entryFile.takeIf { it.isNotBlank() },
        outputName = outputName.trim(),
        includeDirs = lines(includeDirs),
        libraryDirs = lines(libraryDirs),
        libraries = lines(libraries),
        compileFlags = lines(compileFlags),
        linkFlags = lines(linkFlags),
        classpath = lines(classpath),
        target = target
    )

    private fun lines(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
}
