package dev.mobileide.feature.terminal

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import android.app.Application
import dev.mobileide.data.terminal.TerminalOpenResult
import dev.mobileide.data.terminal.TerminalSession
import dev.mobileide.data.terminal.TerminalSessionManager
import dev.mobileide.data.terminal.TerminalSettings
import dev.mobileide.di.container
import dev.mobileide.domain.terminal.TerminalEnvironment
import dev.mobileide.domain.terminal.TerminalKey
import dev.mobileide.domain.terminal.TerminalSessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TerminalScreenState(
    val projectId: Long = 0L,
    val projectName: String = "",
    val sessions: List<TerminalSession> = emptyList(),
    val activeId: String? = null,
    val settings: TerminalSettings = TerminalSettings(),
    val linuxAvailable: Boolean = false,
    val ptyAvailable: Boolean = true,
    val showSettings: Boolean = false,
    val message: String? = null
) {
    val activeSession: TerminalSession? get() = sessions.firstOrNull { it.id == activeId }

    val activeInfo: TerminalSessionInfo?
        get() = activeSession?.state?.value?.info
}

/**
 * Binds the app-scoped terminal sessions to the screen. The screen never owns a
 * process: navigating away keeps shells running.
 */
class TerminalViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val manager: TerminalSessionManager = application.container.terminalManager
    private val projectId: Long =
        savedStateHandle.get<String>("projectId")?.toLongOrNull() ?: 0L

    private val _uiState = MutableStateFlow(
        TerminalScreenState(
            projectId = projectId,
            settings = manager.settings.value,
            linuxAvailable = manager.isEnvironmentAvailable(TerminalEnvironment.LINUX),
            ptyAvailable = manager.isPtyAvailable()
        )
    )
    val uiState: StateFlow<TerminalScreenState> = _uiState.asStateFlow()

    private var initialized = false

    init {
        viewModelScope.launch {
            manager.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            manager.sessions.collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }
        viewModelScope.launch {
            manager.activeId.collect { activeId ->
                _uiState.update { it.copy(activeId = activeId) }
            }
        }
        viewModelScope.launch {
            val project = application.container.projectRepository.getProject(projectId)
            _uiState.update { it.copy(projectName = project?.name.orEmpty()) }
            ensureSession()
        }
    }

    /** Reuses a session for this project, otherwise opens one for the project. */
    private suspend fun ensureSession() {
        if (initialized) return
        initialized = true
        val existing = manager.sessions.value.firstOrNull { it.projectId == projectId }
        if (existing != null) {
            manager.setActive(existing.id)
            return
        }
        when (val result = manager.open(projectId)) {
            is TerminalOpenResult.Failure -> showMessage(result.message)
            is TerminalOpenResult.Success -> Unit
        }
    }

    fun openSession(environment: TerminalEnvironment) {
        viewModelScope.launch {
            when (val result = manager.open(projectId, environment)) {
                is TerminalOpenResult.Failure -> showMessage(result.message)
                is TerminalOpenResult.Success -> Unit
            }
        }
    }

    fun selectSession(sessionId: String) = manager.setActive(sessionId)

    fun closeSession(sessionId: String) = manager.close(sessionId)

    fun closeAllSessions() = manager.closeAll()

    fun send(text: String) {
        val session = _uiState.value.activeSession ?: return
        session.sendLine(text)
    }

    fun clearScreen() {
        _uiState.value.activeSession?.clearScreen()
    }

    fun sendKey(key: TerminalKey) {
        _uiState.value.activeSession?.sendKey(key)
    }

    fun sendBytes(bytes: ByteArray) {
        _uiState.value.activeSession?.send(bytes)
    }

    fun resize(cols: Int, rows: Int) {
        _uiState.value.activeSession?.resize(cols, rows)
    }

    fun setDefaultEnvironment(environment: TerminalEnvironment) {
        manager.updateSettings { it.copy(defaultEnvironment = environment) }
        _uiState.update { it.copy(settings = manager.settings.value) }
    }

    fun setFontSize(sizeSp: Int) {
        manager.updateSettings { it.copy(fontSizeSp = sizeSp) }
        _uiState.update { it.copy(settings = manager.settings.value) }
    }

    fun setMaxSessions(count: Int) {
        manager.updateSettings { it.copy(maxSessions = count) }
        _uiState.update { it.copy(settings = manager.settings.value) }
    }

    fun showSettings(show: Boolean) {
        _uiState.update { it.copy(showSettings = show) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }
}
