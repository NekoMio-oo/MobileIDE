package dev.mobileide.feature.lsp

import android.app.Application
import dev.mobileide.R
import dev.mobileide.di.container
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileide.domain.lsp.LspLogEntry
import dev.mobileide.domain.lsp.LspRepository
import dev.mobileide.domain.lsp.LspSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LspSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: LspRepository = application.container.lspRepository

    val state: StateFlow<LspSnapshot> = repository.state

    private val _logs = MutableStateFlow<List<LspLogEntry>>(emptyList())
    val logs: StateFlow<List<LspLogEntry>> = _logs.asStateFlow()

    private val _logServerId = MutableStateFlow<String?>(null)
    val logServerId: StateFlow<String?> = _logServerId.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { repository.refresh() }
    }

    fun refresh() {
        viewModelScope.launch { repository.refresh(force = true) }
    }

    fun install(serverId: String) {
        viewModelScope.launch { repository.install(serverId) }
    }

    fun cancelInstall() {
        repository.cancelInstall()
    }

    fun uninstall(serverId: String) {
        viewModelScope.launch { repository.uninstall(serverId) }
    }

    fun setEnabled(serverId: String, enabled: Boolean) {
        repository.setEnabled(serverId, enabled)
    }

    fun setGlobalEnabled(enabled: Boolean) {
        repository.setGlobalEnabled(enabled)
    }

    fun setAutoStart(enabled: Boolean) {
        repository.setAutoStart(enabled)
    }

    fun setMaxSessions(count: Int) {
        repository.setMaxSessions(count)
    }

    fun restart(serverId: String) {
        viewModelScope.launch { repository.restart(serverId) }
    }

    fun stopAll() {
        repository.stopAll()
    }

    fun setExtraArgs(serverId: String, text: String) {
        repository.setExtraArgs(
            serverId,
            text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        )
        _message.value = getApplication<Application>().getString(R.string.lsp_args_saved)
    }

    fun openLogs(serverId: String) {
        _logServerId.value = serverId
        refreshLogs()
    }

    fun refreshLogs() {
        _logServerId.value?.let { _logs.value = repository.logs(it) }
    }

    fun closeLogs() {
        _logServerId.value = null
        _logs.value = emptyList()
    }

    fun clearLogs(serverId: String) {
        repository.clearLogs(serverId)
        refreshLogs()
    }

    fun dismissMessage() {
        _message.value = null
        repository.dismissError()
    }
}
