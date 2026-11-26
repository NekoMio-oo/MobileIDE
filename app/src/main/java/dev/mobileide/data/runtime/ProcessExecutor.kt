package dev.mobileide.data.runtime

import dev.mobileide.core.process.ProcessTree
import dev.mobileide.domain.runtime.ExecutionEvent
import dev.mobileide.domain.runtime.ExecutionRequest
import dev.mobileide.domain.runtime.RuntimeExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Runs a host command (optionally a PRoot command) as a child process and
 * streams its output line by line. Supports timeouts and cancellation; the
 * whole process tree is killed in both cases.
 *
 * Android's `Process` does not expose a pid, so the command is started through
 * a tiny shell trampoline that records `$$` in a temp file and then `exec`s the
 * real command (keeping the same pid). Cancelling a build therefore also stops
 * the compiler PRoot spawned, not just PRoot itself.
 */
class ProcessExecutor : RuntimeExecutor {

    override fun execute(request: ExecutionRequest): Flow<ExecutionEvent> = channelFlow {
        // A missing absolute executable is reported the same way ProcessBuilder
        // would report it, instead of letting the trampoline exit with 127.
        val executable = request.command.firstOrNull()
        if (executable != null && executable.startsWith("/") && !File(executable).exists()) {
            send(
                ExecutionEvent.Output(
                    true,
                    "Cannot run program \"$executable\": No such file or directory"
                )
            )
            send(ExecutionEvent.Exit(-1, false))
            return@channelFlow
        }

        val pidFile = ProcessTree.createPidFile(tempDirectory())

        val builder = ProcessBuilder(
            if (pidFile != null) {
                ProcessTree.wrapWithPidFile(request.command, pidFile)
            } else {
                request.command
            }
        )
        request.workingDirectory?.let { builder.directory(it) }
        builder.environment().putAll(request.environment)

        val process = try {
            builder.start()
        } catch (error: IOException) {
            send(ExecutionEvent.Output(true, error.message ?: "Could not start process"))
            send(ExecutionEvent.Exit(-1, false))
            pidFile?.delete()
            return@channelFlow
        }

        val readers = listOf(
            launch(Dispatchers.IO) { pump(process.inputStream, false, channel) },
            launch(Dispatchers.IO) { pump(process.errorStream, true, channel) }
        )

        try {
            // Poll so the timeout is cancellable; Process.waitFor() is a blocking
            // call that would ignore coroutine cancellation.
            val deadline = System.currentTimeMillis() + request.timeoutMs.coerceAtLeast(1_000L)
            while (process.isAlive && System.currentTimeMillis() < deadline) {
                delay(100L)
            }
            val timedOut = process.isAlive
            if (timedOut) ProcessTree.killTree(process, pidFile)
            val exit = withContext(Dispatchers.IO) { process.waitFor() }
            withTimeoutOrNull(2_000L) { readers.forEach { it.join() } }
            send(ExecutionEvent.Exit(code = if (timedOut) -1 else exit, timedOut = timedOut))
        } finally {
            readers.forEach { it.cancel() }
            if (process.isAlive) {
                ProcessTree.killTree(process, pidFile)
                runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            }
            pidFile?.delete()
        }
    }

    private fun tempDirectory(): File {
        val candidate = File(System.getProperty("java.io.tmpdir") ?: "")
        return if (candidate.isDirectory && candidate.canWrite()) candidate else File(".")
    }

    private suspend fun pump(
        stream: InputStream,
        stderr: Boolean,
        channel: SendChannel<ExecutionEvent>
    ) {
        try {
            BufferedReader(stream.reader(Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    channel.send(
                        ExecutionEvent.Output(stderr = stderr, line = line.trimEnd('\r'))
                    )
                }
            }
        } catch (_: IOException) {
            // The process was killed; nothing more to read.
        }
    }
}
