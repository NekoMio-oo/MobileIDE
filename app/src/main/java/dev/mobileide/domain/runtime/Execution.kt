package dev.mobileide.domain.runtime

import kotlinx.coroutines.flow.Flow
import java.io.File

data class ExecutionRequest(
    val command: List<String>,
    val workingDirectory: File? = null,
    val environment: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 120_000
)

/**
 * Host argv plus the environment a child needs to run inside the PRoot
 * userland. Shared by the build system, the terminal and the LSP runtime so the
 * PRoot invocation exists in exactly one place.
 */
data class ProcessSpec(
    val command: List<String>,
    val environment: Map<String, String>
)

sealed interface ExecutionEvent {
    data class Output(val stderr: Boolean, val line: String) : ExecutionEvent
    data class Exit(val code: Int, val timedOut: Boolean) : ExecutionEvent
}

/** Unified execution surface for the runtime manager, build system and terminal. */
interface RuntimeExecutor {
    fun execute(request: ExecutionRequest): Flow<ExecutionEvent>
}
