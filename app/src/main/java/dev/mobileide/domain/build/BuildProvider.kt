package dev.mobileide.domain.build

/**
 * Guest-side mount point for the project. Build commands always run with
 * `/workspace` as the working directory and use project-relative paths, so
 * compiler diagnostics map straight back to editor paths.
 */
object GuestPaths {
    const val ROOT = "/workspace"

    fun of(relativePath: String): String {
        val trimmed = relativePath.trim('/')
        return if (trimmed.isEmpty()) ROOT else "$ROOT/$trimmed"
    }
}

enum class DiagnosticDialect {
    /** clang / gcc: `file:line:col: error: message`. */
    CC,
    /** javac: `file.java:line: error: message`. */
    JAVA,
    /** go: `file.go:line:col: message`. */
    GO,
    /** rustc: `error[E0308]: msg` followed by `--> file:line:col`. */
    RUST,
    /** tsc: `file.ts(line,col): error TS1234: message`. */
    TYPESCRIPT,
    NONE
}

data class BuildStep(
    val title: String,
    val guestCommand: List<String>,
    val environment: Map<String, String> = emptyMap()
)

data class BuildPlan(
    val steps: List<BuildStep>,
    /** Project-relative paths (files or folders) produced by a successful build. */
    val artifactCandidates: List<String>,
    val dialect: DiagnosticDialect
)

data class BuildContext(
    val config: BuildConfig,
    val sources: List<String>,
    /** Project-relative output path, always inside `build/`. */
    val outputPath: String,
    /** Names of regular files at the project root (go.mod, Cargo.toml, …). */
    val rootFiles: Set<String> = emptySet()
)

/**
 * Translates a build configuration into concrete compiler commands. Providers
 * never execute anything and never touch the runtime manager; that is the
 * build manager's job.
 */
interface BuildProvider {
    val language: BuildLanguage

    /** Human-readable problem, or null when the configuration is usable. */
    fun validate(context: BuildContext): String?

    fun plan(context: BuildContext): BuildPlan
}
