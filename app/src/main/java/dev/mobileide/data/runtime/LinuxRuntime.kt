package dev.mobileide.data.runtime

import dev.mobileide.domain.runtime.ExecutionRequest
import dev.mobileide.domain.runtime.ProcessSpec
import java.io.File

/**
 * Builds host commands that run inside the installed Debian rootfs through
 * PRoot. PRoot needs its loader and Termux libraries from the extracted tree;
 * they are passed through the child environment, never hardcoded.
 */
class LinuxRuntime(private val paths: RuntimePaths) {

    fun isInstalled(): Boolean =
        paths.prootBinary.isFile &&
            paths.prootLoader.isFile &&
            (paths.rootfsShell.isFile || paths.rootfsUsrShell.isFile)

    fun rootfsPath(): String = paths.rootfsDir.absolutePath

    /**
     * Device clocks often run ahead of the mirror, which makes apt reject a
     * Release file as expired even though its signature is valid. Disabling only
     * the expiry check keeps signature verification on and lets `apt update`
     * work inside the container.
     */
    fun ensureAptConfiguration(): Boolean {
        if (!isInstalled()) return false
        val directory = File(paths.rootfsDir, "etc/apt/apt.conf.d")
        if (!directory.isDirectory && !directory.mkdirs()) return false
        val file = File(directory, "99mobileide")
        val content = """
            // MobileIDE: allow apt to work when the device clock is ahead of
            // the archive. Package signatures are still verified.
            Acquire::Check-Valid-Until "false";
        """.trimIndent() + "\n"
        return runCatching {
            if (!file.isFile || file.readText() != content) file.writeText(content)
            true
        }.getOrDefault(false)
    }

    /**
     * Creates a directory inside the guest root, e.g. `/workspace`, so a bind
     * mount has a target. The rootfs is a plain directory on the host, so this
     * needs no PRoot run.
     */
    fun ensureGuestDir(relativePath: String): Boolean {
        val sanitized = relativePath.trim('/')
        if (sanitized.isEmpty() || sanitized.split('/').any { it == ".." }) return false
        val directory = File(paths.rootfsDir, sanitized)
        return directory.isDirectory || directory.mkdirs()
    }

    fun buildExecution(
        guestCommand: List<String>,
        guestWorkDir: String = "/root",
        fakeRoot: Boolean = false,
        bindPaths: List<String> = emptyList(),
        timeoutMs: Long = 120_000L,
        extraEnv: Map<String, String> = emptyMap()
    ): ExecutionRequest? {
        val spec = buildProcessSpec(
            guestCommand = guestCommand,
            guestWorkDir = guestWorkDir,
            fakeRoot = fakeRoot,
            bindPaths = bindPaths,
            extraEnv = extraEnv
        ) ?: return null
        return ExecutionRequest(
            command = spec.command,
            workingDirectory = File(paths.tempDir.absolutePath),
            environment = spec.environment,
            timeoutMs = timeoutMs
        )
    }

    /**
     * Same command as [buildExecution] but without the execution wrapper, for
     * callers that need a long-lived bidirectional pipe (the LSP client).
     */
    fun buildProcessSpec(
        guestCommand: List<String>,
        guestWorkDir: String = "/root",
        fakeRoot: Boolean = false,
        bindPaths: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap()
    ): ProcessSpec? {
        if (!isInstalled() || guestCommand.isEmpty()) return null
        paths.tempDir.mkdirs()

        val command = mutableListOf(
            paths.prootBinary.absolutePath,
            "--link2symlink",
            "-r", paths.rootfsDir.absolutePath
        )
        if (fakeRoot) command += "-0"
        command += listOf("-b", "/dev", "-b", "/proc", "-b", "/sys")
        bindPaths.forEach { command += listOf("-b", it) }
        command += listOf("-w", guestWorkDir, "/usr/bin/env", "-i")
        command += listOf(
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8"
        )
        extraEnv.forEach { (key, value) -> command += "$key=$value" }
        command += guestCommand

        val environment = mapOf(
            "PROOT_LOADER" to paths.prootLoader.absolutePath,
            "PROOT_TMP_DIR" to paths.tempDir.absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            "LD_LIBRARY_PATH" to paths.prootLibDir.absolutePath
        )
        return ProcessSpec(command = command, environment = environment)
    }
}
