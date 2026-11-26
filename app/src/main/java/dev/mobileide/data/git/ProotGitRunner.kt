package dev.mobileide.data.git

import dev.mobileide.core.process.ProcessTree
import dev.mobileide.data.runtime.LinuxRuntime
import dev.mobileide.domain.runtime.ProcessSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Runs the Debian `git` binary inside PRoot.
 *
 * This intentionally does not reuse ProcessExecutor: Git mixes `\r` progress on
 * stderr with NUL separated porcelain records on stdout, and a line-based
 * reader would either lose progress or hold a whole status in one string.
 *
 * Only the project directory is mounted at `/workspace`, so rev-parse cannot
 * discover or modify a repository outside the project. The same HOME and fake
 * root as the Linux terminal are used, giving both surfaces one configuration.
 */
class ProotGitRunner(
    private val linux: LinuxRuntime,
    private val paths: GitPaths
) : GitCommandRunner {

    override suspend fun run(
        projectPath: String?,
        args: List<String>,
        options: GitRunOptions
    ): GitCommandResult = withContext(Dispatchers.IO) {
        paths.ensureBaseDirs()
        if (!linux.isInstalled()) {
            return@withContext failure("The Linux environment is not installed")
        }

        val binds = mutableListOf<String>()
        val guestWorkDir: String
        if (projectPath != null) {
            if (projectPath.contains(':')) {
                return@withContext failure("Project paths containing ':' are not supported")
            }
            linux.ensureGuestDir("workspace")
            binds += "$projectPath:/workspace"
            guestWorkDir = "/workspace"
        } else {
            guestWorkDir = "/root"
        }
        binds += options.extraBinds

        val guestCommand = mutableListOf("/usr/bin/git")
        if (projectPath != null) {
            guestCommand += listOf("-C", "/workspace")
            guestCommand += listOf("-c", "safe.directory=/workspace")
            guestCommand += listOf("-c", "core.fileMode=false")
        }
        guestCommand += args

        val environment = buildMap {
            put("GIT_TERMINAL_PROMPT", "0")
            put("GIT_PAGER", "cat")
            put("PAGER", "cat")
            put("TERM", "dumb")
            put("LC_ALL", "C")
            put("GIT_OPTIONAL_LOCKS", if (options.readOnly) "0" else "1")
            putAll(options.extraEnv)
        }

        val spec = linux.buildProcessSpec(
            guestCommand = guestCommand,
            guestWorkDir = guestWorkDir,
            fakeRoot = true,
            bindPaths = binds,
            extraEnv = environment
        ) ?: return@withContext failure("The Linux environment cannot start")

        execute(spec, options)
    }

    private suspend fun execute(
        spec: ProcessSpec,
        options: GitRunOptions
    ): GitCommandResult = coroutineScope {
        val pidFile = ProcessTree.createPidFile(paths.tempDir)
        val hostCommand = if (pidFile != null) {
            ProcessTree.wrapWithPidFile(spec.command, pidFile)
        } else {
            spec.command
        }
        val builder = ProcessBuilder(hostCommand)
        builder.directory(paths.tempDir)
        builder.environment().clear()
        builder.environment().putAll(spec.environment)

        val process = try {
            builder.start()
        } catch (error: IOException) {
            pidFile?.delete()
            return@coroutineScope failure(error.message ?: "Could not start git")
        }

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        var stdoutTruncated = false
        var stderrTruncated = false

        val stdoutJob = launch(Dispatchers.IO) {
            stdoutTruncated = readBytes(process.inputStream, stdout, MAX_STDOUT_BYTES)
        }
        val stderrJob = launch(Dispatchers.IO) {
            stderrTruncated = readProgress(
                stream = process.errorStream,
                sink = stderr,
                maxBytes = MAX_STDERR_BYTES,
                onProgress = options.onProgress
            )
        }

        try {
            val deadline = System.currentTimeMillis() + options.timeoutMs.coerceAtLeast(1_000L)
            while (process.isAlive && System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                delay(100L)
            }
            val timedOut = process.isAlive
            if (timedOut) ProcessTree.killTree(process, pidFile)
            val exit = withContext(Dispatchers.IO) { process.waitFor() }
            withTimeoutOrNull(READER_JOIN_TIMEOUT_MS) {
                stdoutJob.join()
                stderrJob.join()
            }
            GitCommandResult(
                exitCode = if (timedOut) -1 else exit,
                stdout = stdout.toString(Charsets.UTF_8.name()),
                stderr = stderr.toString(Charsets.UTF_8.name()),
                timedOut = timedOut,
                stdoutTruncated = stdoutTruncated,
                stderrTruncated = stderrTruncated
            )
        } finally {
            stdoutJob.cancel()
            stderrJob.cancel()
            if (process.isAlive) {
                ProcessTree.killTree(process, pidFile)
                runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            }
            pidFile?.delete()
        }
    }

    private fun failure(message: String): GitCommandResult = GitCommandResult(
        exitCode = -1,
        stdout = "",
        stderr = message
    )

    /** Keeps the first [maxBytes] bytes and drains the stream to avoid a stuck child. */
    private fun readBytes(
        stream: InputStream,
        sink: ByteArrayOutputStream,
        maxBytes: Int
    ): Boolean {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var written = 0
        var truncated = false
        while (true) {
            val count = try {
                stream.read(buffer)
            } catch (_: IOException) {
                -1
            }
            if (count < 0) break
            if (written < maxBytes) {
                val allowed = minOf(count, maxBytes - written)
                sink.write(buffer, 0, allowed)
                written += allowed
                if (allowed < count) truncated = true
            } else {
                truncated = true
            }
        }
        return truncated
    }

    /**
     * Stores stderr and emits progress segments. Git uses either `\n` or `\r`
     * for progress, and the final segment may not be terminated at all.
     */
    private fun readProgress(
        stream: InputStream,
        sink: ByteArrayOutputStream,
        maxBytes: Int,
        onProgress: ((String) -> Unit)?
    ): Boolean {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        val segment = ByteArrayOutputStream()
        var written = 0
        var truncated = false

        fun emit() {
            if (segment.size() == 0) return
            val text = segment.toString(Charsets.UTF_8.name()).trim()
            segment.reset()
            if (text.isNotEmpty()) onProgress?.invoke(text)
        }

        while (true) {
            val count = try {
                stream.read(buffer)
            } catch (_: IOException) {
                -1
            }
            if (count < 0) break
            for (index in 0 until count) {
                val byte = buffer[index]
                if (written < maxBytes) {
                    sink.write(byte.toInt())
                    written++
                } else {
                    truncated = true
                }
                when (byte.toInt()) {
                    '\n'.code, '\r'.code -> emit()
                    else -> {
                        segment.write(byte.toInt())
                        if (segment.size() >= MAX_PROGRESS_SEGMENT_BYTES) emit()
                    }
                }
            }
        }
        emit()
        return truncated
    }

    private companion object {
        const val READ_BUFFER_BYTES = 8 * 1024
        const val MAX_STDOUT_BYTES = 4 * 1024 * 1024
        const val MAX_STDERR_BYTES = 512 * 1024
        const val MAX_PROGRESS_SEGMENT_BYTES = 4 * 1024
        const val READER_JOIN_TIMEOUT_MS = 2_000L
    }
}
