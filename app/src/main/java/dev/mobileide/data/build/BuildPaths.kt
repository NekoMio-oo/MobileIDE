package dev.mobileide.data.build

import dev.mobileide.domain.build.BuildMode

/**
 * Layout of everything the build system owns inside a project. Outputs live
 * under `build/`; configuration, task metadata and logs under `.mobileide/`.
 * Both folders are visible in the project file browser.
 */
object BuildPaths {
    const val CONFIG_FILE = ".mobileide/build.properties"
    const val TASK_DIR = ".mobileide/tasks"
    const val LOG_DIR = ".mobileide/logs"

    const val BUILD_DIR = "build"
    const val CLASSES_DIR = "build/classes"
    const val CARGO_DIR = "build/cargo"
    const val JS_DIR = "build/js"

    /** Hard cap for one log file; older lines are dropped from the tail. */
    const val MAX_LOG_BYTES = 2L * 1024 * 1024

    fun outputDir(mode: BuildMode): String = "$BUILD_DIR/${mode.key}"

    fun taskPath(taskId: String): String = "$TASK_DIR/$taskId.properties"

    fun logPath(taskId: String): String = "$LOG_DIR/$taskId.log"

    /**
     * Normalizes a project-relative path from the configuration. Absolute
     * paths, drive letters, backslashes and `..` are rejected so a configuration
     * can never point outside the project.
     */
    fun sanitizeRelative(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("/") || trimmed.startsWith("~")) return null
        if (trimmed.contains('\\')) return null
        if (trimmed.contains(':')) return null
        val parts = trimmed.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** True when [path] is inside `build/` (the only writable output root). */
    fun isInsideBuild(path: String): Boolean =
        path == BUILD_DIR || path.startsWith("$BUILD_DIR/")
}
