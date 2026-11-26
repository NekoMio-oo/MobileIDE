package dev.mobileide.core.process

/**
 * Minimal JNI bridge to `forkpty(3)`.
 *
 * The master fd is handed back to Java and wrapped in streams, so read/write
 * never cross the JNI boundary. Only process creation, window size, signalling
 * and reaping live in native code.
 */
object PtyNative {

    data class Handle(val masterFd: Int, val pid: Int)

    private val loaded: Boolean = runCatching { System.loadLibrary("mobileide-pty") }.isSuccess

    fun isAvailable(): Boolean = loaded

    /**
     * Starts [program] on a new pseudo terminal. Returns null when the library
     * is missing or `forkpty` failed; [lastError] then carries the reason.
     */
    fun open(
        program: String,
        arguments: List<String>,
        environment: Map<String, String>,
        workingDirectory: String,
        rows: Int,
        cols: Int
    ): Handle? {
        if (!loaded) {
            lastError = "Native PTY library is not available in this build"
            return null
        }
        val env = environment.map { (key, value) -> "$key=$value" }.toTypedArray()
        val result = runCatching {
            nativeOpen(
                program,
                arguments.toTypedArray(),
                env,
                workingDirectory,
                rows.coerceAtLeast(1),
                cols.coerceAtLeast(1)
            )
        }.getOrNull()
        if (result == null || result.size < 2 || result[0] < 0) {
            val code = result?.getOrNull(1) ?: -1
            lastError = if (code > 0) {
                "forkpty failed (errno $code): ${errnoText(code)}"
            } else {
                "forkpty failed"
            }
            return null
        }
        lastError = null
        return Handle(masterFd = result[0], pid = result[1])
    }

    /** Reason for the last [open] failure. */
    var lastError: String? = null
        private set

    fun resize(fd: Int, pid: Int, rows: Int, cols: Int) {
        if (!loaded) return
        runCatching { nativeResize(fd, pid, rows.coerceAtLeast(1), cols.coerceAtLeast(1)) }
    }

    /** Blocks until the child exits and reaps it. Returns the exit code or -1. */
    fun waitFor(pid: Int): Int = if (loaded) runCatching { nativeWait(pid) }.getOrDefault(-1) else -1

    /** Sends [signal] to the child's process group (the child is a session leader). */
    fun kill(pid: Int, signal: Int) {
        if (!loaded) return
        runCatching { nativeKill(pid, signal) }
    }

    private fun errnoText(code: Int): String = when (code) {
        1 -> "Operation not permitted"
        2 -> "No such file or directory"
        5 -> "I/O error"
        6 -> "No such device or address"
        12 -> "Out of memory"
        13 -> "Permission denied"
        19 -> "No such device"
        24 -> "Too many open files"
        else -> "error"
    }

    private external fun nativeOpen(
        program: String,
        arguments: Array<String>,
        environment: Array<String>,
        workingDirectory: String,
        rows: Int,
        cols: Int
    ): IntArray

    private external fun nativeResize(fd: Int, pid: Int, rows: Int, cols: Int)

    private external fun nativeWait(pid: Int): Int

    private external fun nativeKill(pid: Int, signal: Int)
}
