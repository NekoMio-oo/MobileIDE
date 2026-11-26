package dev.mobileide.domain.lsp

import dev.mobileide.domain.editor.EditorLanguage
import kotlinx.coroutines.flow.StateFlow

/**
 * Language intelligence surface. The editor only talks to this interface; it
 * never starts processes or speaks JSON-RPC itself.
 *
 * Document methods are fire-and-forget: they update the cached text, schedule a
 * `didChange` and return immediately so typing never waits on a server.
 */
interface LspRepository {

    val state: StateFlow<LspSnapshot>

    /** Diagnostics per document, published by the servers. */
    val diagnostics: StateFlow<Map<LspDocumentKey, List<LspDiagnostic>>>

    // ---- management -------------------------------------------------------

    /** Reloads the catalog, settings and install state. */
    suspend fun refresh(force: Boolean = false)

    suspend fun install(serverId: String)

    fun cancelInstall()

    suspend fun uninstall(serverId: String)

    fun setEnabled(serverId: String, enabled: Boolean)

    fun setGlobalEnabled(enabled: Boolean)

    fun setAutoStart(enabled: Boolean)

    fun setMaxSessions(count: Int)

    /** Extra CLI flags appended to the catalog arguments; applied on restart. */
    fun setExtraArgs(serverId: String, args: List<String>)

    /** Stops the running instances of [serverId] and starts it again on demand. */
    suspend fun restart(serverId: String)

    fun stopAll()

    fun logs(serverId: String): List<LspLogEntry>

    fun clearLogs(serverId: String)

    fun dismissError()

    // ---- documents --------------------------------------------------------

    /** Opens or refreshes a document. Safe to call on every tab activation. */
    fun openDocument(
        projectId: Long,
        projectPath: String,
        relativePath: String,
        language: EditorLanguage,
        text: String
    )

    fun changeDocument(projectId: Long, relativePath: String, text: String)

    fun saveDocument(projectId: Long, relativePath: String)

    fun closeDocument(projectId: Long, relativePath: String)

    /** Closes documents; servers stop after the idle timeout. */
    fun closeProject(projectId: Long)

    /** Stops the project's servers immediately (project removed or deleted). */
    fun stopProject(projectId: Long)

    // ---- queries ----------------------------------------------------------

    suspend fun completion(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): List<LspCompletionItem>

    suspend fun hover(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): LspHover?

    suspend fun definition(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): List<LspLocation>

    suspend fun references(
        projectId: Long,
        relativePath: String,
        line: Int,
        column: Int
    ): List<LspLocation>

    suspend fun documentSymbols(
        projectId: Long,
        relativePath: String
    ): List<LspDocumentSymbol>

    suspend fun format(
        projectId: Long,
        relativePath: String
    ): List<LspTextEdit>

    /** Capabilities of the server serving this document, for menu visibility. */
    fun capabilities(projectId: Long, relativePath: String): LspCapabilities

    /** Server available for [language], regardless of install state. */
    fun serverFor(language: EditorLanguage): LspServerInfo?
}
