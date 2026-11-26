package dev.mobileide.data.terminal

import android.os.ParcelFileDescriptor
import dev.mobileide.core.process.PtyNative
import dev.mobileide.domain.runtime.ExecutionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * One interactive child process attached to a pseudo terminal. Unlike
 * [dev.mobileide.domain.runtime.RuntimeExecutor] this is long lived: the shell
 * stays alive and every keystroke is written to the same PTY.
 */
class TerminalProcess private constructor(
    private val handle: PtyNative.Handle,
    private val pfd: ParcelFileDescriptor,
    private val input: FileOutputStream,
    private val output: FileInputStream
) {

    val pid: Int get() = handle.pid

    private val writeLock = Any()

    @Volatile
    private var closed = false

    fun write(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        synchronized(writeLock) {
            runCatching {
                input.write(bytes)
                input.flush()
            }
        }
    }

    fun resize(rows: Int, cols: Int) {
        PtyNative.resize(handle.masterFd, handle.pid, rows, cols)
    }

    /** Blocking read from the master side; -1 means the child closed the PTY. */
    fun read(buffer: ByteArray): Int = output.read(buffer)

    suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { PtyNative.waitFor(handle.pid) }

    fun signal(signal: Int) {
        PtyNative.kill(handle.pid, signal)
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { pfd.close() }
    }

    companion object {
        const val SIGTERM = 15
        const val SIGKILL = 9
        const val SIGWINCH = 28

        fun start(request: ExecutionRequest, rows: Int, cols: Int): Result<TerminalProcess> {
            val command = request.command
            if (command.isEmpty()) {
                return Result.failure(IllegalArgumentException("Empty terminal command"))
            }
            val cwd = request.workingDirectory?.absolutePath ?: "/"
            val handle = PtyNative.open(
                program = command.first(),
                arguments = command.drop(1),
                environment = request.environment,
                workingDirectory = cwd,
                rows = rows,
                cols = cols
            ) ?: return Result.failure(IOException(PtyNative.lastError ?: "Could not start the terminal"))

            return runCatching {
                val pfd = ParcelFileDescriptor.adoptFd(handle.masterFd)
                TerminalProcess(
                    handle = handle,
                    pfd = pfd,
                    input = FileOutputStream(pfd.fileDescriptor),
                    output = FileInputStream(pfd.fileDescriptor)
                )
            }
        }
    }
}
