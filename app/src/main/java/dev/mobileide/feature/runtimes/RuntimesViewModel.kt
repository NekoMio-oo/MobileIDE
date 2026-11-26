package dev.mobileide.feature.runtimes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mobileide.di.container
import dev.mobileide.domain.runtime.RuntimeId
import dev.mobileide.domain.runtime.RuntimeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RuntimesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: RuntimeRepository = application.container.runtimeRepository
    val state = repository.state

    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (state.value.isBusy) return
        job?.cancel()
        job = viewModelScope.launch { repository.refresh() }
    }

    fun installLinux() = run { repository.installLinux() }

    fun uninstallLinux() = run { repository.uninstallLinux() }

    fun verifyLinux() = run { repository.verifyLinux() }

    fun installToolchain(id: RuntimeId) = run { repository.installToolchain(id) }

    fun checkToolchain(id: RuntimeId) = run { repository.checkToolchain(id) }

    /** Cancels downloads, extraction or apt; the repository cleans its staging. */
    fun cancelWork() {
        job?.cancel()
    }

    fun clearMessage() = repository.clearMessage()

    fun clearError() = repository.clearError()

    fun clearLogs() = repository.clearLogs()

    private fun run(block: suspend () -> Unit) {
        if (state.value.isBusy) return
        job?.cancel()
        job = viewModelScope.launch { block() }
    }
}
