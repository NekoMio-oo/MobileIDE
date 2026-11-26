package dev.mobileide.domain.runtime

/** Languages and environments MobileIDE can manage. */
enum class RuntimeId {
    LINUX,
    PYTHON,
    NODEJS,
    C,
    CPP,
    JAVA,
    KOTLIN,
    GO,
    RUST,
    TYPESCRIPT,
    HTML
}

enum class RuntimeBackend {
    /** A Debian userland executed through PRoot, without root. */
    PROOT,

    /** Built into the app; HTML/CSS renders in a WebView. */
    WEBVIEW
}

enum class RuntimeState {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLING,
    INSTALLED,
    CHECKING,
    FAILED,
    UNSUPPORTED,
    UPDATE_AVAILABLE
}

enum class RuntimeStage {
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    FINALIZING
}

data class RuntimeInfo(
    val id: RuntimeId,
    val displayName: String,
    val runtimeName: String,
    val backend: RuntimeBackend,
    val state: RuntimeState,
    val version: String? = null,
    val installPath: String? = null,
    val sizeBytes: Long? = null,
    val message: String? = null,
    val canInstall: Boolean = false,
    val requiresLinux: Boolean = false,
    val packages: List<String> = emptyList()
)

data class RuntimeProgress(
    val runtimeId: RuntimeId,
    val stage: RuntimeStage,
    val fileName: String? = null,
    val bytesDone: Long = 0,
    val bytesTotal: Long? = null
)

/** One downloadable artifact with its integrity data. */
data class RemoteFile(
    val url: String,
    val sha256: String,
    val fileName: String,
    val sizeBytes: Long? = null
)

data class LinuxEnvironmentSpec(
    val name: String,
    val version: String,
    val rootfs: RemoteFile,
    val proot: RemoteFile,
    val prootLibs: List<RemoteFile>,
    val rootfsStripComponents: Int = 0
)

data class LanguageSpec(
    val id: RuntimeId,
    val displayName: String,
    val toolName: String,
    val versionArgs: List<String>,
    val aptPackages: List<String>,
    /** Non-null when the language cannot be provisioned from the catalog yet. */
    val unsupportedReason: String? = null
)

/**
 * Runtime sources come from a catalog (assets by default, overridable from
 * files/runtime-config). No download URL is hardcoded in code.
 */
data class RuntimeCatalog(
    val version: Int,
    val linux: LinuxEnvironmentSpec?,
    val languages: List<LanguageSpec>
)

/** One provisioned runtime, persisted in files/runtime-config/installed.properties. */
data class InstalledRuntimeRecord(
    val id: String,
    val version: String,
    val installedAt: Long,
    val sha256: String,
    val sizeBytes: Long,
    val path: String
)
