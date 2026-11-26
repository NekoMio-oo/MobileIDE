package dev.mobileide.domain.runtime

import kotlinx.coroutines.flow.StateFlow

sealed interface RuntimeActionResult {
    data object Success : RuntimeActionResult
    data class Failure(val message: String) : RuntimeActionResult
}

data class RuntimeSnapshot(
    val isLoading: Boolean = true,
    val isBusy: Boolean = false,
    val runtimes: List<RuntimeInfo> = emptyList(),
    val progress: RuntimeProgress? = null,
    val logs: List<String> = emptyList(),
    val message: String? = null,
    val error: String? = null
)

/**
 * Single entry point for installing, checking and removing local runtimes.
 * The build system and terminal reuse this instead of running commands directly.
 */
interface RuntimeRepository {

    val state: StateFlow<RuntimeSnapshot>

    suspend fun refresh()

    suspend fun installLinux(): RuntimeActionResult

    suspend fun uninstallLinux(): RuntimeActionResult

    suspend fun verifyLinux(): RuntimeActionResult

    suspend fun installToolchain(id: RuntimeId): RuntimeActionResult

    suspend fun checkToolchain(id: RuntimeId): RuntimeActionResult

    fun clearMessage()

    fun clearError()

    fun clearLogs()
}
