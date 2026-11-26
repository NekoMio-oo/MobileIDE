package dev.mobileide.data.lsp

import android.util.Log
import dev.mobileide.data.runtime.LinuxRuntime
import dev.mobileide.domain.editor.EditorLanguage
import dev.mobileide.domain.lsp.LspCapabilities
import dev.mobileide.domain.lsp.LspCompletionItem
import dev.mobileide.domain.lsp.LspDiagnostic
import dev.mobileide.domain.lsp.LspDocumentKey
import dev.mobileide.domain.lsp.LspDocumentSymbol
import dev.mobileide.domain.lsp.LspHover
import dev.mobileide.domain.lsp.LspInstallProgress
import dev.mobileide.domain.lsp.LspInstallStage
import dev.mobileide.domain.lsp.LspLocation
import dev.mobileide.domain.lsp.LspLogEntry
import dev.mobileide.domain.lsp.LspLogLevel
import dev.mobileide.domain.lsp.LspRepository
import dev.mobileide.domain.lsp.LspServerInfo
import dev.mobileide.domain.lsp.LspServerSpec
import dev.mobileide.domain.lsp.LspServerState
import dev.mobileide.domain.lsp.LspSettings
import dev.mobileide.domain.lsp.LspSnapshot
import dev.mobileide.domain.lsp.LspTextEdit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Wires the catalog, the installer, the process runtime and the session manager
 * into the single surface the editor and settings screens consume.
 */
class LspRepositoryImpl(
    private val paths: LspPaths,
    private val linux: LinuxRuntime,
    private val catalogLoader: LspCatalogLoader,
    private val installer: LspServerInstaller,
    private val manifest: LspManifestStore,
    private val settingsStore: LspSettingsStore,
    private val runtime: LspServerRuntime,
    scope: CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis
) : LspRepository {

    private val workScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(LspSnapshot())
    override val state: StateFlow<LspSnapshot> = _state.asStateFlow()

    private val _diagnostics = MutableStateFlow<Map<LspDocumentKey, List<LspDiagnostic>>>(emptyMap())
    override val diagnostics: StateFlow<Map<LspDocumentKey, List<LspDiagnostic>>> =
        _diagnostics.asStateFlow()

    private val logs = mutableMapOf<String, ArrayDeque<LspLogEntry>>()

    @Volatile private var catalog: List<LspServerSpec> = emptyList()
    @Volatile private var stored = LspStoredSettings()
    @Volatile private var detections: Map<String, LspServerInstaller.Detection> = emptyMap()
    @Volatile private var detectedAt = 0L
    @Volatile private var installState: LspInstallProgress? = null
    @Volatile private var installJob: Job? = null

    private val manager = LspSessionManager(
        runtime = runtime,
        scope = workScope,
        tracker = LspDocumentTracker(),
        onSessionsChanged = { publish() },
        onDiagnostics = ::onDiagnostics,
        onLog = ::appendLog
    )

    // ---- management -------------------------------------------------------

    override suspend fun refresh(force: Boolean) = withContext(Dispatchers.IO) {
        refreshInternal(force)
    }

    private suspend fun refreshInternal(force: Boolean) {
        val firstLoad = catalog.isEmpty()
        _state.update { it.copy(isLoading = firstLoad && it.servers.isEmpty()) }
        val loadedCatalog = runCatching { catalogLoader.load() }
            .getOrElse { error ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = "The language server catalog could not be loaded: ${error.message}"
                    )
                }
                return
            }
        catalog = loadedCatalog
        stored = runCatching { settingsStore.load() }.getOrDefault(LspStoredSettings())

        val linuxInstalled = linux.isInstalled()
        val cacheFresh = !force && detections.isNotEmpty() &&
            clock() - detectedAt < DETECTION_TTL_MS
        if (!cacheFresh) {
            detections = if (!linuxInstalled) {
                loadedCatalog.associate { spec ->
                    spec.id to LspServerInstaller.Detection(
                        false,
                        detail = "Install the Linux environment first"
                    )
                }
            } else {
                buildMap {
                    loadedCatalog.forEach { spec ->
                        put(
                            spec.id,
                            if (spec.supported) {
                                runCatching { installer.detect(spec) }
                                    .getOrElse { error ->
                                        LspServerInstaller.Detection(
                                            false,
                                            detail = error.message?.take(160)
                                        )
                                    }
                            } else {
                                LspServerInstaller.Detection(
                                    false,
                                    detail = spec.unsupportedReason
                                )
                            }
                        )
                    }
                }
            }
            detectedAt = clock()
        }
        _state.update { it.copy(isLoading = false) }
        publish()
    }

    override suspend fun install(serverId: String) {
        if (installJob?.isActive == true) {
            _state.update { it.copy(error = "Another installation is already running") }
            return
        }
        val spec = catalog.firstOrNull { it.id == serverId } ?: return
        if (!spec.supported) {
            _state.update { it.copy(error = spec.unsupportedReason) }
            return
        }
        installJob = workScope.launch {
            try {
                installState = LspInstallProgress(serverId, LspInstallStage.INSTALLING, "Preparing")
                publish()
                val result = installer.install(spec) { stage, message, done, total ->
                    installState = LspInstallProgress(serverId, stage, message, done, total)
                    publish()
                }
                installState = null
                detections = detections + (serverId to installer.detect(spec))
                detectedAt = clock()
                _state.update {
                    it.copy(message = "${spec.displayName} ${result.version} installed", error = null)
                }
                refresh(force = true)
            } catch (cancelled: CancellationException) {
                installState = null
                _state.update { it.copy(message = "Installation cancelled") }
                publish()
                throw cancelled
            } catch (error: Throwable) {
                installState = null
                _state.update {
                    it.copy(error = error.message ?: "Installation failed")
                }
                publish()
            } finally {
                installJob = null
            }
        }
    }

    override fun cancelInstall() {
        installJob?.cancel()
    }

    override suspend fun uninstall(serverId: String) = withContext(Dispatchers.IO) {
        val spec = catalog.firstOrNull { it.id == serverId } ?: return@withContext
        manager.stopServer(serverId)
        val removed = runCatching { installer.uninstall(spec) }.getOrElse { error ->
            _state.update { it.copy(error = error.message ?: "Could not remove the server") }
            return@withContext
        }
        detections = detections + (serverId to LspServerInstaller.Detection(false))
        detectedAt = clock()
        _state.update {
            it.copy(
                message = if (removed) {
                    "${spec.displayName} removed"
                } else {
                    "${spec.displayName} packages stay in the Debian userland"
                }
            )
        }
        refresh(force = true)
    }

    override fun setEnabled(serverId: String, enabled: Boolean) {
        updateStored { it.copy(enabled = it.enabled + (serverId to enabled)) }
    }

    override fun setGlobalEnabled(enabled: Boolean) {
        updateStored { it.copy(settings = it.settings.copy(enabled = enabled)) }
        if (!enabled) manager.stopAll()
    }

    override fun setAutoStart(enabled: Boolean) {
        updateStored { it.copy(settings = it.settings.copy(autoStart = enabled)) }
    }

    override fun setMaxSessions(count: Int) {
        updateStored {
            it.copy(settings = it.settings.copy(maxSessions = count.coerceIn(1, 3)))
        }
    }

    override fun setExtraArgs(serverId: String, args: List<String>) {
        val cleaned = args.map { it.trim() }.filter { it.isNotEmpty() }
        updateStored { it.copy(extraArgs = it.extraArgs + (serverId to cleaned)) }
    }

    override suspend fun restart(serverId: String) {
        manager.restartServer(serverId)
        publish()
    }

    override fun stopAll() {
        manager.stopAll()
        publish()
    }

    override fun logs(serverId: String): List<LspLogEntry> = synchronized(logs) {
        logs[serverId]?.toList().orEmpty()
    }

    override fun clearLogs(serverId: String) {
        synchronized(logs) { logs.remove(serverId) }
    }

    override fun dismissError() {
        _state.update { it.copy(error = null, message = null) }
    }

    // ---- documents --------------------------------------------------------

    override fun openDocument(
        projectId: Long,
        projectPath: String,
        relativePath: String,
        language: EditorLanguage,
        text: String
    ) {
        val spec = specFor(language) ?: return
        manager.openDocument(projectId, projectPath, relativePath, language, spec, text)
    }

    override fun changeDocument(projectId: Long, relativePath: String, text: String) {
        manager.changeDocument(projectId, relativePath, text)
    }

    override fun saveDocument(projectId: Long, relativePath: String) {
        manager.saveDocument(projectId, relativePath)
    }

    override fun closeDocument(projectId: Long, relativePath: String) {
        manager.closeDocument(projectId, relativePath)
    }

    override fun closeProject(projectId: Long) {
        manager.closeProject(projectId)
        _diagnostics.update { current -> current.filterKeys { it.projectId != projectId } }
    }

    override fun stopProject(projectId: Long) {
        manager.stopProject(projectId)
        _diagnostics.update { current -> current.filterKeys { it.projectId != projectId } }
        publish()
    }

    // ---- queries ----------------------------------------------------------

    override suspend fun completion(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): List<LspCompletionItem> = manager.completion(projectId, relativePath, line, column)

    override suspend fun hover(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): LspHover? = manager.hover(projectId, relativePath, line, column)

    override suspend fun definition(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): List<LspLocation> = manager.definition(projectId, relativePath, line, column)

    override suspend fun references(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): List<LspLocation> = manager.references(projectId, relativePath, line, column)

    override suspend fun documentSymbols(
        projectId: Long,
        relativePath: String
    ): List<LspDocumentSymbol> = manager.documentSymbols(projectId, relativePath)

    override suspend fun format(projectId: Long, relativePath: String): List<LspTextEdit> =
        manager.format(projectId, relativePath)

    override fun capabilities(projectId: Long, relativePath: String): LspCapabilities {
        val live = manager.capabilities(projectId, relativePath)
        if (live.any) return live
        val serverId = manager.serverIdFor(projectId, relativePath) ?: return LspCapabilities()
        return catalog.firstOrNull { it.id == serverId }?.declaredCapabilities ?: LspCapabilities()
    }

    override fun serverFor(language: EditorLanguage): LspServerInfo? =
        _state.value.servers.firstOrNull { language in it.languages }

    // ---- internals --------------------------------------------------------

    private fun updateStored(transform: (LspStoredSettings) -> LspStoredSettings) {
        val updated = transform(stored)
        stored = updated
        runCatching { settingsStore.save(updated) }
            .onFailure { Log.w(TAG, "Could not save LSP settings", it) }
        publish()
    }

    private fun specFor(language: EditorLanguage): LspServerSpec? =
        catalog.firstOrNull { it.supported && language in it.languages }

    private fun onDiagnostics(key: LspDocumentKey, list: List<LspDiagnostic>, version: Int?) {
        _diagnostics.update { current ->
            if (list.isEmpty()) current - key else current + (key to list)
        }
    }

    private fun appendLog(serverId: String, level: LspLogLevel, message: String) {
        val entry = LspLogEntry(clock(), level, message)
        synchronized(logs) {
            val deque = logs.getOrPut(serverId) { ArrayDeque() }
            deque.addLast(entry)
            while (deque.size > MAX_LOG_LINES) deque.removeFirst()
        }
        Log.d(TAG, "[$serverId] $message")
    }

    private fun publish() {
        val linuxInstalled = linux.isInstalled()
        val sessions = manager.sessionInfos()
        val servers = catalog.map { spec ->
            val detection = detections[spec.id]
            val mine = sessions.filter { it.serverId == spec.id }
            val running = mine.firstOrNull {
                it.state == LspServerState.READY || it.state == LspServerState.STARTING
            }
            val crashed = mine.firstOrNull { it.state == LspServerState.CRASHED }
            val state = when {
                !spec.supported -> LspServerState.UNSUPPORTED
                installState?.serverId == spec.id -> LspServerState.INSTALLING
                !linuxInstalled || detection?.installed != true -> LspServerState.NOT_INSTALLED
                running != null -> running.state
                crashed != null -> LspServerState.CRASHED
                else -> LspServerState.STOPPED
            }
            LspServerInfo(
                id = spec.id,
                displayName = spec.displayName,
                languages = spec.languages,
                state = state,
                installed = detection?.installed == true,
                enabled = stored.isEnabled(spec.id),
                version = detection?.version,
                stateDetail = detection?.detail,
                sizeMb = spec.sizeMb,
                installKind = spec.installKind,
                docsUrl = spec.docsUrl,
                capabilities = running?.capabilities ?: spec.declaredCapabilities,
                sessionCount = mine.count { it.state == LspServerState.READY ||
                    it.state == LspServerState.STARTING },
                busy = mine.any { it.state == LspServerState.STARTING },
                lastError = mine.firstNotNullOfOrNull { it.lastError },
                extraArgs = stored.argsFor(spec.id)
            )
        }
        _state.update {
            it.copy(
                isLoading = false,
                linuxInstalled = linuxInstalled,
                settings = stored.settings,
                servers = servers,
                install = installState
            )
        }
        manager.configure(
            LspManagerConfig(
                settings = stored.settings,
                enabled = catalog.associate { it.id to stored.isEnabled(it.id) },
                installed = detections.filterValues { detection -> detection.installed }.keys,
                specs = catalog.associateBy { it.id },
                extraArgs = stored.extraArgs
            )
        )
    }

    private companion object {
        const val TAG = "LspRepository"
        const val MAX_LOG_LINES = 500
        const val DETECTION_TTL_MS = 30_000L
    }
}
