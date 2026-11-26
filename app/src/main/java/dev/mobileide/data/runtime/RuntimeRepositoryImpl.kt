package dev.mobileide.data.runtime

import dev.mobileide.domain.runtime.ExecutionEvent
import dev.mobileide.domain.runtime.InstalledRuntimeRecord
import dev.mobileide.domain.runtime.LanguageSpec
import dev.mobileide.domain.runtime.RuntimeActionResult
import dev.mobileide.domain.runtime.RuntimeBackend
import dev.mobileide.domain.runtime.RuntimeCatalog
import dev.mobileide.domain.runtime.RuntimeId
import dev.mobileide.domain.runtime.RuntimeInfo
import dev.mobileide.domain.runtime.RuntimeProgress
import dev.mobileide.domain.runtime.RuntimeRepository
import dev.mobileide.domain.runtime.RuntimeSnapshot
import dev.mobileide.domain.runtime.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Coordinates catalog, installer, manifest and detection. Nothing is reported as
 * installed until the files exist and, for toolchains, the version command has
 * actually succeeded inside the Linux environment.
 */
class RuntimeRepositoryImpl(
    private val paths: RuntimePaths,
    private val catalogLoader: RuntimeCatalogLoader,
    private val installer: LinuxRuntimeInstaller,
    private val manifest: RuntimeManifestStore,
    private val linux: LinuxRuntime,
    private val executor: dev.mobileide.domain.runtime.RuntimeExecutor,
    private val detector: RuntimeDetector,
    private val webViewVersion: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis
) : RuntimeRepository {

    private val _state = MutableStateFlow(RuntimeSnapshot())
    override val state: StateFlow<RuntimeSnapshot> = _state.asStateFlow()

    private var catalog: RuntimeCatalog? = null

    override suspend fun refresh() {
        // Keep the existing list visible while re-checking; only the first load
        // shows a full-screen spinner.
        _state.update { it.copy(isLoading = it.runtimes.isEmpty(), error = null) }
        try {
        catalog = runCatching { catalogLoader.load() }.getOrNull()
        if (catalog == null) {
            _state.update {
                it.copy(isLoading = false, error = "The runtime catalog could not be loaded")
            }
            return
        }

        val record = manifest.record(RUNTIME_ID)
        val installed = linux.isInstalled()
        val linuxInfo = linuxInfo(record, installed)
        val languages = catalog?.languages.orEmpty().map { spec ->
            languageInfo(spec, installed)
        }
        val html = RuntimeInfo(
            id = RuntimeId.HTML,
            displayName = "HTML / CSS",
            runtimeName = "Android WebView",
            backend = RuntimeBackend.WEBVIEW,
            state = RuntimeState.INSTALLED,
            version = webViewVersion(),
            message = "Built into the app; no installation needed"
        )

        _state.update {
            it.copy(
                isLoading = false,
                runtimes = listOf(linuxInfo) + languages + html
            )
        }

        if (installed) detectLanguages()
        } finally {
            if (_state.value.isLoading) _state.update { it.copy(isLoading = false) }
        }
    }

    override suspend fun installLinux(): RuntimeActionResult {
        if (_state.value.isBusy) return RuntimeActionResult.Failure("Another task is already running")
        val spec = catalog?.linux
            ?: return RuntimeActionResult.Failure("The catalog has no Linux environment")

        _state.update { it.copy(isBusy = true, progress = null, error = null, message = null) }
        return try {
            installer.install(spec) { progress -> publishProgress(progress) }
            _state.update { it.copy(message = "Linux environment installed") }
            refresh()
            RuntimeActionResult.Success
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val message = error.message ?: "Installation failed"
            _state.update { it.copy(error = message) }
            RuntimeActionResult.Failure(message)
        } finally {
            _state.update { it.copy(isBusy = false, progress = null) }
        }
    }

    override suspend fun uninstallLinux(): RuntimeActionResult {
        if (_state.value.isBusy) return RuntimeActionResult.Failure("Another task is already running")
        return try {
            installer.uninstall()
            _state.update { it.copy(message = "Linux environment removed") }
            refresh()
            RuntimeActionResult.Success
        } catch (error: Exception) {
            val message = error.message ?: "Uninstall failed"
            _state.update { it.copy(error = message) }
            RuntimeActionResult.Failure(message)
        }
    }

    override suspend fun verifyLinux(): RuntimeActionResult {
        val request = linux.buildExecution(
            guestCommand = listOf("/bin/sh", "-lc", "echo mobileide-ok"),
            timeoutMs = 25_000L
        ) ?: return RuntimeActionResult.Failure("Linux environment is not installed")

        val output = StringBuilder()
        var exitCode = -1
        executor.execute(request).collect { event ->
            when (event) {
                is ExecutionEvent.Output -> if (output.length < 2_048) output.appendLine(event.line)
                is ExecutionEvent.Exit -> exitCode = event.code
            }
        }
        return if (exitCode == 0 && output.contains("mobileide-ok")) {
            _state.update { it.copy(message = "Linux environment starts correctly") }
            RuntimeActionResult.Success
        } else {
            val message = "Linux environment failed to start: ${output.toString().trim().take(200)}"
            _state.update { it.copy(error = message) }
            RuntimeActionResult.Failure(message)
        }
    }

    override suspend fun installToolchain(id: RuntimeId): RuntimeActionResult {
        val spec = catalog?.languages?.firstOrNull { it.id == id }
            ?: return RuntimeActionResult.Failure("Language not found in the catalog")
        spec.unsupportedReason?.let { return RuntimeActionResult.Failure(it) }
        if (!linux.isInstalled()) return RuntimeActionResult.Failure("Install the Linux environment first")
        if (spec.aptPackages.isEmpty()) return RuntimeActionResult.Failure("No packages available for this language")
        if (_state.value.isBusy) return RuntimeActionResult.Failure("Another task is already running")

        val packages = spec.aptPackages.joinToString(" ")
        val script = "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get -o Acquire::Check-Valid-Until=false update && " +
            "apt-get install -y --no-install-recommends $packages"
        val request = linux.buildExecution(
            guestCommand = listOf("/bin/sh", "-lc", script),
            fakeRoot = true,
            timeoutMs = 30 * 60 * 1000L
        ) ?: return RuntimeActionResult.Failure("Linux environment is not installed")

        _state.update {
            it.copy(isBusy = true, logs = emptyList(), error = null, message = null)
        }
        return try {
            var exitCode = -1
            val pending = mutableListOf<String>()
            executor.execute(request).collect { event ->
                when (event) {
                    is ExecutionEvent.Output -> {
                        pending += event.line
                        if (pending.size >= 8) {
                            publishLogs(pending)
                            pending.clear()
                        }
                    }

                    is ExecutionEvent.Exit -> exitCode = event.code
                }
            }
            if (pending.isNotEmpty()) publishLogs(pending)

            if (exitCode == 0) {
                _state.update { it.copy(message = "${spec.displayName} toolchain installed") }
                refresh()
                RuntimeActionResult.Success
            } else {
                val message = "Installation failed (exit code $exitCode). Check the log."
                _state.update { it.copy(error = message) }
                RuntimeActionResult.Failure(message)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val message = error.message ?: "Installation failed"
            _state.update { it.copy(error = message) }
            RuntimeActionResult.Failure(message)
        } finally {
            _state.update { it.copy(isBusy = false) }
        }
    }

    override suspend fun checkToolchain(id: RuntimeId): RuntimeActionResult {
        val spec = catalog?.languages?.firstOrNull { it.id == id }
            ?: return RuntimeActionResult.Failure("Language not found in the catalog")
        if (!linux.isInstalled()) return RuntimeActionResult.Failure("Install the Linux environment first")
        updateRuntime(id) { it.copy(state = RuntimeState.CHECKING, message = "Checking…") }
        val result = detector.detect(spec)
        updateRuntime(id) {
            it.copy(
                state = if (result.installed) RuntimeState.INSTALLED else RuntimeState.NOT_INSTALLED,
                version = result.version,
                message = if (result.installed) null else result.message
            )
        }
        return if (result.installed) {
            RuntimeActionResult.Success
        } else {
            RuntimeActionResult.Failure(result.message ?: "未安装")
        }
    }

    override fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun clearError() {
        _state.update { it.copy(error = null) }
    }

    override fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    private suspend fun detectLanguages() {
        val specs = catalog?.languages.orEmpty()
        for (spec in specs) {
            if (spec.unsupportedReason != null) continue
            if (!linux.isInstalled()) return
            updateRuntime(spec.id) {
                it.copy(state = RuntimeState.CHECKING, message = "Checking…")
            }
            val result = detector.detect(spec)
            updateRuntime(spec.id) {
                it.copy(
                    state = if (result.installed) {
                        RuntimeState.INSTALLED
                    } else {
                        RuntimeState.NOT_INSTALLED
                    },
                    version = result.version,
                    message = if (result.installed) null else result.message
                )
            }
        }
    }

    private fun linuxInfo(record: InstalledRuntimeRecord?, installed: Boolean) = RuntimeInfo(
        id = RuntimeId.LINUX,
        displayName = "Linux 环境",
        runtimeName = catalog?.linux?.name ?: "Debian",
        backend = RuntimeBackend.PROOT,
        state = if (installed) RuntimeState.INSTALLED else RuntimeState.NOT_INSTALLED,
        version = if (installed) record?.version ?: catalog?.linux?.version else null,
        installPath = paths.linuxDir.absolutePath.takeIf { installed },
        sizeBytes = installer.sizeBytes().takeIf { installed },
        message = if (installed) null else "Install the Debian userland to use C, Java, Go and other toolchains",
        canInstall = catalog?.linux != null
    )

    private fun languageInfo(spec: LanguageSpec, linuxInstalled: Boolean) = RuntimeInfo(
        id = spec.id,
        displayName = spec.displayName,
        runtimeName = "Debian · ${spec.toolName}",
        backend = RuntimeBackend.PROOT,
        state = when {
            spec.unsupportedReason != null -> RuntimeState.UNSUPPORTED
            !linuxInstalled -> RuntimeState.NOT_INSTALLED
            else -> RuntimeState.CHECKING
        },
        message = when {
            spec.unsupportedReason != null -> spec.unsupportedReason
            !linuxInstalled -> "Install the Linux environment first"
            else -> "Checking…"
        },
        canInstall = spec.unsupportedReason == null && spec.aptPackages.isNotEmpty(),
        requiresLinux = true,
        packages = spec.aptPackages
    )

    private fun publishProgress(progress: RuntimeProgress) {
        _state.update { it.copy(progress = progress) }
    }

    private fun publishLogs(lines: List<String>) {
        _state.update { it.copy(logs = (it.logs + lines).takeLast(MAX_LOG_LINES)) }
    }

    private fun updateRuntime(id: RuntimeId, transform: (RuntimeInfo) -> RuntimeInfo) {
        _state.update { snapshot ->
            snapshot.copy(
                runtimes = snapshot.runtimes.map { info ->
                    if (info.id == id) transform(info) else info
                }
            )
        }
    }

    private companion object {
        const val RUNTIME_ID = "LINUX"
        const val MAX_LOG_LINES = 300
    }
}
