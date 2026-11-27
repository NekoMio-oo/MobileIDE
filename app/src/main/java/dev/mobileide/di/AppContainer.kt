package dev.mobileide.di

import android.app.Application
import android.content.Context
import android.os.Environment
import dev.mobileide.MobileIdeApplication
import dev.mobileide.core.filesystem.NativeFileSystem
import dev.mobileide.core.filesystem.NativeFileSystemImpl
import dev.mobileide.core.storage.AllFilesAccess
import dev.mobileide.core.storage.ProjectRootStore
import dev.mobileide.data.editor.EditorRevealRequests
import dev.mobileide.data.editor.EditorTabManager
import dev.mobileide.data.editor.FileEditorSessionStore
import dev.mobileide.data.build.BuildConfigStore
import dev.mobileide.data.build.BuildManager
import dev.mobileide.data.build.BuildTaskStore
import dev.mobileide.data.build.SourceScanner
import dev.mobileide.data.build.providers.CcBuildProvider
import dev.mobileide.data.build.providers.GoBuildProvider
import dev.mobileide.data.build.providers.JavaBuildProvider
import dev.mobileide.data.build.providers.RustBuildProvider
import dev.mobileide.data.build.providers.TypeScriptBuildProvider
import dev.mobileide.data.build.providers.UnsupportedBuildProvider
import dev.mobileide.data.git.AndroidKeystoreCredentialCrypto
import dev.mobileide.data.git.GitCredentialStore
import dev.mobileide.data.git.GitPaths
import dev.mobileide.data.git.GitRepositoryImpl
import dev.mobileide.data.git.GitSshManager
import dev.mobileide.data.git.ProotGitInstaller
import dev.mobileide.data.git.ProotGitRunner
import dev.mobileide.data.local.ProjectDatabase
import dev.mobileide.data.lsp.LspCatalogLoader
import dev.mobileide.data.lsp.LspManifestStore
import dev.mobileide.data.lsp.LspPaths
import dev.mobileide.data.lsp.LspRepositoryImpl
import dev.mobileide.data.lsp.LspServerInstaller
import dev.mobileide.data.lsp.LspServerRuntime
import dev.mobileide.data.lsp.LspSettingsStore
import dev.mobileide.data.repository.ProjectRepositoryImpl
import dev.mobileide.data.repository.StorageBrowserImpl
import dev.mobileide.data.runtime.LinuxRuntime
import dev.mobileide.data.runtime.LinuxRuntimeInstaller
import dev.mobileide.data.runtime.ProcessExecutor
import dev.mobileide.data.runtime.RuntimeCatalogLoader
import dev.mobileide.data.runtime.RuntimeDetector
import dev.mobileide.data.runtime.RuntimeDownloader
import dev.mobileide.data.runtime.RuntimeManifestStore
import dev.mobileide.data.runtime.RuntimePaths
import dev.mobileide.data.terminal.TerminalPreferences
import dev.mobileide.data.terminal.TerminalSessionManager
import dev.mobileide.data.runtime.RuntimeRepositoryImpl
import dev.mobileide.domain.editor.EditorSessionStore
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildProvider
import dev.mobileide.domain.build.BuildRepository
import dev.mobileide.domain.git.GitRepository
import dev.mobileide.domain.lsp.LspRepository
import dev.mobileide.domain.repository.ProjectRepository
import dev.mobileide.domain.repository.StorageBrowser
import dev.mobileide.domain.runtime.RuntimeRepository
import java.io.File

/**
 * Manual dependency container. The project is small enough that Hilt would add
 * more ceremony than value at this stage.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database: ProjectDatabase by lazy { ProjectDatabase.create(appContext) }

    val allFilesAccess: AllFilesAccess = AllFilesAccess(appContext)

    private val storageRoots: List<String> = sharedStorageRoots(appContext)

    val nativeFileSystem: NativeFileSystem = NativeFileSystemImpl(storageRoots)

    val projectRootStore: ProjectRootStore = ProjectRootStore(nativeFileSystem)

    val storageBrowser: StorageBrowser = StorageBrowserImpl(nativeFileSystem)

    private val editorSessionStore: EditorSessionStore by lazy {
        FileEditorSessionStore(File(appContext.filesDir, "editor_sessions"))
    }

    /** App-wide tab session so the browser and the editor share one truth. */
    val editorTabManager: EditorTabManager by lazy {
        EditorTabManager(editorSessionStore)
    }

    /** One-shot "open file at line" hand-off from build problems to the editor. */
    val editorRevealRequests: EditorRevealRequests = EditorRevealRequests()

    val runtimePaths: RuntimePaths = RuntimePaths(appContext.filesDir, appContext.cacheDir)

    /** One executor for runtime management, builds and future terminals. */
    val runtimeExecutor: ProcessExecutor = ProcessExecutor()

    val linuxRuntime: LinuxRuntime = LinuxRuntime(runtimePaths)

    val runtimeRepository: RuntimeRepository by lazy {
        val manifest = RuntimeManifestStore(runtimePaths.manifestFile)
        RuntimeRepositoryImpl(
            paths = runtimePaths,
            catalogLoader = RuntimeCatalogLoader(
                openDefault = { appContext.assets.open("runtime_catalog.properties") },
                overrideFile = runtimePaths.catalogOverride
            ),
            installer = LinuxRuntimeInstaller(
                paths = runtimePaths,
                downloader = RuntimeDownloader(runtimePaths.downloadsDir),
                manifest = manifest
            ),
            manifest = manifest,
            linux = linuxRuntime,
            executor = runtimeExecutor,
            detector = RuntimeDetector(linuxRuntime, runtimeExecutor),
            webViewVersion = { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }
        )
    }

    val projectRepository: ProjectRepository by lazy {
        ProjectRepositoryImpl(
            projectDao = database.projectDao(),
            fileSystem = nativeFileSystem,
            storageAccess = allFilesAccess
        )
    }

    val terminalPreferences: TerminalPreferences by lazy {
        TerminalPreferences(File(appContext.filesDir, "terminal-config.properties"))
    }

    val terminalManager: TerminalSessionManager by lazy {
        TerminalSessionManager(
            linux = linuxRuntime,
            projectRepository = projectRepository,
            preferences = terminalPreferences,
            homeDir = File(appContext.filesDir, "home")
        )
    }

    /** Language intelligence: parallel to the build system, never nested in it. */
    val lspRepository: LspRepository by lazy {
        val lspPaths = LspPaths(appContext.filesDir, appContext.cacheDir)
        val manifest = LspManifestStore(lspPaths.installedFile)
        LspRepositoryImpl(
            paths = lspPaths,
            linux = linuxRuntime,
            catalogLoader = LspCatalogLoader(
                openDefault = { appContext.assets.open("lsp_catalog.properties") },
                overrideFile = lspPaths.catalogOverride
            ),
            installer = LspServerInstaller(
                paths = lspPaths,
                linux = linuxRuntime,
                executor = runtimeExecutor,
                manifest = manifest,
                downloader = RuntimeDownloader(lspPaths.downloadsDir)
            ),
            manifest = manifest,
            settingsStore = LspSettingsStore(lspPaths.settingsFile),
            runtime = LspServerRuntime(linuxRuntime, lspPaths)
        )
    }

    val buildRepository: BuildRepository by lazy {
        BuildManager(
            repository = projectRepository,
            runtimeRepository = runtimeRepository,
            linux = linuxRuntime,
            executor = runtimeExecutor,
            configStore = BuildConfigStore(projectRepository),
            taskStore = BuildTaskStore(projectRepository),
            scanner = SourceScanner(projectRepository),
            providers = buildProviders()
        )
    }

    /** Git CLI shares the Debian userland with the terminal and the build system. */
    val gitRepository: GitRepository by lazy {
        val gitPaths = GitPaths(appContext.filesDir, appContext.cacheDir)
        val gitCredentials = GitCredentialStore(
            gitPaths.credentialsFile,
            AndroidKeystoreCredentialCrypto()
        )
        GitRepositoryImpl(
            projects = projectRepository,
            runner = ProotGitRunner(linuxRuntime, gitPaths),
            installer = ProotGitInstaller(linuxRuntime, runtimeExecutor),
            linuxInstalled = { linuxRuntime.isInstalled() },
            paths = gitPaths,
            credentials = gitCredentials,
            ssh = GitSshManager(
                linuxRuntime,
                runtimeExecutor,
                gitPaths,
                gitCredentials
            )
        )
    }
}

private fun buildProviders(): Map<BuildLanguage, BuildProvider> = buildMap {
    put(BuildLanguage.C, CcBuildProvider(BuildLanguage.C))
    put(BuildLanguage.CPP, CcBuildProvider(BuildLanguage.CPP))
    put(BuildLanguage.JAVA, JavaBuildProvider())
    put(BuildLanguage.GO, GoBuildProvider())
    put(BuildLanguage.RUST, RustBuildProvider())
    put(BuildLanguage.TYPESCRIPT, TypeScriptBuildProvider())
    BuildLanguage.entries
        .filter { it !in keys }
        .forEach { language ->
            put(
                language,
                UnsupportedBuildProvider(language, UnsupportedBuildProvider.reasonFor(language))
            )
        }
}

private fun sharedStorageRoots(context: Context): List<String> {
    val roots = linkedSetOf<String>()

    @Suppress("DEPRECATION")
    roots += Environment.getExternalStorageDirectory().absolutePath

    runCatching {
        context.getExternalFilesDirs(null).filterNotNull().forEach { directory ->
            var current: File? = directory
            while (current != null && current.name != "Android") {
                current = current.parentFile
            }
            current?.parentFile?.let { roots += it.absolutePath }
        }
    }

    return roots.toList()
}

val Application.container: AppContainer
    get() = (this as MobileIdeApplication).container
