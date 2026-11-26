package dev.mobileide.domain.build

import dev.mobileide.domain.model.ProjectType
import dev.mobileide.domain.runtime.RuntimeId

/**
 * Languages the build system understands. [runtimeId] links a language to the
 * toolchain managed by the runtime layer; [sourceExtensions] is what the source
 * scanner looks for.
 */
enum class BuildLanguage(
    val displayName: String,
    val runtimeId: RuntimeId,
    val projectType: ProjectType,
    val sourceExtensions: Set<String>
) {
    C("C", RuntimeId.C, ProjectType.C, setOf("c")),
    CPP("C++", RuntimeId.CPP, ProjectType.CPP, setOf("cpp", "cc", "cxx", "c++")),
    JAVA("Java", RuntimeId.JAVA, ProjectType.JAVA, setOf("java")),
    GO("Go", RuntimeId.GO, ProjectType.GO, setOf("go")),
    RUST("Rust", RuntimeId.RUST, ProjectType.RUST, setOf("rs")),
    TYPESCRIPT("TypeScript", RuntimeId.TYPESCRIPT, ProjectType.TYPESCRIPT, setOf("ts", "tsx")),
    KOTLIN("Kotlin", RuntimeId.KOTLIN, ProjectType.KOTLIN, setOf("kt", "kts")),
    PYTHON("Python", RuntimeId.PYTHON, ProjectType.PYTHON, setOf("py")),
    JAVASCRIPT("JavaScript", RuntimeId.NODEJS, ProjectType.JAVASCRIPT, setOf("js", "mjs", "cjs")),
    HTML("HTML / CSS", RuntimeId.HTML, ProjectType.WEB, setOf("html", "htm", "css"));

    /** False for languages that are edited here but have no compile step. */
    val hasBuildStep: Boolean
        get() = this in BUILDABLE

    companion object {
        val BUILDABLE = setOf(C, CPP, JAVA, GO, RUST, TYPESCRIPT)

        fun fromProjectType(type: ProjectType): BuildLanguage? =
            entries.firstOrNull { it.projectType == type }

        fun fromId(id: String): BuildLanguage? = entries.firstOrNull { it.name == id }
    }
}

enum class BuildMode(val displayName: String) {
    DEBUG("Debug"),
    RELEASE("Release");

    val key: String get() = name.lowercase()
}

enum class BuildState {
    IDLE,
    PREPARING,
    COMPILING,
    SUCCEEDED,
    FAILED,
    CANCELLED,

    /** The app died while this task was running; it never finished. */
    INTERRUPTED;

    val isRunning: Boolean get() = this == PREPARING || this == COMPILING
    val isFinished: Boolean get() = this == SUCCEEDED || this == FAILED ||
        this == CANCELLED || this == INTERRUPTED
}

enum class BuildLogLevel {
    INFO,
    COMMAND,
    WARNING,
    ERROR
}

data class BuildLogLine(
    val text: String,
    val level: BuildLogLevel = BuildLogLevel.INFO
)

enum class BuildProblemSeverity {
    ERROR,
    WARNING
}

/** One parsed compiler diagnostic. [path] is project-relative when resolvable. */
data class BuildProblem(
    val severity: BuildProblemSeverity,
    val path: String?,
    val line: Int,
    val column: Int?,
    val message: String,
    val raw: String
)

data class BuildArtifact(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long
)

/**
 * Build configuration for one project. Every path is project-relative; absolute
 * paths and `..` are rejected before a command is generated.
 */
data class BuildConfig(
    val language: BuildLanguage = BuildLanguage.C,
    val mode: BuildMode = BuildMode.DEBUG,
    val compiler: String = "",
    val standard: String = "",
    /** Empty means "scan the project for source files of [language]". */
    val sourcesAuto: Boolean = true,
    val sources: List<String> = emptyList(),
    val entryFile: String? = null,
    val outputName: String = "",
    val includeDirs: List<String> = emptyList(),
    val libraryDirs: List<String> = emptyList(),
    val libraries: List<String> = emptyList(),
    val compileFlags: List<String> = emptyList(),
    val linkFlags: List<String> = emptyList(),
    val classpath: List<String> = emptyList(),
    /** TypeScript target (ES2017, ES2020, …); unused by other languages. */
    val target: String = ""
)

data class BuildTask(
    val id: String,
    val projectId: Long,
    val language: BuildLanguage,
    val mode: BuildMode,
    val state: BuildState,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val durationMs: Long? = null,
    val exitCode: Int? = null,
    val errorCount: Int = 0,
    val warningCount: Int = 0,
    val artifacts: List<BuildArtifact> = emptyList(),
    val outputPath: String? = null,
    val failureMessage: String? = null
) {
    val isSuccess: Boolean get() = state == BuildState.SUCCEEDED
}

enum class ToolchainStatus {
    UNKNOWN,
    READY,
    LINUX_MISSING,
    TOOLCHAIN_MISSING,
    UNSUPPORTED
}

data class ToolchainInfo(
    val status: ToolchainStatus = ToolchainStatus.UNKNOWN,
    val language: BuildLanguage = BuildLanguage.C,
    val compilers: List<String> = emptyList(),
    val version: String? = null,
    val message: String? = null
)

data class BuildSnapshot(
    val projectId: Long = 0L,
    val projectName: String = "",
    val isLoading: Boolean = true,
    val config: BuildConfig = BuildConfig(),
    val toolchain: ToolchainInfo = ToolchainInfo(),
    val task: BuildTask? = null,
    val logs: List<BuildLogLine> = emptyList(),
    val logTruncated: Boolean = false,
    val problems: List<BuildProblem> = emptyList(),
    val artifacts: List<BuildArtifact> = emptyList(),
    val history: List<BuildTask> = emptyList(),
    val isBuilding: Boolean = false,
    val runningProjectId: Long? = null,
    val message: String? = null,
    val error: String? = null
)
