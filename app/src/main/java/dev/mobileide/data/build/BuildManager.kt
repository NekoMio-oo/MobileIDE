package dev.mobileide.data.build

import dev.mobileide.data.build.providers.UnsupportedBuildProvider
import dev.mobileide.data.runtime.LinuxRuntime
import dev.mobileide.domain.build.BuildActionResult
import dev.mobileide.domain.build.BuildArtifact
import dev.mobileide.domain.build.BuildConfig
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildLogLevel
import dev.mobileide.domain.build.BuildLogLine
import dev.mobileide.domain.build.BuildMode
import dev.mobileide.domain.build.BuildPlan
import dev.mobileide.domain.build.BuildProblem
import dev.mobileide.domain.build.BuildProblemSeverity
import dev.mobileide.domain.build.BuildProvider
import dev.mobileide.domain.build.BuildRepository
import dev.mobileide.domain.build.BuildSnapshot
import dev.mobileide.domain.build.BuildStandards
import dev.mobileide.domain.build.BuildState
import dev.mobileide.domain.build.BuildTask
import dev.mobileide.domain.build.DiagnosticDialect
import dev.mobileide.domain.build.GuestPaths
import dev.mobileide.domain.build.StartBuildResult
import dev.mobileide.domain.build.ToolchainInfo
import dev.mobileide.domain.build.ToolchainStatus
import dev.mobileide.domain.repository.ListProjectFilesResult
import dev.mobileide.domain.repository.ProjectFileError
import dev.mobileide.domain.repository.ProjectFileInfoResult
import dev.mobileide.domain.repository.ProjectRepository
import dev.mobileide.domain.runtime.ExecutionEvent
import dev.mobileide.domain.runtime.RuntimeExecutor
import dev.mobileide.domain.runtime.RuntimeRepository
import dev.mobileide.domain.runtime.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs builds inside the installed Linux environment.
 *
 * Policy: exactly one build at a time for the whole app. Starting another one
 * returns [StartBuildResult.Busy] instead of queuing, so two tasks can never
 * write the same output. All commands go through [LinuxRuntime] and
 * [RuntimeExecutor]; nothing here installs toolchains or spawns processes on its
 * own.
 */
class BuildManager(
    private val repository: ProjectRepository,
    private val runtimeRepository: RuntimeRepository,
    private val linux: LinuxRuntime,
    private val executor: RuntimeExecutor,
    private val configStore: BuildConfigStore,
    private val taskStore: BuildTaskStore,
    private val scanner: SourceScanner,
    private val providers: Map<BuildLanguage, BuildProvider>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val taskIdGenerator: () -> String = ::newTaskId,
    scope: CoroutineScope? = null
) : BuildRepository {

    private val buildScope: CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val snapshots = MutableStateFlow<Map<Long, BuildSnapshot>>(emptyMap())
    private val activeJob = AtomicReference<Job?>(null)
    private val activeProject = AtomicReference<Long?>(null)

    override fun observe(projectId: Long): StateFlow<BuildSnapshot> =
        snapshots
            .map { it[projectId] ?: BuildSnapshot(projectId = projectId, isLoading = false) }
            .stateIn(buildScope, SharingStarted.WhileSubscribed(5_000), snapshotOf(projectId))

    override suspend fun load(projectId: Long) {
        update(projectId) { it.copy(isLoading = true, error = null) }
        val project = runCatching { repository.getProject(projectId) }.getOrNull()
        if (project == null || project.path.isNullOrBlank()) {
            update(projectId) {
                it.copy(isLoading = false, error = "The project folder is not available")
            }
            return
        }
        val defaultLanguage = BuildLanguage.fromProjectType(project.type) ?: BuildLanguage.C
        val stored = runCatching { configStore.load(projectId) }.getOrNull()
        val config = stored ?: BuildConfig(
            language = defaultLanguage,
            standard = defaultStandard(defaultLanguage),
            outputName = defaultOutputName(project.name)
        )
        val history = loadHistory(projectId)
        val lastTask = history.firstOrNull()
        val running = snapshotOf(projectId).isBuilding
        update(projectId) {
            it.copy(
                isLoading = false,
                projectName = project.name,
                config = config,
                history = history,
                // A load triggered by the settings screen must not wipe the
                // output of a build that is currently running.
                task = if (running) it.task else lastTask,
                artifacts = if (running) it.artifacts else lastTask?.artifacts.orEmpty(),
                isBuilding = activeJob.get()?.isActive == true && activeProject.get() == projectId,
                runningProjectId = activeProject.get()
            )
        }
        refreshToolchain(projectId)
    }

    override suspend fun saveConfig(projectId: Long, config: BuildConfig): BuildActionResult {
        validateConfig(config)?.let { return BuildActionResult.Failure(it) }
        return when (val result = configStore.save(projectId, config)) {
            is dev.mobileide.domain.repository.SaveProjectDocumentResult.Success -> {
                update(projectId) { it.copy(config = config, message = "Build settings saved") }
                refreshToolchain(projectId)
                BuildActionResult.Success
            }

            is dev.mobileide.domain.repository.SaveProjectDocumentResult.Failure ->
                BuildActionResult.Failure(result.detail ?: "Could not save build settings")
        }
    }

    override suspend fun availableSources(
        projectId: Long,
        language: BuildLanguage
    ): List<String> = runCatching { scanner.scan(projectId, language) }.getOrDefault(emptyList())

    override suspend fun refreshToolchain(projectId: Long) {
        val config = snapshotOf(projectId).config
        val info = probeToolchain(config.language)
        update(projectId) { it.copy(toolchain = info) }
    }

    override suspend fun availableCompilers(
        projectId: Long,
        language: BuildLanguage
    ): List<String> = runCatching { probeToolchain(language).compilers }.getOrDefault(emptyList())

    override fun startBuild(projectId: Long): StartBuildResult {
        val running = activeJob.get()
        if (running != null && running.isActive) return StartBuildResult.Busy
        val config = snapshotOf(projectId).config
        val provider = providers[config.language]
            ?: return StartBuildResult.Failure("${config.language.displayName} is not supported")
        if (provider is UnsupportedBuildProvider) {
            return StartBuildResult.Failure(provider.validate(dummyContext(config)))
        }
        val job = buildScope.launch {
            try {
                runBuild(projectId)
            } finally {
                val self = coroutineContext[Job]
                if (activeJob.get() === self) {
                    activeJob.set(null)
                    activeProject.set(null)
                }
            }
        }
        activeJob.set(job)
        activeProject.set(projectId)
        if (!job.isActive) {
            activeJob.compareAndSet(job, null)
            activeProject.set(null)
        }
        update(projectId) {
            it.copy(isBuilding = true, runningProjectId = projectId, error = null, message = null)
        }
        return StartBuildResult.Started
    }

    override fun cancelBuild(projectId: Long) {
        if (activeProject.get() != projectId) return
        activeJob.get()?.cancel()
    }

    override fun clearLogs(projectId: Long) {
        update(projectId) { it.copy(logs = emptyList(), logTruncated = false) }
    }

    override fun clearMessage(projectId: Long) {
        update(projectId) { it.copy(message = null, error = null) }
    }

    override suspend fun deleteArtifact(projectId: Long, path: String): BuildActionResult {
        val sanitized = BuildPaths.sanitizeRelative(path)
            ?: return BuildActionResult.Failure("Invalid artifact path")
        if (!BuildPaths.isInsideBuild(sanitized)) {
            return BuildActionResult.Failure("Only files inside build/ can be deleted")
        }
        return when (val result = repository.deleteProjectEntry(projectId, sanitized)) {
            is dev.mobileide.domain.repository.DeleteProjectEntryResult.Success -> {
                update(projectId) { snapshot ->
                    snapshot.copy(
                        artifacts = snapshot.artifacts.filterNot { it.path == sanitized },
                        task = snapshot.task?.let { task ->
                            task.copy(artifacts = task.artifacts.filterNot { it.path == sanitized })
                        }
                    )
                }
                BuildActionResult.Success
            }

            is dev.mobileide.domain.repository.DeleteProjectEntryResult.Failure ->
                BuildActionResult.Failure(result.detail ?: "Could not delete the artifact")
        }
    }

    override suspend fun openTaskLog(projectId: Long, taskId: String): BuildActionResult {
        val task = taskStore.load(projectId, taskId)
            ?: return BuildActionResult.Failure("Build record not found")
        val text = taskStore.readLog(projectId, taskId).orEmpty()
        val collector = DiagnosticCollector(dialectOf(task.language))
        val lines = text.lineSequence()
            .filter { it.isNotEmpty() }
            .map { BuildLogLine(it, collector.levelOf(it)) }
            .toList()
        update(projectId) {
            it.copy(
                task = task,
                artifacts = task.artifacts,
                logs = lines.takeLast(MAX_UI_LOG_LINES),
                logTruncated = lines.size > MAX_UI_LOG_LINES,
                problems = emptyList()
            )
        }
        return BuildActionResult.Success
    }

    // ---- Build execution --------------------------------------------------

    private suspend fun runBuild(projectId: Long) {
        val project = runCatching { repository.getProject(projectId) }.getOrNull()
        val rootPath = project?.path
        if (project == null || rootPath.isNullOrBlank()) {
            update(projectId) {
                it.copy(isBuilding = false, runningProjectId = null, error = "Project folder is not available")
            }
            return
        }

        var config = snapshotOf(projectId).config
        val provider = providers[config.language]
        if (provider == null || provider is UnsupportedBuildProvider) {
            val message = provider?.validate(dummyContext(config))
                ?: "${config.language.displayName} is not supported"
            update(projectId) { it.copy(isBuilding = false, runningProjectId = null, error = message) }
            return
        }

        val taskId = taskIdGenerator()
        val startedAt = clock()
        var task = BuildTask(
            id = taskId,
            projectId = projectId,
            language = config.language,
            mode = config.mode,
            state = BuildState.PREPARING,
            startedAt = startedAt
        )
        val logs = mutableListOf<BuildLogLine>()
        var logTruncated = false
        var logBytesWritten = 0L
        var logFileFull = false
        var pendingLog = StringBuilder()
        var pendingBytes = 0
        var lastPublish = 0L
        var collector = DiagnosticCollector(DiagnosticDialect.NONE)

        taskStore.save(projectId, task)
        update(projectId) {
            it.copy(
                task = task,
                logs = emptyList(),
                problems = emptyList(),
                artifacts = emptyList(),
                isBuilding = true,
                runningProjectId = projectId,
                logTruncated = false,
                error = null,
                message = null
            )
        }

        suspend fun flushLog() {
            if (pendingLog.isEmpty()) return
            if (logBytesWritten < BuildPaths.MAX_LOG_BYTES) {
                taskStore.appendLog(projectId, taskId, pendingLog.toString())
                logBytesWritten += pendingBytes
            }
            pendingLog = StringBuilder()
            pendingBytes = 0
        }

        suspend fun publishLogs(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastPublish < PUBLISH_INTERVAL_MS) return
            lastPublish = now
            update(projectId) { it.copy(logs = logs.toList(), logTruncated = logTruncated) }
        }

        suspend fun log(text: String, level: BuildLogLevel = BuildLogLevel.INFO) {
            logs += BuildLogLine(text, level)
            if (logs.size > MAX_UI_LOG_LINES * 2) {
                logs.subList(0, MAX_UI_LOG_LINES).clear()
                logTruncated = true
            }
            if (!logFileFull) {
                val chunk = text + "\n"
                val bytes = chunk.toByteArray(Charsets.UTF_8).size
                if (logBytesWritten + pendingBytes + bytes > BuildPaths.MAX_LOG_BYTES) {
                    logFileFull = true
                    pendingLog.append("[log truncated at 2 MB]\n")
                } else {
                    pendingLog.append(chunk)
                    pendingBytes += bytes
                    if (pendingBytes >= LOG_FLUSH_BYTES) flushLog()
                }
            }
            publishLogs()
        }

        suspend fun finish(
            state: BuildState,
            message: String?,
            exitCode: Int?,
            outputPath: String?,
            artifacts: List<BuildArtifact>
        ) {
            withContext(NonCancellable) {
                if (state != BuildState.SUCCEEDED) {
                    publishLogs(force = true)
                }
                flushLog()
                val finishedAt = clock()
                task = task.copy(
                    state = state,
                    finishedAt = finishedAt,
                    durationMs = (finishedAt - startedAt).coerceAtLeast(0),
                    exitCode = exitCode,
                    errorCount = collector.problems.count {
                        it.severity == BuildProblemSeverity.ERROR
                    },
                    warningCount = collector.problems.count {
                        it.severity == BuildProblemSeverity.WARNING
                    },
                    artifacts = artifacts,
                    outputPath = outputPath,
                    failureMessage = message
                )
                taskStore.save(projectId, task)
                val history = runCatching { taskStore.history(projectId) }.getOrDefault(emptyList())
                update(projectId) {
                    it.copy(
                        task = task,
                        history = history,
                        artifacts = artifacts,
                        problems = collector.problems.toList(),
                        logs = logs.toList(),
                        logTruncated = logTruncated,
                        isBuilding = false,
                        runningProjectId = null,
                        error = if (state == BuildState.FAILED) message else null
                    )
                }
            }
        }

        try {
            log("MobileIDE build $taskId", BuildLogLevel.INFO)
            log("Project: ${project.name} (${project.path})")
            log("Language: ${config.language.displayName}   Mode: ${config.mode.displayName}")
            log("")

            val linuxInfo = probeToolchain(config.language)
            update(projectId) { it.copy(toolchain = linuxInfo) }
            if (linuxInfo.status != ToolchainStatus.READY) {
                val message = linuxInfo.message ?: "Toolchain is not available"
                log(message, BuildLogLevel.ERROR)
                finish(BuildState.FAILED, message, null, null, emptyList())
                return
            }
            log("Toolchain: ${linuxInfo.compilers.joinToString(", ")} ${linuxInfo.version.orEmpty()}".trim())

            val compiler = config.compiler.takeIf { it in linuxInfo.compilers }
                ?: linuxInfo.compilers.first()
            if (compiler != config.compiler) {
                config = config.copy(compiler = compiler)
                update(projectId) { it.copy(config = config) }
            }

            val allSources = scanner.scan(projectId, config.language)
            val selected = if (config.sourcesAuto || config.sources.isEmpty()) {
                allSources
            } else {
                config.sources.filter { it in allSources }
            }
            if (selected.isEmpty()) {
                val message = "No ${config.language.displayName} source files found"
                log(message, BuildLogLevel.ERROR)
                finish(BuildState.FAILED, message, null, null, emptyList())
                return
            }
            if (!config.sourcesAuto && selected.size < config.sources.size) {
                log("Some configured sources no longer exist and were skipped.")
            }

            val rootFiles = rootFileNames(projectId)
            val outputDir = BuildPaths.outputDir(config.mode)
            ensureDirectory(projectId, BuildPaths.BUILD_DIR)
            ensureDirectory(projectId, outputDir)
            if (config.language == BuildLanguage.JAVA) ensureDirectory(projectId, BuildPaths.CLASSES_DIR)
            linux.ensureGuestDir(GuestPaths.ROOT.trimStart('/'))

            val context = dev.mobileide.domain.build.BuildContext(
                config = config,
                sources = selected,
                outputPath = "$outputDir/${config.outputName}",
                rootFiles = rootFiles
            )
            provider.validate(context)?.let { message ->
                log(message, BuildLogLevel.ERROR)
                finish(BuildState.FAILED, message, null, null, emptyList())
                return
            }

            val plan: BuildPlan = provider.plan(context)
            collector = DiagnosticCollector(plan.dialect)
            if (plan.steps.isEmpty()) {
                val message = "No build command was generated"
                log(message, BuildLogLevel.ERROR)
                finish(BuildState.FAILED, message, null, null, emptyList())
                return
            }

            log("Sources (${selected.size}): ${selected.joinToString(", ")}")
            log("")

            var exitCode = 0
            var failure: String? = null
            for ((index, step) in plan.steps.withIndex()) {
                task = task.copy(state = BuildState.COMPILING)
                update(projectId) { it.copy(task = task) }
                log("[${index + 1}/${plan.steps.size}] ${step.title}", BuildLogLevel.COMMAND)
                log("$ ${renderCommand(step.guestCommand)}", BuildLogLevel.COMMAND)

                val request = linux.buildExecution(
                    guestCommand = step.guestCommand,
                    guestWorkDir = GuestPaths.ROOT,
                    bindPaths = listOf("$rootPath:${GuestPaths.ROOT}"),
                    timeoutMs = BUILD_TIMEOUT_MS,
                    extraEnv = step.environment
                )
                if (request == null) {
                    failure = "Linux environment is not installed"
                    log(failure, BuildLogLevel.ERROR)
                    break
                }

                var exit: ExecutionEvent.Exit? = null
                executor.execute(request).collect { event ->
                    when (event) {
                        is ExecutionEvent.Output -> {
                            collector.onLine(event.line)
                            log(event.line, collector.levelOf(event.line))
                        }

                        is ExecutionEvent.Exit -> exit = event
                    }
                }
                publishLogs(force = true)

                val result = exit
                if (result == null) {
                    failure = "The compiler process ended unexpectedly"
                    log(failure, BuildLogLevel.ERROR)
                    break
                }
                exitCode = result.code
                if (result.timedOut) {
                    failure = "Build timed out after ${BUILD_TIMEOUT_MS / 60_000} minutes"
                    log(failure, BuildLogLevel.ERROR)
                    break
                }
                if (result.code != 0) {
                    failure = "Build failed with exit code ${result.code}"
                    log(failure, BuildLogLevel.ERROR)
                    break
                }
                log("")
            }

            if (failure != null) {
                finish(BuildState.FAILED, failure, exitCode, null, emptyList())
                return
            }

            val artifacts = collectArtifacts(projectId, plan.artifactCandidates)
            val outputPath = artifacts.firstOrNull { it.path == context.outputPath }?.path
                ?: artifacts.firstOrNull()?.path
            val finishedAt = clock()
            log(
                "Build succeeded in ${formatDuration(finishedAt - startedAt)}" +
                    if (artifacts.isEmpty()) "" else ", ${artifacts.size} artifact(s)",
                BuildLogLevel.INFO
            )
            artifacts.forEach { artifact -> log("  ${artifact.path} (${artifact.sizeBytes} bytes)") }
            finish(BuildState.SUCCEEDED, null, 0, outputPath, artifacts)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                log("Build cancelled", BuildLogLevel.WARNING)
                finish(BuildState.CANCELLED, "Cancelled", null, null, emptyList())
            }
            throw cancel
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            log(message, BuildLogLevel.ERROR)
            finish(BuildState.FAILED, message, null, null, emptyList())
        }
    }

    // ---- Helpers ----------------------------------------------------------

    private suspend fun probeToolchain(language: BuildLanguage): ToolchainInfo {
        providers[language]?.let { provider ->
            if (provider is UnsupportedBuildProvider) {
                return ToolchainInfo(
                    status = ToolchainStatus.UNSUPPORTED,
                    language = language,
                    message = provider.validate(dummyContext(BuildConfig(language = language)))
                )
            }
        }
        val runtimeId = language.runtimeId
        val runtimeInfo = runtimeRepository.state.value.runtimes.firstOrNull { it.id == runtimeId }
        if (!linux.isInstalled()) {
            return ToolchainInfo(
                status = ToolchainStatus.LINUX_MISSING,
                language = language,
                message = "Install the Linux environment in Runtimes first."
            )
        }
        if (runtimeInfo != null && runtimeInfo.state == RuntimeState.UNSUPPORTED) {
            return ToolchainInfo(
                status = ToolchainStatus.UNSUPPORTED,
                language = language,
                message = runtimeInfo.message
            )
        }

        val candidates = compilerCandidates(language)
        if (candidates.isEmpty()) {
            return ToolchainInfo(
                status = ToolchainStatus.TOOLCHAIN_MISSING,
                language = language,
                message = "No compiler is known for ${language.displayName}"
            )
        }
        val script = candidates.joinToString("; ") { tool ->
            "if command -v $tool >/dev/null 2>&1; then " +
                "echo \"OK $tool \$($tool --version 2>&1 | head -n 1)\"; fi"
        }
        val request = linux.buildExecution(
            guestCommand = listOf("/bin/sh", "-lc", script),
            timeoutMs = PROBE_TIMEOUT_MS
        ) ?: return ToolchainInfo(
            status = ToolchainStatus.LINUX_MISSING,
            language = language,
            message = "Install the Linux environment in Runtimes first."
        )

        val found = mutableListOf<String>()
        var version: String? = null
        var lastLine: String? = null
        executor.execute(request).collect { event ->
            if (event is ExecutionEvent.Output) {
                val line = event.line.trim()
                if (line.isNotEmpty()) lastLine = line
                if (line.startsWith("OK ")) {
                    val parts = line.split(' ', limit = 3)
                    if (parts.size >= 2) {
                        found += parts[1]
                        if (version == null && parts.size == 3) version = parts[2].take(120)
                    }
                }
            }
        }
        return if (found.isEmpty()) {
            ToolchainInfo(
                status = ToolchainStatus.TOOLCHAIN_MISSING,
                language = language,
                message = lastLine?.take(200)
                    ?: "No ${language.displayName} compiler is installed inside the Linux environment."
            )
        } else {
            ToolchainInfo(
                status = ToolchainStatus.READY,
                language = language,
                compilers = found.distinct(),
                version = version
            )
        }
    }

    private fun compilerCandidates(language: BuildLanguage): List<String> = when (language) {
        BuildLanguage.C -> listOf("clang", "gcc")
        BuildLanguage.CPP -> listOf("clang++", "g++")
        BuildLanguage.JAVA -> listOf("javac")
        BuildLanguage.GO -> listOf("go")
        BuildLanguage.RUST -> listOf("cargo")
        BuildLanguage.TYPESCRIPT -> listOf("tsc")
        else -> emptyList()
    }

    private suspend fun collectArtifacts(
        projectId: Long,
        candidates: List<String>
    ): List<BuildArtifact> {
        val result = mutableListOf<BuildArtifact>()
        for (candidate in candidates) {
            val info = repository.getProjectFileInfo(projectId, candidate)
            val entry = (info as? ProjectFileInfoResult.Success)?.entry ?: continue
            if (entry.isDirectory) {
                expandArtifactDirectory(projectId, entry.path, result, depth = 0)
            } else {
                result += BuildArtifact(
                    path = entry.path,
                    name = entry.name,
                    sizeBytes = entry.size,
                    modifiedAt = entry.lastModified
                )
            }
            if (result.size >= MAX_ARTIFACTS) break
        }
        return result.distinctBy { it.path }.take(MAX_ARTIFACTS)
    }

    private suspend fun expandArtifactDirectory(
        projectId: Long,
        path: String,
        target: MutableList<BuildArtifact>,
        depth: Int
    ) {
        if (depth > 3 || target.size >= MAX_ARTIFACTS) return
        val listing = repository.listProjectFiles(projectId, path)
        val entries = (listing as? ListProjectFilesResult.Success)?.entries ?: return
        for (entry in entries) {
            if (target.size >= MAX_ARTIFACTS) return
            if (entry.isDirectory) {
                if (entry.name in SKIPPED_ARTIFACT_DIRS) continue
                expandArtifactDirectory(projectId, entry.path, target, depth + 1)
            } else {
                val extension = entry.name.substringAfterLast('.', "").lowercase()
                if (extension in SKIPPED_ARTIFACT_EXTENSIONS) continue
                if (entry.size <= 0L) continue
                target += BuildArtifact(
                    path = entry.path,
                    name = entry.name,
                    sizeBytes = entry.size,
                    modifiedAt = entry.lastModified
                )
            }
        }
    }

    private suspend fun rootFileNames(projectId: Long): Set<String> {
        val listing = repository.listProjectFiles(projectId, "")
        val entries = (listing as? ListProjectFilesResult.Success)?.entries ?: return emptySet()
        return entries.asSequence().filter { !it.isDirectory }.map { it.name }.toSet()
    }

    private suspend fun ensureDirectory(projectId: Long, path: String) {
        var current = ""
        for (part in path.trim('/').split('/')) {
            if (part.isBlank()) continue
            runCatching {
                repository.createProjectEntry(
                    projectId = projectId,
                    directoryPath = current,
                    name = part,
                    isDirectory = true
                )
            }
            current = if (current.isEmpty()) part else "$current/$part"
        }
    }

    private suspend fun loadHistory(projectId: Long): List<BuildTask> {
        val history = runCatching { taskStore.history(projectId) }.getOrDefault(emptyList())
        return history.map { task ->
            if (!task.state.isRunning) return@map task
            val now = clock()
            val interrupted = task.copy(
                state = BuildState.INTERRUPTED,
                finishedAt = now,
                durationMs = (now - task.startedAt).coerceAtLeast(0),
                failureMessage = "The app stopped while this build was running"
            )
            taskStore.save(projectId, interrupted)
            interrupted
        }
    }

    private fun validateConfig(config: BuildConfig): String? {
        if (config.outputName.isBlank()) return "Output name is empty"
        if (config.outputName.contains('/') || config.outputName.contains('\\')) {
            return "Output name must not contain a path separator"
        }
        val paths = config.sources + config.includeDirs + config.libraryDirs +
            config.classpath + listOfNotNull(config.entryFile)
        paths.forEach { path ->
            if (BuildPaths.sanitizeRelative(path) == null) {
                return "Path must stay inside the project: $path"
            }
        }
        if (config.libraries.any { it.isBlank() }) return "Library name is empty"
        return null
    }

    private fun snapshotOf(projectId: Long): BuildSnapshot =
        snapshots.value[projectId]
            ?: BuildSnapshot(projectId = projectId, isLoading = false)

    private fun update(projectId: Long, transform: (BuildSnapshot) -> BuildSnapshot) {
        snapshots.update { current ->
            val snapshot = current[projectId]
                ?: BuildSnapshot(projectId = projectId, isLoading = false)
            current + (projectId to transform(snapshot))
        }
    }

    private fun dummyContext(config: BuildConfig) = dev.mobileide.domain.build.BuildContext(
        config = config,
        sources = emptyList(),
        outputPath = "${BuildPaths.outputDir(config.mode)}/${config.outputName}"
    )

    private fun dialectOf(language: BuildLanguage): DiagnosticDialect = when (language) {
        BuildLanguage.C, BuildLanguage.CPP -> DiagnosticDialect.CC
        BuildLanguage.JAVA -> DiagnosticDialect.JAVA
        BuildLanguage.GO -> DiagnosticDialect.GO
        BuildLanguage.RUST -> DiagnosticDialect.RUST
        BuildLanguage.TYPESCRIPT -> DiagnosticDialect.TYPESCRIPT
        else -> DiagnosticDialect.NONE
    }

    private fun defaultStandard(language: BuildLanguage): String =
        BuildStandards.defaultFor(language)

    private fun defaultOutputName(projectName: String): String {
        val sanitized = projectName.map { character ->
            if (character.isLetterOrDigit() || character in "._-") character else '_'
        }.joinToString("").trim('_', '.')
        return sanitized.take(48).ifBlank { "app" }
    }

    private fun renderCommand(command: List<String>): String =
        command.joinToString(" ") { argument ->
            if (argument.any { it.isWhitespace() || it == '"' || it == '\'' }) {
                "'" + argument.replace("'", "'\\''") + "'"
            } else {
                argument
            }
        }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000.0
        return if (seconds < 60) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            val minutes = (seconds / 60).toInt()
            val rest = (seconds - minutes * 60).toInt()
            "${minutes}m ${rest}s"
        }
    }

    private companion object {
        const val BUILD_TIMEOUT_MS = 15 * 60 * 1000L
        const val PROBE_TIMEOUT_MS = 30_000L
        const val MAX_UI_LOG_LINES = 2_000
        const val MAX_ARTIFACTS = 40
        const val LOG_FLUSH_BYTES = 64 * 1024
        const val PUBLISH_INTERVAL_MS = 100L

        val SKIPPED_ARTIFACT_DIRS = setOf(
            "deps", "build", "incremental", ".fingerprint", "obj", ".git"
        )
        val SKIPPED_ARTIFACT_EXTENSIONS = setOf(
            "d", "o", "obj", "rlib", "rmeta", "pdb", "exp", "lib", "map", "tmp"
        )

        fun newTaskId(): String {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val suffix = (System.nanoTime() % 1000).toString().padStart(3, '0')
            return "$stamp-$suffix"
        }
    }
}
