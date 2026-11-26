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

/** javac compilation followed by `jar` packaging, both inside the Linux guest. */
class JavaBuildProvider : BuildProvider {

    override val language: BuildLanguage = BuildLanguage.JAVA

    override fun validate(context: BuildContext): String? {
        if (context.sources.isEmpty()) return "No Java source files selected"
        validateOutputName(context.outputPath.substringAfterLast('/'))?.let { return it }
        if (context.config.compiler.isBlank()) {
            return "javac not found. Install the Java toolchain in Runtimes."
        }
        val standard = context.config.standard
        if (standard.isNotBlank() && standard !in BuildStandards.JAVA) return "Unsupported release: $standard"
        return null
    }

    override fun plan(context: BuildContext): BuildPlan {
        val config = context.config
        val javac = mutableListOf(config.compiler.ifBlank { "javac" })
        javac += listOf("-encoding", "UTF-8")
        javac += listOf("-d", GuestPaths.of(BuildPaths.CLASSES_DIR))
        if (config.standard.isNotBlank()) javac += listOf("--release", config.standard)
        if (config.classpath.isNotEmpty()) {
            javac += "-cp"
            javac += config.classpath.joinToString(":") { GuestPaths.of(it) }
        }
        config.compileFlags.forEach { javac += it }
        orderedSources(context).forEach { javac += GuestPaths.of(it) }

        val jarRelative = context.outputPath.removeSuffix(".jar") + ".jar"
        val jar = GuestPaths.of(jarRelative)
        val packageJar = mutableListOf(
            "jar", "--create", "--file", jar,
            "-C", GuestPaths.of(BuildPaths.CLASSES_DIR), "."
        )

        return BuildPlan(
            steps = listOf(
                BuildStep(title = "Compile Java", guestCommand = javac),
                BuildStep(title = "Package JAR", guestCommand = packageJar)
            ),
            artifactCandidates = listOf(jarRelative),
            dialect = DiagnosticDialect.JAVA
        )
    }

}
