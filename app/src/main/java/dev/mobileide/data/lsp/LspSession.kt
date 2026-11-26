package dev.mobileide.data.lsp

import dev.mobileide.domain.lsp.LspCapabilities
import dev.mobileide.domain.lsp.LspCompletionItem
import dev.mobileide.domain.lsp.LspDocumentSymbol
import dev.mobileide.domain.lsp.LspHover
import dev.mobileide.domain.lsp.LspLogLevel
import dev.mobileide.domain.lsp.LspRange
import dev.mobileide.domain.lsp.LspServerSpec
import dev.mobileide.domain.lsp.LspServerState
import dev.mobileide.domain.lsp.LspTextEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One language-server process plus its JSON-RPC connection.
 *
 * Document versions live in the session manager; the session only remembers
 * which URIs were announced so `didChange` is never sent for a closed document.
 */
class LspSession(
    val spec: LspServerSpec,
    val projectId: Long,
    val projectPath: String,
    private val launch: LspServerRuntime.Launch,
    private val runtime: LspServerRuntime,
    private val scope: CoroutineScope,
    private val projectName: String,
    private val onNotification: (method: String, params: JSONObject?) -> Unit,
    private val onExit: (exitCode: Int?) -> Unit,
    private val onLog: (LspLogLevel, String) -> Unit
) {

    private val closing = AtomicBoolean(false)
    private val opened = mutableSetOf<String>()
    private var connection: LspConnection? = null
    private var initialized = false

    @Volatile
    var state: LspServerState = LspServerState.STARTING
        private set

    @Volatile
    var capabilities: LspCapabilities = LspCapabilities()
        private set

    @Volatile
    var lastError: String? = null
        private set

    val startedAt: Long = System.currentTimeMillis()

    val openDocumentCount: Int
        @Synchronized get() = opened.size

    fun start() {
        val connection = LspConnection(
            input = launch.input,
            output = launch.output,
            scope = scope,
            handler = handler,
            log = onLog
        )
        this.connection = connection
        connection.start()
        pumpStderr()
        scope.launch(Dispatchers.IO) {
            val code = runCatching { launch.process.waitFor() }.getOrNull()
            if (!closing.get()) {
                state = LspServerState.CRASHED
                lastError = "Language server exited" + (code?.let { " with code $it" } ?: "")
                onLog(LspLogLevel.ERROR, lastError ?: "Language server exited")
                onExit(code)
            }
        }
    }

    /** Sends `initialize` and `initialized`; returns false when the server refuses. */
    suspend fun initialize(): Boolean {
        if (initialized) return true
        val active = connection ?: return false
        return try {
            val result = active.requestObject("initialize", initializeParams(), 30_000L)
            val reported = LspProtocol.parseCapabilities(result)
            capabilities = reported.intersect(spec.declaredCapabilities)
            active.notify("initialized", JSONObject())
            initialized = true
            state = LspServerState.READY
            onLog(
                LspLogLevel.INFO,
                "Initialized ${spec.displayName} " +
                    "(completion=${capabilities.completion}, hover=${capabilities.hover})"
            )
            true
        } catch (error: Throwable) {
            lastError = error.message ?: "initialize failed"
            state = LspServerState.CRASHED
            onLog(LspLogLevel.ERROR, "initialize failed: ${error.message}")
            false
        }
    }

    // ---- documents --------------------------------------------------------

    @Synchronized
    fun didOpen(uri: String, languageId: String, version: Int, text: String) {
        if (uri in opened) {
            didChange(uri, version, text)
            return
        }
        opened += uri
        notify(
            "textDocument/didOpen",
            JSONObject().put(
                "textDocument",
                JSONObject()
                    .put("uri", uri)
                    .put("languageId", languageId)
                    .put("version", version)
                    .put("text", text)
            )
        )
    }

    @Synchronized
    fun didChange(uri: String, version: Int, text: String) {
        if (uri !in opened) return
        notify(
            "textDocument/didChange",
            JSONObject()
                .put("textDocument", JSONObject().put("uri", uri).put("version", version))
                .put(
                    "contentChanges",
                    JSONArray().put(JSONObject().put("text", text))
                )
        )
    }

    @Synchronized
    fun didSave(uri: String) {
        if (uri !in opened) return
        notify(
            "textDocument/didSave",
            JSONObject().put("textDocument", JSONObject().put("uri", uri))
        )
    }

    @Synchronized
    fun didClose(uri: String) {
        if (!opened.remove(uri)) return
        notify(
            "textDocument/didClose",
            JSONObject().put("textDocument", JSONObject().put("uri", uri))
        )
    }

    @Synchronized
    fun closeAllDocuments() {
        opened.toList().forEach { didClose(it) }
    }

    // ---- requests ---------------------------------------------------------

    suspend fun completion(uri: String, line: Int, column: Int): List<LspCompletionItem> {
        if (state != LspServerState.READY || !capabilities.completion) return emptyList()
        val result = request("textDocument/completion", positionParams(uri, line, column), 4_000L)
        return LspProtocol.parseCompletion(result)
    }

    suspend fun hover(uri: String, line: Int, column: Int): LspHover? {
        if (state != LspServerState.READY || !capabilities.hover) return null
        return LspProtocol.parseHover(
            request("textDocument/hover", positionParams(uri, line, column), 8_000L)
        )
    }

    suspend fun definition(uri: String, line: Int, column: Int): List<Pair<String, LspRange>> {
        if (state != LspServerState.READY || !capabilities.definition) return emptyList()
        val result = request("textDocument/definition", positionParams(uri, line, column), 8_000L)
        return LspProtocol.parseLocationPairs(result)
    }

    suspend fun references(uri: String, line: Int, column: Int): List<Pair<String, LspRange>> {
        if (state != LspServerState.READY || !capabilities.references) return emptyList()
        val params = positionParams(uri, line, column)
            .put("context", JSONObject().put("includeDeclaration", true))
        val result = request("textDocument/references", params, 10_000L)
        return LspProtocol.parseLocationPairs(result)
    }

    suspend fun documentSymbols(uri: String): List<LspDocumentSymbol> {
        if (state != LspServerState.READY || !capabilities.documentSymbol) return emptyList()
        val result = request(
            "textDocument/documentSymbol",
            JSONObject().put("textDocument", JSONObject().put("uri", uri)),
            15_000L
        )
        return LspProtocol.parseDocumentSymbols(result)
    }

    suspend fun format(uri: String): List<LspTextEdit> {
        if (state != LspServerState.READY || !capabilities.formatting) return emptyList()
        val result = request(
            "textDocument/formatting",
            JSONObject()
                .put("textDocument", JSONObject().put("uri", uri))
                .put("options", JSONObject().put("tabSize", 4).put("insertSpaces", true)),
            20_000L
        )
        return LspProtocol.parseTextEdits(result)
    }

    private suspend fun request(method: String, params: JSONObject, timeoutMs: Long): Any? {
        val active = connection ?: return null
        return runCatching { active.requestRaw(method, params, timeoutMs) }
            .onFailure { onLog(LspLogLevel.WARN, "$method failed: ${it.message}") }
            .getOrNull()
    }

    // ---- shutdown ---------------------------------------------------------

    suspend fun shutdown() {
        if (!closing.compareAndSet(false, true)) return
        val active = connection
        if (active != null && !active.isClosed && initialized) {
            withTimeoutOrNull(2_500L) {
                runCatching {
                    active.requestRaw("shutdown", null, 2_000L)
                    active.notify("exit", null)
                }
            }
            delay(120L)
        }
        active?.close()
        runtime.stop(launch)
        state = LspServerState.STOPPED
        onLog(LspLogLevel.INFO, "Stopped ${spec.displayName}")
    }

    fun forceStop() {
        closing.set(true)
        connection?.close()
        runtime.stop(launch)
        state = LspServerState.STOPPED
    }

    // ---- internals --------------------------------------------------------

    private fun pumpStderr() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                BufferedReader(InputStreamReader(launch.process.errorStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) onLog(LspLogLevel.SERVER, line.take(300))
                    }
                }
            }
        }
    }

    private fun notify(method: String, params: JSONObject) {
        connection?.notify(method, params)
    }

    private fun positionParams(uri: String, line: Int, column: Int): JSONObject =
        LspProtocol.positionParams(uri, line, column)

    private fun initializeParams(): JSONObject {
        val rootUri = GuestUri.encode("")
        val workspaceFolders = JSONArray().put(
            JSONObject().put("uri", rootUri).put("name", projectName)
        )
        return JSONObject()
            .put("processId", JSONObject.NULL)
            .put("clientInfo", JSONObject().put("name", "MobileIDE").put("version", "0.1.0"))
            .put("rootUri", rootUri)
            .put("rootPath", GuestUri.GUEST_ROOT)
            .put("workspaceFolders", workspaceFolders)
            .put("initializationOptions", JSONObject().put("settings", JSONObject()))
            .put("capabilities", clientCapabilities())
            .put(
                "trace",
                "off"
            )
    }

    private fun clientCapabilities(): JSONObject = JSONObject()
        .put(
            "workspace",
            JSONObject()
                .put("workspaceFolders", true)
                .put("configuration", false)
                .put("applyEdit", false)
                .put("didChangeWatchedFiles", JSONObject().put("dynamicRegistration", false))
        )
        .put(
            "textDocument",
            JSONObject()
                .put(
                    "synchronization",
                    JSONObject()
                        .put("dynamicRegistration", false)
                        .put("willSave", false)
                        .put("willSaveWaitUntil", false)
                        .put("didSave", true)
                )
                .put(
                    "completion",
                    JSONObject()
                        .put("dynamicRegistration", false)
                        .put("contextSupport", true)
                        .put(
                            "completionItem",
                            JSONObject()
                                .put("snippetSupport", false)
                                .put("documentationFormat", JSONArray().put("markdown").put("plaintext"))
                                .put("insertReplaceSupport", true)
                        )
                )
                .put(
                    "hover",
                    JSONObject()
                        .put("dynamicRegistration", false)
                        .put("contentFormat", JSONArray().put("markdown").put("plaintext"))
                )
                .put("definition", JSONObject().put("dynamicRegistration", false).put("linkSupport", false))
                .put("references", JSONObject().put("dynamicRegistration", false))
                .put(
                    "documentSymbol",
                    JSONObject()
                        .put("dynamicRegistration", false)
                        .put("hierarchicalDocumentSymbolSupport", true)
                )
                .put("formatting", JSONObject().put("dynamicRegistration", false))
                .put(
                    "publishDiagnostics",
                    JSONObject().put("relatedInformation", false).put("versionSupport", true)
                )
        )
        .put("window", JSONObject().put("workDoneProgress", false))

    private val handler = object : LspConnection.Handler {
        override suspend fun onRequest(
            method: String,
            params: JSONObject?
        ): LspConnection.ServerRequestResult = when (method) {
            "client/registerCapability",
            "client/unregisterCapability",
            "window/workDoneProgress/create" -> LspConnection.ServerRequestResult.Success(null)

            "workspace/configuration" -> {
                val count = params?.optJSONArray("items")?.length() ?: 0
                val values = JSONArray()
                repeat(count) { values.put(JSONObject.NULL) }
                LspConnection.ServerRequestResult.Success(values)
            }

            "workspace/workspaceFolders" -> {
                val folder = JSONObject()
                    .put("uri", GuestUri.encode(""))
                    .put("name", projectName)
                LspConnection.ServerRequestResult.Success(JSONArray().put(folder))
            }

            // Editing the workspace from a server request would bypass the
            // editor's undo stack and unsaved buffers; refuse it explicitly.
            "workspace/applyEdit" -> LspConnection.ServerRequestResult.Success(
                JSONObject().put("applied", false).put("failureReason", "Not supported by MobileIDE")
            )

            "window/showMessageRequest" -> LspConnection.ServerRequestResult.Success(null)

            else -> LspConnection.ServerRequestResult.Unhandled
        }

        override fun onNotification(method: String, params: JSONObject?) {
            when (method) {
                "textDocument/publishDiagnostics",
                "window/logMessage",
                "window/showMessage" -> onNotification(method, params)

                else -> Unit
            }
        }

        override fun onClosed(cause: Throwable?) {
            if (!closing.get()) {
                state = LspServerState.CRASHED
                lastError = cause?.message ?: "Connection closed"
                onLog(LspLogLevel.ERROR, lastError ?: "Connection closed")
                onExit(null)
            }
        }
    }
}
