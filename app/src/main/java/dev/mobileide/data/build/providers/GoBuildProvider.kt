package dev.mobileide.data.build.providers

import dev.mobileide.domain.build.BuildContext
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildPlan
import dev.mobileide.domain.build.BuildProvider
import dev.mobileide.domain.build.BuildStep
import dev.mobileide.domain.build.DiagnosticDialect
import dev.mobileide.domain.build.GuestPaths

/**
 * `go build` for a module. The only target that can run inside this environment
 * is linux/arm64, so those values are passed explicitly instead of offering a
 * fake architecture picker.
 */
class GoBuildProvider : BuildProvider {

    override val language: BuildLanguage = BuildLanguage.GO

    override fun validate(context: BuildContext): String? {
        validateOutputName(context.config.outputName)?.let { return it }
        if (context.config.compiler.isBlank()) {
            return "go not found. Install the Go toolchain in Runtimes."
        }
        if ("go.mod" !in context.rootFiles) {
            return "go.mod not found. Go modules are required; run `go mod init` in a terminal first."
        }
        return null
    }

    override fun plan(context: BuildContext): BuildPlan {
        val command = mutableListOf(
            context.config.compiler.ifBlank { "go" },
            "build",
            "-trimpath",
            "-buildvcs=false",
            "-o", GuestPaths.of(context.outputPath),
            "."
        )
        context.config.compileFlags.forEach { command += it }

        return BuildPlan(
            steps = listOf(
                BuildStep(
                    title = "Build Go package",
                    guestCommand = command,
                    environment = mapOf(
                        "GOOS" to "linux",
                        "GOARCH" to "arm64",
                        "CGO_ENABLED" to "0",
                        "GO111MODULE" to "on"
                    )
                )
            ),
            artifactCandidates = listOf(context.outputPath),
            dialect = DiagnosticDialect.GO
        )
    }

    companion object {
        /** The only target this runtime can produce and execute. */
        const val TARGET = "linux/arm64"
    }
}
