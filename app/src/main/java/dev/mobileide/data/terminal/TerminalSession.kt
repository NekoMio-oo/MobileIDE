package dev.mobileide.data.terminal

import dev.mobileide.domain.runtime.ExecutionRequest
import dev.mobileide.domain.terminal.TerminalEmulator
import dev.mobileide.domain.terminal.TerminalEnvironment
import dev.mobileide.domain.terminal.TerminalKey
import dev.mobileide.domain.terminal.TerminalSessionInfo
import dev.mobileide.domain.terminal.TerminalUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * One interactive shell. Owns the PTY, the emulator and the reader loop; the
 * screen only ever sees immutable snapshots.
 */
class TerminalSession(
    val id: String,
    val projectId: Long,
    val environment: TerminalEnvironment,
    private val projectName: String,
    private val displayWorkingDirectory: String,
    private val scope: CoroutineScope,
    initialCols: Int,
    initialRows: Int,
    scrollbackLines: Int = 600
) {

    private var cols = initialCols.coerceIn(MIN_COLS, MAX_COLS)
    private var rows = initialRows.coerceIn(MIN_ROWS, MAX_ROWS)

    private val emulator = TerminalEmulator(cols, rows, scrollbackLines)

    private val _state = MutableStateFlow(
        TerminalUiState(
            info = TerminalSessionInfo(
                id = id,
                environment = environment,
                projectId = projectId,
                projectName = projectName,
                workingDirectory = displayWorkingDirectory,
                isRunning = false
            ),
            snapshot = emulator.snapshot()
        )
    )
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var process: TerminalProcess? = null
    private var readerJob: Job? = null
    private var tickerJob: Job? = null

    @Volatile
    private var dirty = false

    @Volatile
    private var started = false

    @Volatile
    private var closing = false

    fun start(request: ExecutionRequest) {
        if (started) return
        started = true

        val result = TerminalProcess.start(request, rows, cols)
        val startedProcess = result.getOrElse { error ->
            _state.update {
                it.copy(
                    info = it.info.copy(
                        isRunning = false,
                        error = error.message ?: "Could not start the terminal"
                    )
                )
            }
            return
        }

        process = startedProcess
        emulator.onResponse = { bytes -> startedProcess.write(bytes) }
        startedProcess.resize(rows, cols)
        _state.update { it.copy(info = it.info.copy(isRunning = true, error = null)) }

        readerJob = scope.launch(Dispatchers.IO) { readLoop(startedProcess) }
        tickerJob = scope.launch {
            while (isActive) {
                delay(SNAPSHOT_INTERVAL_MS)
                if (dirty) {
                    dirty = false
                    publish()
                }
            }
        }
    }

    private suspend fun readLoop(process: TerminalProcess) {
        val buffer = ByteArray(8 * 1024)
        while (kotlin.coroutines.coroutineContext.isActive) {
            val count = try {
                process.read(buffer)
            } catch (_: IOException) {
                -1
            }
            if (count < 0) break
            if (count == 0) continue
            emulator.feed(buffer.copyOf(count))
            dirty = true
        }
        dirty = false
        publish()
        val exitCode = process.awaitExit()
        // Release the master fd as soon as the shell is gone.
        process.close()
        tickerJob?.cancel()
        _state.update {
            it.copy(info = it.info.copy(isRunning = false, exitCode = exitCode))
        }
    }

    fun send(bytes: ByteArray) {
        process?.write(bytes)
    }

    fun sendKey(key: TerminalKey) {
        send(key.bytes())
    }

    fun sendLine(text: String) {
        if (text.isEmpty()) return
        send((text + "\n").toByteArray(Charsets.UTF_8))
    }

    /** Clears the local screen without waiting for the shell to support `clear`. */
    fun clearScreen() {
        emulator.feed("\u001b[2J\u001b[H".toByteArray(Charsets.US_ASCII))
        publish()
    }

    fun resize(newCols: Int, newRows: Int) {
        val targetCols = newCols.coerceIn(MIN_COLS, MAX_COLS)
        val targetRows = newRows.coerceIn(MIN_ROWS, MAX_ROWS)
        if (targetCols == cols && targetRows == rows) return
        cols = targetCols
        rows = targetRows
        emulator.resize(cols, rows)
        process?.resize(rows, cols)
        publish()
    }

    /** Graceful shutdown: SIGTERM, then SIGKILL, then release the descriptors. */
    fun close() {
        if (closing) return
        closing = true
        val target = process ?: return
        target.signal(TerminalProcess.SIGTERM)
        scope.launch {
            delay(TERM_GRACE_MS)
            target.signal(TerminalProcess.SIGKILL)
            delay(KILL_GRACE_MS)
            // Unblocks a reader still waiting on a grandchild that kept the PTY open.
            target.close()
        }
    }

    private fun publish() {
        _state.update { it.copy(snapshot = emulator.snapshot()) }
    }

    companion object {
        const val MIN_COLS = 20
        const val MAX_COLS = 200
        const val MIN_ROWS = 4
        const val MAX_ROWS = 120

        private const val SNAPSHOT_INTERVAL_MS = 50L
        private const val TERM_GRACE_MS = 1_200L
        private const val KILL_GRACE_MS = 800L
    }
}
