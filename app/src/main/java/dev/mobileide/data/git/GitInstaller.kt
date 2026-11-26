package dev.mobileide.data.git

import dev.mobileide.data.runtime.LinuxRuntime
import dev.mobileide.domain.git.GitInstallResult
import dev.mobileide.domain.runtime.ExecutionEvent
import dev.mobileide.domain.runtime.RuntimeExecutor

/** Installs the Git client into the existing Debian userland. */
interface GitInstaller {
    suspend fun install(onProgress: (String) -> Unit): GitInstallResult
}

/**
 * apt based installer. It reuses the same fake root, apt expiry workaround and
 * streaming executor as the runtime toolchains, so Git and the terminal share
 * one environment and one configuration.
 */
class ProotGitInstaller(
    private val linux: LinuxRuntime,
    private val executor: RuntimeExecutor
) : GitInstaller {

    override suspend fun install(onProgress: (String) -> Unit): GitInstallResult {
        if (!linux.isInstalled()) {
            return GitInstallResult(
                success = false,
                message = "Install the Linux environment first"
            )
        }
        linux.ensureAptConfiguration()

        val script = """
            export DEBIAN_FRONTEND=noninteractive
            apt-get -o Acquire::Check-Valid-Until=false update &&
            apt-get install -y --no-install-recommends git openssh-client ca-certificates
        """.trimIndent()

        val request = linux.buildExecution(
            guestCommand = listOf("/bin/sh", "-lc", script),
            fakeRoot = true,
            timeoutMs = INSTALL_TIMEOUT_MS
        ) ?: return GitInstallResult(
            success = false,
            message = "Could not build the install command"
        )

        val tail = ArrayDeque<String>()
        var exitCode = -1
        var timedOut = false
        executor.execute(request).collect { event ->
            when (event) {
                is ExecutionEvent.Output -> {
                    onProgress(event.line)
                    tail.addLast(event.line)
                    if (tail.size > MAX_ERROR_LINES) tail.removeFirst()
                }

                is ExecutionEvent.Exit -> {
                    exitCode = event.code
                    timedOut = event.timedOut
                }
            }
        }

        if (exitCode == 0) {
            return GitInstallResult(success = true, message = "Git installed")
        }
        return GitInstallResult(
            success = false,
            message = if (timedOut) "Git installation timed out" else "Git installation failed",
            detail = tail.joinToString("\n").takeLast(MAX_ERROR_CHARS)
        )
    }

    private companion object {
        const val INSTALL_TIMEOUT_MS = 30 * 60 * 1000L
        const val MAX_ERROR_LINES = 40
        const val MAX_ERROR_CHARS = 4_000
    }
}
