package dev.mobileide.data.build.providers

import dev.mobileide.data.build.BuildPaths
import dev.mobileide.domain.build.BuildContext
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildMode
import dev.mobileide.domain.build.BuildPlan
import dev.mobileide.domain.build.BuildProvider
import dev.mobileide.domain.build.BuildStep
import dev.mobileide.domain.build.DiagnosticDialect
import dev.mobileide.domain.build.GuestPaths

/**
 * `cargo build` with the target directory redirected into the project's build
 * folder. Dependency downloads and compile output stream through unchanged.
 */
class RustBuildProvider : BuildProvider {

    override val language: BuildLanguage = BuildLanguage.RUST

    override fun validate(context: BuildContext): String? {
        if (context.config.compiler.isBlank()) {
            return "cargo not found. Install the Rust toolchain in Runtimes."
        }
        if ("Cargo.toml" !in context.rootFiles) {
            return "Cargo.toml not found. Open a Cargo project to build with Rust."
        }
        return null
    }

    override fun plan(context: BuildContext): BuildPlan {
        val command = mutableListOf(context.config.compiler.ifBlank { "cargo" }, "build")
        if (context.config.mode == BuildMode.RELEASE) command += "--release"
        context.config.compileFlags.forEach { command += it }

        val profile = if (context.config.mode == BuildMode.RELEASE) "release" else "debug"
        return BuildPlan(
            steps = listOf(
                BuildStep(
                    title = "cargo build",
                    guestCommand = command,
                    environment = mapOf("CARGO_TARGET_DIR" to GuestPaths.of(BuildPaths.CARGO_DIR))
                )
            ),
            artifactCandidates = listOf("${BuildPaths.CARGO_DIR}/$profile"),
            dialect = DiagnosticDialect.RUST
        )
    }
}
