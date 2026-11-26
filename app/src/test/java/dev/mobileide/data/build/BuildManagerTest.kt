package dev.mobileide.data.build

import dev.mobileide.core.filesystem.NativeFileSystemImpl
import dev.mobileide.core.storage.StorageAccess
import dev.mobileide.data.build.providers.CcBuildProvider
import dev.mobileide.data.build.providers.GoBuildProvider
import dev.mobileide.data.build.providers.JavaBuildProvider
import dev.mobileide.data.build.providers.RustBuildProvider
import dev.mobileide.data.build.providers.TypeScriptBuildProvider
import dev.mobileide.data.build.providers.UnsupportedBuildProvider
import dev.mobileide.data.local.ProjectDao
import dev.mobileide.data.local.ProjectEntity
import dev.mobileide.data.repository.ProjectRepositoryImpl
import dev.mobileide.data.runtime.LinuxRuntime
import dev.mobileide.data.runtime.RuntimePaths
import dev.mobileide.domain.build.BuildConfig
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildMode
import dev.mobileide.domain.build.BuildProvider
import dev.mobileide.domain.build.BuildRepository
import dev.mobileide.domain.build.BuildState
import dev.mobileide.domain.build.StartBuildResult
import dev.mobileide.domain.build.ToolchainStatus
import dev.mobileide.domain.model.ProjectType
import dev.mobileide.domain.runtime.ExecutionEvent
import dev.mobileide.domain.runtime.ExecutionRequest
import dev.mobileide.domain.runtime.RuntimeActionResult
import dev.mobileide.domain.runtime.RuntimeExecutor
import dev.mobileide.domain.runtime.RuntimeId
import dev.mobileide.domain.runtime.RuntimeRepository
import dev.mobileide.domain.runtime.RuntimeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end build pipeline over a real temporary project folder: real path
 * checks, real configuration and log files, a scripted executor standing in for
 * PRoot (there is no Linux environment in a JVM test).
 */
class BuildManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var storageRoot: File
    private lateinit var projectDir: File
    private lateinit var fileSystem: NativeFileSystemImpl
    private lateinit var dao: TestProjectDao
    private var projectId: Long = 0L

    @Before
    fun setUp() {
        storageRoot = temporaryFolder.newFolder("storage")
        projectDir = File(storageRoot, "demo").apply { mkdirs() }
        File(projectDir, "main.c").writeText("int main(void) { return 0; }\n")
        File(projectDir, "util.c").writeText("int helper(void) { return 1; }\n")

        fileSystem = NativeFileSystemImpl(listOf(storageRoot.absolutePath))
        dao = TestProjectDao()
        val entity = dao.seed(path = projectDir.absolutePath, type = ProjectType.C)
        projectId = entity.id
    }

    @Test
    fun `successful build records diagnostics and artifacts`() = runBlocking {
        val executor = ScriptedExecutor { request ->
            if (isProbe(request)) {
                listOf(
                    ExecutionEvent.Output(false, "OK gcc gcc (Debian 12.2.0) 12.2.0"),
                    ExecutionEvent.Exit(0, false)
                )
            } else {
                File(projectDir, "build/debug/demo").apply { parentFile?.mkdirs() }
                    .writeText("binary")
                listOf(
                    ExecutionEvent.Output(false, "main.c:2:5: warning: unused parameter 'argc'"),
                    ExecutionEvent.Output(false, "gcc -o app main.c util.c"),
                    ExecutionEvent.Exit(0, false)
                )
            }
        }
        val manager = manager(executor)

        manager.load(projectId)
        assertEquals(ToolchainStatus.READY, snapshot(manager).toolchain.status)
        assertEquals(listOf("gcc"), snapshot(manager).toolchain.compilers)

        assertEquals(StartBuildResult.Started, manager.startBuild(projectId))
        awaitBuild(manager)

        val result = snapshot(manager)
        assertEquals(BuildState.SUCCEEDED, result.task?.state)
        assertEquals(1, result.task?.warningCount)
        assertEquals(0, result.task?.errorCount)
        assertEquals(listOf("build/debug/demo"), result.task?.artifacts?.map { it.path })
        assertEquals("build/debug/demo", result.task?.outputPath)
        assertEquals(1, result.history.size)
        assertFalse(result.isBuilding)
        // The compiler was resolved from the probe, not from the default config.
        assertEquals("gcc", result.config.compiler)

        val log = File(projectDir, ".mobileide/logs/t1.log")
        assertTrue(log.isFile)
        assertTrue(log.readText().contains("warning: unused parameter"))
    }

    @Test
    fun `failed compilation keeps the log and reports errors`() = runBlocking {
        val executor = ScriptedExecutor { request ->
            if (isProbe(request)) {
                listOf(
                    ExecutionEvent.Output(false, "OK clang clang version 14.0.6"),
                    ExecutionEvent.Exit(0, false)
                )
            } else {
                listOf(
                    ExecutionEvent.Output(false, "main.c:3:5: error: expected ';'"),
                    ExecutionEvent.Exit(1, false)
                )
            }
        }
        val manager = manager(executor)
        manager.load(projectId)

        manager.startBuild(projectId)
        awaitBuild(manager)

        val result = snapshot(manager)
        assertEquals(BuildState.FAILED, result.task?.state)
        assertEquals(1, result.task?.errorCount)
        assertEquals(1, result.task?.exitCode)
        assertTrue(result.task?.artifacts.isNullOrEmpty())
        assertNotNull(result.error)
        assertTrue(result.problems.any { it.path == "main.c" && it.line == 3 })
        assertTrue(File(projectDir, ".mobileide/logs/t1.log").readText().contains("expected ';'"))
    }

    @Test
    fun `build without sources fails with a clear message`() = runBlocking {
        File(projectDir, "main.c").delete()
        File(projectDir, "util.c").delete()
        val manager = manager(ScriptedExecutor { request ->
            if (isProbe(request)) {
                listOf(ExecutionEvent.Output(false, "OK gcc gcc 12"), ExecutionEvent.Exit(0, false))
            } else {
                listOf(ExecutionEvent.Exit(0, false))
            }
        })
        manager.load(projectId)

        manager.startBuild(projectId)
        awaitBuild(manager)

        val result = snapshot(manager)
        assertEquals(BuildState.FAILED, result.task?.state)
        assertTrue(result.error!!.contains("No C source files"))
    }

    @Test
    fun `unsupported language cannot start a build`() = runBlocking {
        val manager = manager(ScriptedExecutor { emptyList() })
        manager.load(projectId)

        val saved = manager.saveConfig(
            projectId,
            BuildConfig(language = BuildLanguage.KOTLIN, outputName = "app")
        )
        assertTrue(saved is dev.mobileide.domain.build.BuildActionResult.Success)

        val result = manager.startBuild(projectId)
        assertTrue(result is StartBuildResult.Failure)
        assertTrue((result as StartBuildResult.Failure).message.contains("Kotlin"))
        assertEquals(ToolchainStatus.UNSUPPORTED, snapshot(manager).toolchain.status)
    }

    @Test
    fun `artifacts can only be deleted inside the build folder`() = runBlocking {
        File(projectDir, "build/debug").mkdirs()
        val artifact = File(projectDir, "build/debug/app").apply { writeText("binary") }
        val source = File(projectDir, "main.c")
        val manager = manager(ScriptedExecutor { emptyList() })

        val refused = manager.deleteArtifact(projectId, "main.c")
        assertTrue(refused is dev.mobileide.domain.build.BuildActionResult.Failure)
        assertTrue(source.exists())

        assertTrue(
            manager.deleteArtifact(projectId, "build/debug/app") is
                dev.mobileide.domain.build.BuildActionResult.Success
        )
        assertFalse(artifact.exists())
    }

    @Test
    fun `a task left running by a killed app is marked interrupted`() = runBlocking {
        val taskDir = File(projectDir, ".mobileide/tasks").apply { mkdirs() }
        val stale = dev.mobileide.domain.build.BuildTask(
            id = "stale",
            projectId = projectId,
            language = BuildLanguage.C,
            mode = BuildMode.DEBUG,
            state = BuildState.COMPILING,
            startedAt = NOW - 5_000L
        )
        File(taskDir, "stale.properties").writeText(BuildTaskCodec.encode(stale))

        val manager = manager(ScriptedExecutor { request ->
            if (isProbe(request)) {
                listOf(ExecutionEvent.Output(false, "OK gcc gcc 12"), ExecutionEvent.Exit(0, false))
            } else {
                listOf(ExecutionEvent.Exit(0, false))
            }
        })
        manager.load(projectId)

        val history = snapshot(manager).history
        assertEquals(1, history.size)
        assertEquals(BuildState.INTERRUPTED, history.first().state)
        assertNotNull(history.first().failureMessage)
    }

    private suspend fun awaitBuild(manager: BuildManager) {
        withTimeout(20_000) {
            while (manager.snapshots.value[projectId]?.isBuilding != false) {
                delay(10)
            }
        }
    }

    private fun snapshot(manager: BuildManager) =
        manager.snapshots.value[projectId] ?: error("no snapshot for project")

    private fun isProbe(request: ExecutionRequest): Boolean =
        request.command.any { it.contains("command -v") }

    private fun manager(executor: RuntimeExecutor): BuildManager {
        val repository = ProjectRepositoryImpl(
            projectDao = dao,
            fileSystem = fileSystem,
            storageAccess = StorageAccess { true },
            ioDispatcher = Dispatchers.IO,
            clock = { NOW }
        )
        val runtimePaths = RuntimePaths(
            File(temporaryFolder.root, "files"),
            File(temporaryFolder.root, "cache")
        )
        listOf(
            runtimePaths.prootBinary,
            runtimePaths.prootLoader,
            runtimePaths.rootfsShell
        ).forEach { file ->
            file.parentFile?.mkdirs()
            file.writeText("")
        }

        val providers: Map<BuildLanguage, BuildProvider> = mapOf(
            BuildLanguage.C to CcBuildProvider(BuildLanguage.C),
            BuildLanguage.CPP to CcBuildProvider(BuildLanguage.CPP),
            BuildLanguage.JAVA to JavaBuildProvider(),
            BuildLanguage.GO to GoBuildProvider(),
            BuildLanguage.RUST to RustBuildProvider(),
            BuildLanguage.TYPESCRIPT to TypeScriptBuildProvider(),
            BuildLanguage.KOTLIN to UnsupportedBuildProvider(
                BuildLanguage.KOTLIN,
                UnsupportedBuildProvider.KOTLIN_REASON
            )
        )

        return BuildManager(
            repository = repository,
            runtimeRepository = FakeRuntimeRepository(),
            linux = LinuxRuntime(runtimePaths),
            executor = executor,
            configStore = BuildConfigStore(repository),
            taskStore = BuildTaskStore(repository),
            scanner = SourceScanner(repository),
            providers = providers,
            clock = { NOW },
            taskIdGenerator = { "t1" }
        )
    }

    private companion object {
        const val NOW = 1_900_000_000_000L
    }
}

/** Emits scripted events instead of starting a real PRoot process. */
private class ScriptedExecutor(
    private val script: (ExecutionRequest) -> List<ExecutionEvent>
) : RuntimeExecutor {

    override fun execute(request: ExecutionRequest): Flow<ExecutionEvent> = flow {
        script(request).forEach { event -> emit(event) }
    }
}

private class FakeRuntimeRepository : RuntimeRepository {
    private val _state = MutableStateFlow(RuntimeSnapshot(isLoading = false))
    override val state: StateFlow<RuntimeSnapshot> = _state

    override suspend fun refresh() = Unit

    override suspend fun installLinux(): RuntimeActionResult = RuntimeActionResult.Success

    override suspend fun uninstallLinux(): RuntimeActionResult = RuntimeActionResult.Success

    override suspend fun verifyLinux(): RuntimeActionResult = RuntimeActionResult.Success

    override suspend fun installToolchain(id: RuntimeId): RuntimeActionResult =
        RuntimeActionResult.Success

    override suspend fun checkToolchain(id: RuntimeId): RuntimeActionResult =
        RuntimeActionResult.Success

    override fun clearMessage() = Unit

    override fun clearError() = Unit

    override fun clearLogs() = Unit
}

private class TestProjectDao : ProjectDao {

    private val projects = MutableStateFlow<List<ProjectEntity>>(emptyList())
    private var nextId = 1L

    fun seed(path: String, type: ProjectType): ProjectEntity {
        val entity = ProjectEntity(
            id = nextId++,
            name = "demo",
            type = type.name,
            path = path,
            legacyUri = null,
            locationName = "",
            createdAt = 0L,
            lastOpenedAt = 0L,
            updatedAt = 0L
        )
        projects.update { it + entity }
        return entity
    }

    override fun observeAll(): Flow<List<ProjectEntity>> = projects

    override fun observeRecent(limit: Int): Flow<List<ProjectEntity>> =
        projects.map { list -> list.take(limit) }

    override suspend fun findById(projectId: Long): ProjectEntity? =
        projects.value.firstOrNull { it.id == projectId }

    override suspend fun findByPath(path: String): ProjectEntity? =
        projects.value.firstOrNull { it.path == path }

    override suspend fun getAll(): List<ProjectEntity> = projects.value

    override suspend fun insert(project: ProjectEntity): Long {
        val id = nextId++
        projects.update { it + project.copy(id = id) }
        return id
    }

    override suspend fun updateNameAndPath(
        projectId: Long,
        name: String,
        path: String,
        updatedAt: Long
    ) = Unit

    override suspend fun updatePath(
        projectId: Long,
        path: String,
        locationName: String,
        updatedAt: Long
    ) = Unit

    override suspend fun touch(projectId: Long, openedAt: Long) = Unit

    override suspend fun deleteById(projectId: Long) {
        projects.update { list -> list.filterNot { it.id == projectId } }
    }
}
