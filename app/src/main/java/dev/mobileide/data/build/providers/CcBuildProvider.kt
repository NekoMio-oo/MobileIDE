package dev.mobileide.data.build.providers

import dev.mobileide.domain.build.BuildContext
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildMode
import dev.mobileide.domain.build.BuildPlan
import dev.mobileide.domain.build.BuildProvider
import dev.mobileide.domain.build.BuildStandards
import dev.mobileide.domain.build.BuildStep
import dev.mobileide.domain.build.DiagnosticDialect
import dev.mobileide.domain.build.GuestPaths

/**
 * C and C++ share one implementation: same compiler family, same flag layout,
 * different executable and standard list. The compiler is resolved by the build
 * manager from what actually exists in the Linux environment.
 */
class CcBuildProvider(override val language: BuildLanguage) : BuildProvider {

    init {
        require(language == BuildLanguage.C || language == BuildLanguage.CPP) {
            "CcBuildProvider only supports C and C++"
        }
    }

    override fun validate(context: BuildContext): String? {
        val config = context.config
        if (context.sources.isEmpty()) return "No source files selected"
        validateOutputName(config.outputName)?.let { return it }
        if (config.compiler.isBlank()) {
            return "No ${language.displayName} compiler found. Install the toolchain in Runtimes."
        }
        if (config.standard.isNotBlank() && config.standard !in standards()) {
            return "Unsupported ${language.displayName} standard: ${config.standard}"
        }
        return null
    }

    override fun plan(context: BuildContext): BuildPlan {
        val config = context.config
        val command = mutableListOf(config.compiler)
        if (config.standard.isNotBlank()) command += "-std=${config.standard}"
        if (context.config.mode == BuildMode.DEBUG) {
            command += listOf("-O0", "-g")
        } else {
            command += listOf("-O2", "-DNDEBUG")
        }
        command += listOf("-o", GuestPaths.of(context.outputPath))
        orderedSources(context).forEach { command += GuestPaths.of(it) }
        config.includeDirs.forEach { dir ->
            command += "-I"
            command += GuestPaths.of(dir)
        }
        config.libraryDirs.forEach { dir ->
            command += "-L"
            command += GuestPaths.of(dir)
        }
        config.libraries.forEach { library ->
            command += if (library.startsWith("-l")) library else "-l$library"
        }
        config.compileFlags.forEach { command += it }
        config.linkFlags.forEach { command += it }

        return BuildPlan(
            steps = listOf(BuildStep(title = "Compile ${language.displayName}", guestCommand = command)),
            artifactCandidates = listOf(context.outputPath),
            dialect = DiagnosticDialect.CC
        )
    }

    private fun standards(): List<String> = BuildStandards.forLanguage(language)
}
