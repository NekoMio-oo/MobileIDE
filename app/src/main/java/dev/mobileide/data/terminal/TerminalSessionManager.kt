package dev.mobileide.data.terminal

import dev.mobileide.core.process.PtyNative
import dev.mobileide.data.runtime.LinuxRuntime
import dev.mobileide.domain.repository.ProjectRepository
import dev.mobileide.domain.runtime.ExecutionRequest
import dev.mobileide.domain.terminal.TerminalEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

sealed interface TerminalOpenResult {
    data class Success(val sessionId: String) : TerminalOpenResult
    data class Failure(val message: String) : TerminalOpenResult
}

/**
 * App-scoped terminal sessions. Sessions live outside the screen so navigating
 * to the editor does not kill a running shell; closing the app ends them.
 *
 * Every session is a separate PTY and child process; the manager only enforces
 * the session limit and builds the environment-specific launch request from the
 * installed runtime (never a hardcoded path).
 */
class TerminalSessionManager(
    private val linux: LinuxRuntime,
    private val projectRepository: ProjectRepository,
    private val preferences: TerminalPreferences,
    private val homeDir: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val _settings = MutableStateFlow(preferences.load())
    val settings: StateFlow<TerminalSettings> = _settings.asStateFlow()

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val counter = AtomicInteger(1)

    fun isPtyAvailable(): Boolean = PtyNative.isAvailable()

    fun isEnvironmentAvailable(environment: TerminalEnvironment): Boolean = when (environment) {
        TerminalEnvironment.ANDROID -> true
        TerminalEnvironment.LINUX -> linux.isInstalled()
    }

    suspend fun open(
        projectId: Long,
        environment: TerminalEnvironment? = null,
        initialCols: Int = 80,
        initialRows: Int = 24
    ): TerminalOpenResult {
        if (!PtyNative.isAvailable()) {
            return TerminalOpenResult.Failure(
                "This build has no native PTY support, so an interactive terminal cannot start."
            )
        }
        val project = runCatching { projectRepository.getProject(projectId) }.getOrNull()
        val projectPath = project?.path
        if (project == null || projectPath.isNullOrBlank()) {
            return TerminalOpenResult.Failure("The project folder is not available")
        }
        if (_sessions.value.size >= _settings.value.maxSessions) {
            return TerminalOpenResult.Failure(
                "Close a terminal first (limit ${_settings.value.maxSessions})."
            )
        }

        val selected = environment ?: _settings.value.defaultEnvironment
        if (selected == TerminalEnvironment.LINUX && !linux.isInstalled()) {
            return TerminalOpenResult.Failure(
                "The Debian environment is not installed. Install it in Runtimes first."
            )
        }

        val request = when (selected) {
            TerminalEnvironment.ANDROID -> androidRequest(projectPath)
            TerminalEnvironment.LINUX -> linuxRequest(projectPath)
        } ?: return TerminalOpenResult.Failure("Could not build the terminal command")

        val displayDirectory = when (selected) {
            TerminalEnvironment.ANDROID -> projectPath
            TerminalEnvironment.LINUX -> "/workspace"
        }
        val session = TerminalSession(
            id = "term-${clock()}-${counter.getAndIncrement()}",
            projectId = projectId,
            environment = selected,
            projectName = project.name,
            displayWorkingDirectory = displayDirectory,
            scope = scope,
            initialCols = initialCols,
            initialRows = initialRows,
            scrollbackLines = _settings.value.scrollbackLines
        )
        session.start(request)
        _sessions.update { it + session }
        _activeId.value = session.id

        if (environment != null && environment != _settings.value.defaultEnvironment) {
            updateSettings { it.copy(defaultEnvironment = environment) }
        }
        return TerminalOpenResult.Success(session.id)
    }

    fun setActive(sessionId: String) {
        if (_sessions.value.any { it.id == sessionId }) {
            _activeId.value = sessionId
        }
    }

    fun close(sessionId: String) {
        val session = _sessions.value.firstOrNull { it.id == sessionId } ?: return
        session.close()
        _sessions.update { list -> list.filterNot { it.id == sessionId } }
        if (_activeId.value == sessionId) {
            _activeId.value = _sessions.value.lastOrNull()?.id
        }
    }

    fun closeAll() {
        _sessions.value.forEach { it.close() }
        _sessions.value = emptyList()
        _activeId.value = null
    }

    fun updateSettings(transform: (TerminalSettings) -> TerminalSettings): TerminalSettings {
        val saved = preferences.save(transform(_settings.value))
        _settings.value = saved
        return saved
    }

    private fun androidRequest(projectPath: String): ExecutionRequest {
        homeDir.mkdirs()
        val temporary = File(homeDir, "tmp").apply { mkdirs() }
        return ExecutionRequest(
            command = listOf("/system/bin/sh", "-i"),
            workingDirectory = File(projectPath),
            environment = mapOf(
                "HOME" to homeDir.absolutePath,
                "PWD" to projectPath,
                "PATH" to "/system/bin:/system/xbin:/vendor/bin",
                "TERM" to "xterm-256color",
                "LANG" to "C.UTF-8",
                "SHELL" to "/system/bin/sh",
                "TMPDIR" to temporary.absolutePath,
                "ANDROID_ROOT" to "/system",
                "ANDROID_DATA" to "/data",
                "EXTERNAL_STORAGE" to "/storage/emulated/0"
            ),
            timeoutMs = 0L
        )
    }

    private fun linuxRequest(projectPath: String): ExecutionRequest? {
        // The bind target must exist inside the guest rootfs.
        linux.ensureGuestDir("workspace")
        linux.ensureAptConfiguration()
        // Use bash when the installed userland has it, otherwise the POSIX shell.
        val shell = if (File(linux.rootfsPath(), "bin/bash").isFile) "/bin/bash" else "/bin/sh"
        return linux.buildExecution(
            guestCommand = listOf(shell, "-i"),
            guestWorkDir = "/workspace",
            // PRoot's -0 is a fake root inside the container, not real root.
            fakeRoot = true,
            bindPaths = listOf("$projectPath:/workspace"),
            timeoutMs = 0L
        )
    }
}
