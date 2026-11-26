package dev.mobileide.domain.build

import kotlinx.coroutines.flow.StateFlow

sealed interface BuildActionResult {
    data object Success : BuildActionResult
    data class Failure(val message: String) : BuildActionResult
}

sealed interface StartBuildResult {
    data object Started : StartBuildResult

    /** Another build (same or different project) is already running. */
    data object Busy : StartBuildResult
    data class Failure(val message: String) : StartBuildResult
}

/**
 * Single entry point for build configuration, execution and artifacts. The UI
 * only talks to this interface; it never runs compiler commands itself.
 */
interface BuildRepository {

    fun observe(projectId: Long): StateFlow<BuildSnapshot>

    /** Loads configuration, history and the real toolchain state. */
    suspend fun load(projectId: Long)

    suspend fun saveConfig(projectId: Long, config: BuildConfig): BuildActionResult

    /** Re-probes the toolchain inside the Linux environment. */
    suspend fun refreshToolchain(projectId: Long)

    /** Compilers actually present for [language]; empty when none is installed. */
    suspend fun availableCompilers(projectId: Long, language: BuildLanguage): List<String>

    /** Files matching the language, excluding build output and vendored trees. */
    suspend fun availableSources(projectId: Long, language: BuildLanguage): List<String>

    fun startBuild(projectId: Long): StartBuildResult

    fun cancelBuild(projectId: Long)

    /** Clears the in-memory log view; the log file on disk is untouched. */
    fun clearLogs(projectId: Long)

    fun clearMessage(projectId: Long)

    suspend fun deleteArtifact(projectId: Long, path: String): BuildActionResult

    /** Loads a finished task's full log into the log view. */
    suspend fun openTaskLog(projectId: Long, taskId: String): BuildActionResult
}
