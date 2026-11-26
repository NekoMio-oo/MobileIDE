package dev.mobileide.data.build.providers

import dev.mobileide.data.build.BuildPaths
import dev.mobileide.domain.build.BuildContext
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildPlan
import dev.mobileide.domain.build.BuildProvider
import dev.mobileide.domain.build.BuildStandards
import dev.mobileide.domain.build.BuildStep
import dev.mobileide.domain.build.DiagnosticDialect
import dev.mobileide.domain.build.GuestPaths

/** `tsc` either through an existing tsconfig.json or with explicit sources. */
class TypeScriptBuildProvider : BuildProvider {

    override val language: BuildLanguage = BuildLanguage.TYPESCRIPT

    override fun validate(context: BuildContext): String? {
        if (context.config.compiler.isBlank()) {
            return "tsc not found. Install the TypeScript toolchain in Runtimes."
        }
        if ("tsconfig.json" !in context.rootFiles && context.sources.isEmpty()) {
            return "No TypeScript sources and no tsconfig.json"
        }
        return null
    }

    override fun plan(context: BuildContext): BuildPlan {
        val command = mutableListOf(context.config.compiler.ifBlank { "tsc" })
        if ("tsconfig.json" in context.rootFiles) {
            command += listOf("-p", ".")
        } else {
            orderedSources(context).forEach { command += GuestPaths.of(it) }
            command += listOf("--module", "commonjs")
            val target = context.config.target.ifBlank { defaultTarget }
            command += listOf("--target", target)
        }
        command += listOf("--outDir", GuestPaths.of(BuildPaths.JS_DIR))
        context.config.compileFlags.forEach { command += it }

        return BuildPlan(
            steps = listOf(BuildStep(title = "Compile TypeScript", guestCommand = command)),
            artifactCandidates = listOf(BuildPaths.JS_DIR),
            dialect = DiagnosticDialect.TYPESCRIPT
        )
    }

    private val defaultTarget = BuildStandards.defaultFor(BuildLanguage.TYPESCRIPT)
}
