package dev.mobileide.domain.lsp

import dev.mobileide.domain.editor.EditorLanguage

/** Lifecycle of one language server, as shown in the settings screen. */
enum class LspServerState {
    /** The catalog marks this language as having no usable ARM64 server. */
    UNSUPPORTED,

    /** Not provisioned in the Debian userland. */
    NOT_INSTALLED,

    /** An install is running. */
    INSTALLING,

    /** Installed but not running. */
    STOPPED,

    /** Process started, waiting for the `initialize` response. */
    STARTING,

    /** Initialized and serving requests. */
    READY,

    /** Exited unexpectedly; a restart may be attempted. */
    CRASHED
}

enum class LspInstallKind { APT, ARCHIVE }

/** How the process is assembled from the installed files. */
enum class LspLaunchKind {
    /** Run [LspServerSpec.binary] directly. */
    DIRECT,

    /** Build the Eclipse JDT LS java command line. */
    JDTLS
}

/** Capabilities the client may expose; only advertised ones are shown. */
data class LspCapabilities(
    val completion: Boolean = false,
    val hover: Boolean = false,
    val definition: Boolean = false,
    val references: Boolean = false,
    val documentSymbol: Boolean = false,
    val formatting: Boolean = false
) {
    val any: Boolean
        get() = completion || hover || definition || references || documentSymbol || formatting

    /** Capabilities reported by the server win over the catalog defaults. */
    fun intersect(other: LspCapabilities): LspCapabilities = LspCapabilities(
        completion = completion && other.completion,
        hover = hover && other.hover,
        definition = definition && other.definition,
        references = references && other.references,
        documentSymbol = documentSymbol && other.documentSymbol,
        formatting = formatting && other.formatting
    )

    companion object {
        val ALL = LspCapabilities(
            completion = true,
            hover = true,
            definition = true,
            references = true,
            documentSymbol = true,
            formatting = true
        )
    }
}

/** One entry of the server catalog (assets/lsp_catalog.properties). */
data class LspServerSpec(
    val id: String,
    val displayName: String,
    val languages: List<EditorLanguage>,
    val languageIds: Map<EditorLanguage, String>,
    val installKind: LspInstallKind,
    val packages: List<String> = emptyList(),
    val downloadUrl: String? = null,
    val downloadSha256: String? = null,
    val downloadSizeBytes: Long = 0L,
    /** Guest path of the executable, or of `java` for the JDT launcher. */
    val binary: String = "",
    val args: List<String> = emptyList(),
    val versionArgs: List<String> = emptyList(),
    val launchKind: LspLaunchKind = LspLaunchKind.DIRECT,
    /** Guest mount point for archive-based servers, e.g. `/opt/jdtls`. */
    val guestMount: String? = null,
    val sizeMb: Long = 0L,
    val docsUrl: String? = null,
    val declaredCapabilities: LspCapabilities = LspCapabilities.ALL,
    val unsupportedReason: String? = null
) {
    val supported: Boolean
        get() = unsupportedReason == null && languages.isNotEmpty()

    fun languageId(language: EditorLanguage): String? = languageIds[language]
}

/** A server as presented to the UI. */
data class LspServerInfo(
    val id: String,
    val displayName: String,
    val languages: List<EditorLanguage>,
    val state: LspServerState,
    val installed: Boolean,
    val enabled: Boolean = true,
    val version: String? = null,
    val stateDetail: String? = null,
    val sizeMb: Long = 0L,
    val installKind: LspInstallKind = LspInstallKind.APT,
    val docsUrl: String? = null,
    val capabilities: LspCapabilities = LspCapabilities(),
    val sessionCount: Int = 0,
    val busy: Boolean = false,
    val lastError: String? = null,
    /** User-supplied CLI flags appended to the catalog arguments. */
    val extraArgs: List<String> = emptyList()
)

data class LspSettings(
    val enabled: Boolean = true,
    val autoStart: Boolean = true,
    val maxSessions: Int = 2
)

enum class LspInstallStage { DOWNLOADING, VERIFYING, EXTRACTING, INSTALLING, DONE, FAILED }

data class LspInstallProgress(
    val serverId: String,
    val stage: LspInstallStage,
    val message: String? = null,
    val bytesDone: Long = 0L,
    val bytesTotal: Long? = null
) {
    val fraction: Float?
        get() {
            val total = bytesTotal ?: return null
            if (total <= 0L) return null
            return (bytesDone.toFloat() / total).coerceIn(0f, 1f)
        }
}

data class LspSnapshot(
    val isLoading: Boolean = true,
    val linuxInstalled: Boolean = false,
    val settings: LspSettings = LspSettings(),
    val servers: List<LspServerInfo> = emptyList(),
    val install: LspInstallProgress? = null,
    val error: String? = null,
    val message: String? = null
)

/** Identifies one document; the project id keeps sessions and diagnostics apart. */
data class LspDocumentKey(
    val projectId: Long,
    val relativePath: String
)

// ---- Protocol-level value objects -----------------------------------------

data class LspPosition(val line: Int, val character: Int)

data class LspRange(val start: LspPosition, val end: LspPosition)

enum class LspSeverity { ERROR, WARNING, INFO, HINT }

data class LspDiagnostic(
    val range: LspRange,
    val severity: LspSeverity,
    val message: String,
    val source: String? = null,
    val code: String? = null
)

data class LspTextEdit(val range: LspRange, val newText: String)

data class LspCompletionItem(
    val label: String,
    val detail: String? = null,
    val kind: Int = 1,
    val insertText: String? = null,
    val textEdit: LspTextEdit? = null,
    val additionalEdits: List<LspTextEdit> = emptyList(),
    val sortText: String? = null,
    val filterText: String? = null,
    val documentation: String? = null,
    val deprecated: Boolean = false
)

data class LspHover(val contents: String, val range: LspRange? = null)

data class LspLocation(
    val relativePath: String,
    val range: LspRange
)

data class LspDocumentSymbol(
    val name: String,
    val detail: String?,
    val kind: Int,
    val range: LspRange,
    val selectionRange: LspRange,
    val children: List<LspDocumentSymbol> = emptyList()
)

enum class LspLogLevel { INFO, WARN, ERROR, SERVER }

data class LspLogEntry(
    val time: Long,
    val level: LspLogLevel,
    val message: String
)

/** Runtime state of one (project, server) session, used by the UI. */
data class LspSessionInfo(
    val serverId: String,
    val projectId: Long,
    val state: LspServerState,
    val capabilities: LspCapabilities = LspCapabilities(),
    val openDocuments: Int = 0,
    val startedAt: Long = 0L,
    val lastError: String? = null
)
