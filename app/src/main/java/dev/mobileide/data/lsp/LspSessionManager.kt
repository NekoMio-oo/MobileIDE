package dev.mobileide.data.lsp

import dev.mobileide.domain.lsp.LspCapabilities
import dev.mobileide.domain.lsp.LspCompletionItem
import dev.mobileide.domain.lsp.LspDiagnostic
import dev.mobileide.domain.lsp.LspDocumentKey
import dev.mobileide.domain.lsp.LspDocumentSymbol
import dev.mobileide.domain.lsp.LspHover
import dev.mobileide.domain.lsp.LspLocation
import dev.mobileide.domain.lsp.LspLogLevel
import dev.mobileide.domain.lsp.LspRange
import dev.mobileide.domain.lsp.LspServerSpec
import dev.mobileide.domain.lsp.LspServerState
import dev.mobileide.domain.lsp.LspSessionInfo
import dev.mobileide.domain.lsp.LspSettings
import dev.mobileide.domain.lsp.LspTextEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Snapshot of everything the manager needs to decide whether a server may run. */
data class LspManagerConfig(
    val settings: LspSettings = LspSettings(),
    val enabled: Map<String, Boolean> = emptyMap(),
    val installed: Set<String> = emptySet(),
    val specs: Map<String, LspServerSpec> = emptyMap(),
    val extraArgs: Map<String, List<String>> = emptyMap()
)

/**
 * Owns every running language server.
 *
 * Sessions are keyed by (project, server) so two projects never share an
 * instance. At most `maxSessions` run at once; the least recently used one is
 * stopped first, and a server with no open documents shuts down after an idle
 * period. A crash is contained here: the editor only sees empty results.
 */
class LspSessionManager(
    private val runtime: LspServerRuntime,
    private val scope: CoroutineScope,
    private val tracker: LspDocumentTracker,
    private val onSessionsChanged: () -> Unit,
    private val onDiagnostics: (LspDocumentKey, List<LspDiagnostic>, Int?) -> Unit,
    private val onLog: (serverId: String, level: LspLogLevel, message: String) -> Unit,
    private val idleTimeoutMs: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private data class Key(val projectId: Long, val serverId: String)

    private val lock = Any()
    private val mutex = Mutex()
    private val sessions = mutableMapOf<Key, LspSession>()
    private val idleJobs = mutableMapOf<Key, Job>()
    private val restartAttempts = mutableMapOf<Key, Int>()
    private val lastUsed = mutableMapOf<Key, Long>()
    private val largeWarned = mutableSetOf<LspDocumentKey>()

    @Volatile
    private var config = LspManagerConfig()

    fun configure(value: LspManagerConfig) {
        val previous = config
        config = value
        if (previous.settings.enabled && !value.settings.enabled) {
            stopAll()
            return
        }
        val unavailable = synchronized(lock) {
            sessions.keys.filter { key ->
                value.enabled[key.serverId] == false || key.serverId !in value.installed
            }
        }
        unavailable.forEach { key ->
            onLog(key.serverId, LspLogLevel.INFO, "Server unavailable; stopping session")
            scope.launch { stopSession(key) }
        }
        // A server installed while a file is already open should start without
        // forcing the user to close and reopen the tab.
        val newlyInstalled = value.installed - previous.installed
        if (newlyInstalled.isNotEmpty() && value.settings.enabled && value.settings.autoStart) {
            tracker.allDocuments()
                .filter { it.serverId in newlyInstalled }
                .groupBy { it.projectId to it.serverId }
                .forEach { (projectServer, documents) ->
                    scope.launch {
                        startSession(
                            Key(projectServer.first, projectServer.second),
                            documents.first().projectPath
                        )
                    }
                }
        }
        if (value.settings.maxSessions < previous.settings.maxSessions) {
            scope.launch { mutex.withLock { evictIfNeeded() } }
        }
    }

    // ---- documents --------------------------------------------------------

    fun openDocument(
        projectId: Long,
        projectPath: String,
        relativePath: String,
        language: dev.mobileide.domain.editor.EditorLanguage,
        spec: LspServerSpec,
        text: String
    ) {
        val current = config
        if (!current.settings.enabled) return
        if (current.enabled[spec.id] == false) return
        // Documents are tracked even before install, so enabling or installing
        // the server later takes effect without reopening the file.
        val languageId = spec.languageId(language) ?: return

        val update = tracker.open(
            projectId = projectId,
            projectPath = projectPath,
            relativePath = relativePath,
            language = language,
            serverId = spec.id,
            languageId = languageId,
            text = text
        )
        val document = update.document
        val session = readySession(projectId, spec.id)
        when {
            update.opened -> session?.didOpen(
                document.uri,
                document.languageId,
                document.version,
                document.text
            )

            update.changed -> session?.didChange(document.uri, document.version, document.text)
        }
        if (update.skippedLarge) warnLarge(document.key)
        touch(projectId, spec.id)
        ensureSession(projectId, spec.id, projectPath)
    }

    fun changeDocument(projectId: Long, relativePath: String, text: String) {
        val update = tracker.change(LspDocumentKey(projectId, relativePath), text) ?: return
        if (update.skippedLarge) {
            warnLarge(update.document.key)
            return
        }
        if (!update.changed) return
        val document = update.document
        readySession(projectId, document.serverId)
            ?.didChange(document.uri, document.version, document.text)
        touch(projectId, document.serverId)
    }

    fun saveDocument(projectId: Long, relativePath: String) {
        val document = tracker.get(LspDocumentKey(projectId, relativePath)) ?: return
        readySession(projectId, document.serverId)?.didSave(document.uri)
    }

    fun closeDocument(projectId: Long, relativePath: String) {
        val key = LspDocumentKey(projectId, relativePath)
        val document = tracker.close(key) ?: return
        readySession(projectId, document.serverId)?.didClose(document.uri)
        onDiagnostics(key, emptyList(), null)
        largeWarned.remove(key)
        scheduleIdle(document)
    }

    /** Closes every document of the project; servers stop after the idle timeout. */
    fun closeProject(projectId: Long) {
        val documents = tracker.removeProject(projectId)
        documents.forEach { document ->
            readySession(projectId, document.serverId)?.didClose(document.uri)
            onDiagnostics(document.key, emptyList(), null)
        }
        documents.groupBy { it.serverId }.forEach { (serverId, _) ->
            val key = Key(projectId, serverId)
            if (tracker.documentsFor(projectId, serverId).isEmpty()) {
                synchronized(lock) {
                    idleJobs.remove(key)?.cancel()
                    idleJobs[key] = scope.launch {
                        delay(idleTimeoutMs)
                        onLog(serverId, LspLogLevel.INFO, "Stopping idle language server")
                        stopSession(key)
                    }
                }
            }
        }
    }

    // ---- lifecycle --------------------------------------------------------

    fun stopAll() {
        val all = synchronized(lock) {
            val values = sessions.values.toList()
            sessions.clear()
            values
        }
        all.forEach { it.forceStop() }
        synchronized(lock) {
            idleJobs.values.forEach { it.cancel() }
            idleJobs.clear()
        }
        onSessionsChanged()
    }

    /** Immediate stop, used when the project itself is removed. */
    fun stopProject(projectId: Long) {
        val keys = synchronized(lock) { sessions.keys.filter { it.projectId == projectId } }
        keys.forEach { key -> synchronized(lock) { sessions.remove(key) }?.forceStop() }
        tracker.removeProject(projectId)
        onSessionsChanged()
    }

    fun stopServer(serverId: String) {
        val keys = synchronized(lock) { sessions.keys.filter { it.serverId == serverId } }
        scope.launch { keys.forEach { stopSession(it) } }
    }

    /** Stops and starts every session of [serverId] that has open documents. */
    suspend fun restartServer(serverId: String) {
        val keys = synchronized(lock) { sessions.keys.filter { it.serverId == serverId } }
        keys.forEach { stopSession(it) }
        tracker.allDocuments()
            .filter { it.serverId == serverId }
            .groupBy { it.projectId }
            .forEach { (projectId, documents) ->
                startSession(Key(projectId, serverId), documents.first().projectPath)
            }
    }

    fun sessionInfos(): List<LspSessionInfo> = synchronized(lock) {
        sessions.map { (key, session) ->
            LspSessionInfo(
                serverId = key.serverId,
                projectId = key.projectId,
                state = session.state,
                capabilities = session.capabilities,
                openDocuments = session.openDocumentCount,
                startedAt = session.startedAt,
                lastError = session.lastError
            )
        }
    }

    fun runningCount(): Int = synchronized(lock) { sessions.size }

    fun openDocumentCount(serverId: String): Int =
        trackerDocuments(serverId).size

    // ---- queries ----------------------------------------------------------

    suspend fun completion(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): List<LspCompletionItem> {
        val document = tracker.get(LspDocumentKey(projectId, relativePath)) ?: return emptyList()
        val session = readySession(projectId, document.serverId) ?: return emptyList()
        touch(projectId, document.serverId)
        return session.completion(document.uri, line, column)
    }

    suspend fun hover(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): LspHover? {
        val document = tracker.get(LspDocumentKey(projectId, relativePath)) ?: return null
        val session = readySession(projectId, document.serverId) ?: return null
        touch(projectId, document.serverId)
        return session.hover(document.uri, line, column)
    }

    suspend fun definition(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): List<LspLocation> {
        val document = tracker.get(LspDocumentKey(projectId, relativePath)) ?: return emptyList()
        val session = readySession(projectId, document.serverId) ?: return emptyList()
        touch(projectId, document.serverId)
        return session.definition(document.uri, line, column).toLocations()
    }

    suspend fun references(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): List<LspLocation> {
        val document = tracker.get(LspDocumentKey(projectId, relativePath)) ?: return emptyList()
        val session = readySession(projectId, document.serverId) ?: return emptyList()
        touch(projectId, document.serverId)
        return session.references(document.uri, line, column).toLocations()
    }

    suspend fun documentSymbols(
        projectId: Long,
        relativePath: String
    ): List<LspDocumentSymbol> {
        val document = tracker.get(LspDocumentKey(projectId, relativePath)) ?: return emptyList()
        val session = readySession(projectId, document.serverId) ?: return emptyList()
        touch(projectId, document.serverId)
        return session.documentSymbols(document.uri)
    }

    suspend fun format(projectId: Long, relativePath: String): List<LspTextEdit> {
        val document = tracker.get(LspDocumentKey(projectId, relativePath)) ?: return emptyList()
        val session = readySession(projectId, document.serverId) ?: return emptyList()
        touch(projectId, document.serverId)
        return session.format(document.uri)
    }

    fun capabilities(projectId: Long, relativePath: String): LspCapabilities {
        val document = tracker.get(LspDocumentKey(projectId, relativePath)) ?: return LspCapabilities()
        return synchronized(lock) { sessions[Key(projectId, document.serverId)] }
            ?.capabilities
            ?: LspCapabilities()
    }

    fun serverIdFor(projectId: Long, relativePath: String): String? =
        tracker.get(LspDocumentKey(projectId, relativePath))?.serverId

    fun isRunning(serverId: String): Boolean =
        synchronized(lock) { sessions.keys.any { it.serverId == serverId } }

    // ---- internals --------------------------------------------------------

    private fun ensureSession(projectId: Long, serverId: String, projectPath: String) {
        val current = config
        if (!current.settings.enabled || !current.settings.autoStart) return
        if (current.enabled[serverId] == false || serverId !in current.installed) return
        val key = Key(projectId, serverId)
        synchronized(lock) { idleJobs.remove(key)?.cancel() }
        scope.launch { startSession(key, projectPath) }
    }

    private suspend fun startSession(key: Key, projectPath: String) = mutex.withLock {
        val current = config
        if (!current.settings.enabled) return@withLock
        val spec = current.specs[key.serverId] ?: return@withLock
        if (current.enabled[spec.id] == false || spec.id !in current.installed) return@withLock

        val existing = synchronized(lock) { sessions[key] }
        if (existing != null && (existing.state == LspServerState.READY ||
                existing.state == LspServerState.STARTING)
        ) {
            return@withLock
        }
        if (existing != null) {
            synchronized(lock) { sessions.remove(key) }
            existing.forceStop()
        }

        evictIfNeeded()

        val launch = runtime.launch(
            spec = spec,
            projectId = key.projectId,
            projectPath = projectPath,
            extraArgs = current.extraArgs[spec.id].orEmpty()
        ) ?: run {
            onLog(spec.id, LspLogLevel.ERROR, "Could not start ${spec.displayName}")
            return@withLock
        }

        lateinit var session: LspSession
        session = LspSession(
            spec = spec,
            projectId = key.projectId,
            projectPath = projectPath,
            launch = launch,
            runtime = runtime,
            scope = scope,
            projectName = projectPath.substringAfterLast('/').ifEmpty { "project" },
            onNotification = { method, params -> handleNotification(key, method, params) },
            onExit = { code -> handleExit(key, session, code) },
            onLog = { level, message -> onLog(spec.id, level, message) }
        )
        synchronized(lock) {
            sessions[key] = session
            lastUsed[key] = clock()
        }
        onSessionsChanged()
        session.start()

        if (!session.initialize()) {
            synchronized(lock) { sessions.remove(key) }
            session.forceStop()
            onSessionsChanged()
            return@withLock
        }
        restartAttempts[key] = 0
        tracker.documentsFor(key.projectId, key.serverId).forEach { document ->
            session.didOpen(
                document.uri,
                document.languageId,
                document.version,
                document.text
            )
        }
        onSessionsChanged()
    }

    private suspend fun stopSession(key: Key) {
        val session = synchronized(lock) { sessions.remove(key) }
        session?.shutdown()
        synchronized(lock) { idleJobs.remove(key)?.cancel() }
        onSessionsChanged()
    }

    private suspend fun evictIfNeeded() {
        val max = config.settings.maxSessions.coerceIn(1, 3)
        while (true) {
            val current = synchronized(lock) { sessions.toList() }
            if (current.size < max) return
            val victim = current
                .filter { (_, session) ->
                    session.state == LspServerState.READY ||
                        session.state == LspServerState.CRASHED ||
                        session.state == LspServerState.STOPPED
                }
                .minWithOrNull(
                    compareBy(
                        { (_, session) -> session.openDocumentCount },
                        { (key, _) -> lastUsed[key] ?: 0L }
                    )
                )
                ?: current.minByOrNull { (key, _) -> lastUsed[key] ?: 0L }
                ?: return
            onLog(victim.first.serverId, LspLogLevel.INFO, "Stopping the least recently used server")
            stopSession(victim.first)
        }
    }

    private fun handleExit(key: Key, session: LspSession, code: Int?) {
        scope.launch {
            val removed = synchronized(lock) {
                if (sessions[key] === session) {
                    sessions.remove(key)
                } else {
                    null
                }
            }
            if (removed == null) return@launch
            onSessionsChanged()
            val documents = tracker.documentsFor(key.projectId, key.serverId)
            val attempts = restartAttempts.getOrDefault(key, 0)
            val current = config
            if (documents.isNotEmpty() &&
                attempts < MAX_RESTART_ATTEMPTS &&
                current.settings.enabled &&
                current.settings.autoStart
            ) {
                restartAttempts[key] = attempts + 1
                onLog(
                    key.serverId,
                    LspLogLevel.WARN,
                    "Server exited (${code ?: "?"}); restarting ${attempts + 1}/$MAX_RESTART_ATTEMPTS"
                )
                delay(1_000L * (attempts + 1))
                startSession(key, documents.first().projectPath)
            } else if (documents.isNotEmpty()) {
                onLog(key.serverId, LspLogLevel.ERROR, "Server stopped; open files have no intelligence")
            }
        }
    }

    private fun handleNotification(key: Key, method: String, params: JSONObject?) {
        when (method) {
            "textDocument/publishDiagnostics" -> {
                val payload = LspProtocol.parseDiagnostics(params) ?: return
                val relative = GuestUri.relativePath(payload.uri) ?: run {
                    onLog(key.serverId, LspLogLevel.WARN, "Ignored diagnostics outside the workspace")
                    return
                }
                if (relative.isEmpty()) return
                val documentKey = LspDocumentKey(key.projectId, relative)
                val document = tracker.get(documentKey)
                if (payload.version != null && document != null && payload.version < document.version) {
                    return
                }
                val capped = payload.diagnostics.take(MAX_DIAGNOSTICS)
                onDiagnostics(documentKey, capped, payload.version)
            }

            "window/logMessage", "window/showMessage" -> {
                val message = params?.optString("message").orEmpty()
                if (message.isNotBlank()) {
                    val level = when (params?.optInt("type", 3)) {
                        1 -> LspLogLevel.ERROR
                        2 -> LspLogLevel.WARN
                        else -> LspLogLevel.INFO
                    }
                    onLog(key.serverId, level, message.take(300))
                }
            }
        }
    }

    private fun readySession(projectId: Long, serverId: String): LspSession? =
        synchronized(lock) { sessions[Key(projectId, serverId)] }
            ?.takeIf { it.state == LspServerState.READY }

    private fun touch(projectId: Long, serverId: String) {
        synchronized(lock) { lastUsed[Key(projectId, serverId)] = clock() }
    }

    private fun scheduleIdle(document: TrackedDocument) {
        val key = Key(document.projectId, document.serverId)
        if (tracker.documentsFor(key.projectId, key.serverId).isNotEmpty()) return
        synchronized(lock) {
            idleJobs.remove(key)?.cancel()
            idleJobs[key] = scope.launch {
                delay(idleTimeoutMs)
                onLog(key.serverId, LspLogLevel.INFO, "Stopping idle language server")
                stopSession(key)
            }
        }
    }

    private fun trackerDocuments(serverId: String): List<TrackedDocument> =
        tracker.allDocuments().filter { it.serverId == serverId }

    private fun warnLarge(key: LspDocumentKey) {
        val added = synchronized(lock) { largeWarned.add(key) }
        if (added) {
            onLog(
                "workspace",
                LspLogLevel.WARN,
                "${key.relativePath} is larger than the sync limit; language features are off for it"
            )
        }
    }

    private fun List<Pair<String, LspRange>>.toLocations(): List<LspLocation> =
        mapNotNull { (uri, range) ->
            val relative = GuestUri.relativePath(uri) ?: return@mapNotNull null
            if (relative.isEmpty()) return@mapNotNull null
            LspLocation(relative, range)
        }

    private companion object {
        const val MAX_RESTART_ATTEMPTS = 2
        const val MAX_DIAGNOSTICS = 500
    }
}
