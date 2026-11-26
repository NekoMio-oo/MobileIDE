package dev.mobileide.data.git

import dev.mobileide.core.filesystem.NativeFileSystemImpl
import dev.mobileide.core.storage.StorageAccess
import dev.mobileide.data.local.ProjectDao
import dev.mobileide.data.local.ProjectEntity
import dev.mobileide.data.repository.ProjectRepositoryImpl
import dev.mobileide.domain.git.GitAvailability
import dev.mobileide.domain.git.GitChangeKind
import dev.mobileide.domain.git.GitInstallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Real Git integration over a temporary project: detection, init and status run
 * the host `git` binary (the PRoot wrapper is device-only). This catches parser
 * and argument mistakes that canned output would miss.
 */
class GitRepositoryImplTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var projectDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var repository: GitRepositoryImpl
    private var projectId: Long = 0L

    @Before
    fun setUp() {
        assumeGit()
        val storageRoot = temporaryFolder.newFolder("storage")
        projectDir = File(storageRoot, "demo").apply { mkdirs() }
        File(projectDir, "README.md").writeText("# demo\n")

        val fileSystem = NativeFileSystemImpl(listOf(storageRoot.absolutePath))
        val dao = TestProjectDao()
        projectId = dao.seed(projectDir.absolutePath).id
        val projects = ProjectRepositoryImpl(
            projectDao = dao,
            fileSystem = fileSystem,
            storageAccess = StorageAccess { true },
            ioDispatcher = Dispatchers.IO
        )

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = GitRepositoryImpl(
            projects = projects,
            runner = LocalGitRunner(),
            installer = FakeGitInstaller(),
            linuxInstalled = { true },
            scope = scope
        )
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
    }

    @Test
    fun `detects a non repository and initializes a real one`() = runBlocking {
        repository.refresh(projectId)
        val notRepo = repository.observe(projectId).value
        assertEquals(GitAvailability.NOT_A_REPO, notRepo.availability)
        assertFalse(notRepo.hasGitDir)
        assertNotNull(notRepo.gitVersion)

        initializeRepository()

        val ready = repository.observe(projectId).value
        assertTrue(File(projectDir, ".git").isDirectory)
        assertEquals(GitAvailability.READY, ready.availability)
        assertEquals("main", ready.repo?.branch)
        assertTrue(ready.hasGitDir)
        assertEquals(listOf("README.md"), ready.status?.untracked?.map { it.path })
    }

    @Test
    fun `isRepository uses the real git marker`() = runBlocking {
        assertFalse(repository.isRepository(projectId))
        initializeRepository()
        assertTrue(repository.isRepository(projectId))
    }

    @Test
    fun `reports untracked and modified files from a real worktree`() = runBlocking {
        initializeRepository()
        val file = File(projectDir, "src/Main.kt").apply {
            parentFile?.mkdirs()
            writeText("fun main() {}\n")
        }

        repository.refresh(projectId)
        var status = repository.observe(projectId).value.status!!
        assertTrue(status.untracked.any { it.path == "src/Main.kt" })

        val runner = LocalGitRunner()
        assertTrue(
            runner.run(
                projectPath = projectDir.absolutePath,
                args = listOf("add", "README.md", "src/Main.kt"),
                options = GitRunOptions(readOnly = false)
            ).isSuccess
        )
        assertTrue(
            runner.run(
                projectPath = projectDir.absolutePath,
                args = listOf(
                    "-c", "user.name=MobileIDE Test",
                    "-c", "user.email=test@example.com",
                    "commit", "-m", "initial"
                ),
                options = GitRunOptions(readOnly = false)
            ).isSuccess
        )

        file.writeText("fun main() { println(\"hi\") }\n")
        repository.refresh(projectId)
        status = repository.observe(projectId).value.status!!
        val change = status.unstaged.single { it.path == "src/Main.kt" }
        assertEquals(GitChangeKind.MODIFIED, change.workTreeChange)
        assertTrue(status.untracked.isEmpty())
    }

    private suspend fun initializeRepository() {
        repository.initRepository(projectId, "main")
        withTimeout(20_000) {
            repository.observe(projectId).first {
                it.availability == GitAvailability.READY && !it.isBusy
            }
        }
    }

    private fun assumeGit() {
        val installed = runCatching {
            ProcessBuilder("git", "--version").start().waitFor() == 0
        }.getOrDefault(false)
        Assume.assumeTrue("git is not installed on the test host", installed)
    }
}

/** Runs the host git binary with the project as the working directory. */
private class LocalGitRunner : GitCommandRunner {

    override suspend fun run(
        projectPath: String?,
        args: List<String>,
        options: GitRunOptions
    ): GitCommandResult = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(listOf("git") + args)
        builder.directory(
            projectPath?.let(::File) ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        )
        builder.environment().apply {
            put("GIT_TERMINAL_PROMPT", "0")
            put("LC_ALL", "C")
            put("GIT_OPTIONAL_LOCKS", if (options.readOnly) "0" else "1")
            putAll(options.extraEnv)
        }

        val process = builder.start()
        var stdout = ByteArray(0)
        var stderr = ByteArray(0)
        val stdoutThread = Thread { stdout = process.inputStream.readBytes() }
        val stderrThread = Thread { stderr = process.errorStream.readBytes() }
        stdoutThread.start()
        stderrThread.start()
        val exit = process.waitFor()
        stdoutThread.join()
        stderrThread.join()

        GitCommandResult(
            exitCode = exit,
            stdout = stdout.toString(Charsets.UTF_8),
            stderr = stderr.toString(Charsets.UTF_8)
        )
    }
}

private class FakeGitInstaller : GitInstaller {
    override suspend fun install(onProgress: (String) -> Unit): GitInstallResult {
        onProgress("fake install")
        return GitInstallResult(success = true, message = "Git installed")
    }
}

private class TestProjectDao : ProjectDao {

    private val projects = MutableStateFlow<List<ProjectEntity>>(emptyList())
    private var nextId = 1L

    fun seed(path: String): ProjectEntity {
        val entity = ProjectEntity(
            id = nextId++,
            name = "demo",
            type = "C",
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
