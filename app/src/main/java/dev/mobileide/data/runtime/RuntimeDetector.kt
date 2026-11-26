package dev.mobileide.data.runtime

import dev.mobileide.domain.runtime.ExecutionEvent
import dev.mobileide.domain.runtime.LanguageSpec
import dev.mobileide.domain.runtime.RuntimeExecutor
import kotlinx.coroutines.flow.collect

data class ToolDetection(
    val installed: Boolean,
    val version: String? = null,
    val message: String? = null
)

/**
 * Detects language toolchains inside the Linux environment. A missing toolchain
 * reports a concrete reason (command not found, proot failure, timeout) instead
 * of a generic "unavailable".
 */
class RuntimeDetector(
    private val linux: LinuxRuntime,
    private val executor: RuntimeExecutor
) {

    suspend fun detect(spec: LanguageSpec): ToolDetection {
        spec.unsupportedReason?.let { return ToolDetection(installed = false, message = it) }

        val tool = spec.toolName
        if (tool.isBlank() || tool.any { !(it.isLetterOrDigit() || it in "_+-.") }) {
            return ToolDetection(installed = false, message = "Invalid tool name")
        }
        if (!linux.isInstalled()) {
            return ToolDetection(installed = false, message = "Linux environment is not installed")
        }

        val arguments = spec.versionArgs.joinToString(" ")
        val script = "command -v $tool >/dev/null 2>&1 && $tool $arguments 2>&1 | head -n 1"
        val request = linux.buildExecution(
            guestCommand = listOf("/bin/sh", "-lc", script),
            timeoutMs = 25_000L
        ) ?: return ToolDetection(installed = false, message = "Linux environment is not installed")

        val output = StringBuilder()
        var exitCode = -1
        var timedOut = false
        executor.execute(request).collect { event ->
            when (event) {
                is ExecutionEvent.Output -> {
                    if (output.length < 4_096) output.appendLine(event.line)
                }

                is ExecutionEvent.Exit -> {
                    exitCode = event.code
                    timedOut = event.timedOut
                }
            }
        }

        if (timedOut) {
            return ToolDetection(installed = false, message = "Detection timed out")
        }
        val text = output.toString().trim()
        if (exitCode == 0 && text.isNotBlank()) {
            val version = text.lineSequence().firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(120)
            return ToolDetection(installed = true, version = version)
        }
        return ToolDetection(
            installed = false,
            message = if (text.isBlank()) "Toolchain not installed" else text.take(160)
        )
    }
}
