package dev.mobileide.data.git

import dev.mobileide.core.filesystem.NativeFileSystemImpl
import dev.mobileide.core.storage.StorageAccess
import dev.mobileide.data.local.ProjectDao
import dev.mobileide.data.local.ProjectEntity
import dev.mobileide.data.repository.ProjectRepositoryImpl
import dev.mobileide.domain.git.GitAvailability
import dev.mobileide.domain.git.GitChangeKind
import dev.mobileide.domain.git.GitCommitDetailResult
import dev.mobileide.domain.git.GitDiffResult
import dev.mobileide.domain.git.GitErrorKind
import dev.mobileide.domain.git.GitHistoryResult
import dev.mobileide.domain.git.GitInstallResult
import dev.mobileide.domain.git.GitSnapshot
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
import org.junit.Assert.assertNull
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

    @Test
    fun `stages and unstages a file in a repository without commits`() = runBlocking {
        initializeRepository()
        File(projectDir, "notes.txt").writeText("hello\n")
        repository.refresh(projectId)
        val untracked = repository.observe(projectId).value.status!!
            .untracked.single { it.path == "notes.txt" }

        repository.stage(projectId, untracked.path)
        var state = awaitStatus {
            it.status?.staged?.any { change -> change.path == "notes.txt" } == true
        }
        assertEquals(listOf("notes.txt"), state.status!!.staged.map { it.path })

        repository.unstage(projectId, "notes.txt")
        state = awaitStatus {
            it.status?.untracked?.any { change -> change.path == "notes.txt" } == true
        }
        assertTrue(state.status!!.untracked.any { it.path == "notes.txt" })
        assertTrue(state.status!!.staged.isEmpty())
    }

    @Test
    fun `stages all and unstages all after the first commit`() = runBlocking {
        initializeRepository()
        commitAll("initial")
        repository.refresh(projectId)
        File(projectDir, "a.txt").writeText("a\n")
        File(projectDir, "b.txt").writeText("b\n")
        repository.refresh(projectId)

        repository.stageAll(projectId)
        var state = awaitStatus { it.status?.staged?.size == 2 }
        assertEquals(setOf("a.txt", "b.txt"), state.status!!.staged.map { it.path }.toSet())

        repository.unstageAll(projectId)
        state = awaitStatus { it.status?.untracked?.size == 2 }
        assertEquals(setOf("a.txt", "b.txt"), state.status!!.untracked.map { it.path }.toSet())
    }

    @Test
    fun `unstages with a reset when the repository has a commit`() = runBlocking {
        initializeRepository()
        commitAll("initial")
        repository.refresh(projectId)
        File(projectDir, "README.md").appendText("seed\n")
        repository.refresh(projectId)

        repository.stage(projectId, "README.md")
        var state = awaitStatus {
            it.status?.staged?.any { change -> change.path == "README.md" } == true
        }
        assertTrue(state.status!!.unstaged.isEmpty())

        repository.unstage(projectId, "README.md")
        state = awaitStatus {
            it.status?.unstaged?.any { change -> change.path == "README.md" } == true
        }
        assertTrue(state.status!!.staged.isEmpty())
    }

    @Test
    fun `discards an unstaged tracked modification after confirmation`() = runBlocking {
        initializeRepository()
        commitAll("initial")
        repository.refresh(projectId)
        val file = File(projectDir, "README.md")
        val original = file.readText()
        file.appendText("do not keep\n")
        repository.refresh(projectId)
        val change = repository.observe(projectId).value.status!!
            .unstaged.single { it.path == "README.md" }

        repository.discardChanges(projectId, change, confirmed = true)

        awaitStatus { it.status?.unstaged?.isEmpty() == true }
        assertEquals(original, file.readText())
    }

    @Test
    fun `deletes an untracked file only when confirmed`() = runBlocking {
        initializeRepository()
        val file = File(projectDir, "scratch.txt").apply { writeText("temp\n") }
        repository.refresh(projectId)
        val change = repository.observe(projectId).value.status!!
            .untracked.single { it.path == "scratch.txt" }

        repository.discardChanges(projectId, change, confirmed = false)
        assertTrue(file.exists())
        assertEquals(
            GitErrorKind.UNKNOWN,
            repository.observe(projectId).value.error?.kind
        )

        repository.discardChanges(projectId, change, confirmed = true)
        awaitStatus {
            it.status?.untracked?.none { item -> item.path == "scratch.txt" } == true
        }
        assertFalse(file.exists())
    }

    @Test
    fun `returns staged and worktree diffs for a real modification`() = runBlocking {
        initializeRepository()
        commitAll("initial")
        repository.refresh(projectId)
        val file = File(projectDir, "README.md")
        file.writeText("# demo\n\nchanged\n")
        repository.refresh(projectId)
        val change = repository.observe(projectId).value.status!!
            .unstaged.single { it.path == "README.md" }

        val worktree = repository.diff(projectId, change, staged = false)
        assertTrue(worktree is GitDiffResult.Success)
        val worktreeDiff = (worktree as GitDiffResult.Success).diff
        assertEquals("README.md", worktreeDiff.files.single().path)
        assertTrue(worktreeDiff.addedLines >= 1)

        repository.stage(projectId, "README.md")
        val state = awaitStatus {
            it.status?.staged?.any { item -> item.path == "README.md" } == true
        }
        val stagedChange = state.status!!.staged.single { it.path == "README.md" }
        val staged = repository.diff(projectId, stagedChange, staged = true)
        assertTrue(staged is GitDiffResult.Success)
        assertTrue((staged as GitDiffResult.Success).diff.addedLines >= 1)
    }

    @Test
    fun `renders an untracked file as all added lines`() = runBlocking {
        initializeRepository()
        File(projectDir, "new.txt").writeText("one\ntwo\n")
        repository.refresh(projectId)
        val change = repository.observe(projectId).value.status!!
            .untracked.single { it.path == "new.txt" }

        val result = repository.diff(projectId, change, staged = false)

        assertTrue(result is GitDiffResult.Success)
        val diff = (result as GitDiffResult.Success).diff
        assertEquals(2, diff.addedLines)
        assertEquals(0, diff.removedLines)
        assertEquals(
            listOf(1, 2),
            diff.files.single().hunks.single().lines.map { it.newLine }
        )
    }

    @Test
    fun `reports an untracked binary file without text hunks`() = runBlocking {
        initializeRepository()
        File(projectDir, "image.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x00)
        )
        repository.refresh(projectId)
        val change = repository.observe(projectId).value.status!!
            .untracked.single { it.path == "image.png" }

        val result = repository.diff(projectId, change, staged = false)

        assertTrue(result is GitDiffResult.Success)
        assertTrue((result as GitDiffResult.Success).diff.files.single().isBinary)
    }

    @Test
    fun `history is empty before the first commit`() = runBlocking {
        initializeRepository()

        val history = repository.history(projectId)

        assertTrue(history is GitHistoryResult.Success)
        assertTrue((history as GitHistoryResult.Success).commits.isEmpty())
    }

    @Test
    fun `history and commit detail come from real git`() = runBlocking {
        initializeRepository()
        commitAll("initial")
        repository.refresh(projectId)

        val rootHistory = repository.history(projectId)
        assertTrue(rootHistory is GitHistoryResult.Success)
        val rootCommit = (rootHistory as GitHistoryResult.Success).commits.single()
        assertEquals("initial", rootCommit.subject)
        assertTrue(rootCommit.parents.isEmpty())

        val rootDetail = repository.commitDetail(projectId, rootCommit.hash)
        assertTrue(rootDetail is GitCommitDetailResult.Success)
        val detail = (rootDetail as GitCommitDetailResult.Success).detail
        assertEquals("initial", detail.commit.subject)
        assertEquals("initial", detail.message)
        assertTrue(
            detail.files.any { it.path == "README.md" && it.change == GitChangeKind.ADDED }
        )
        assertTrue(detail.branches.contains("main"))
        assertNull(detail.compareParent)

        val rootDiff = repository.commitDiff(projectId, rootCommit.hash, "README.md")
        assertTrue(rootDiff is GitDiffResult.Success)
        assertTrue((rootDiff as GitDiffResult.Success).diff.addedLines > 0)

        File(projectDir, "README.md").appendText("more\n")
        commitAll("second")
        repository.refresh(projectId)
        val history = repository.history(projectId)
        val commits = (history as GitHistoryResult.Success).commits
        assertEquals("second", commits.first().subject)

        val secondDetail = repository.commitDetail(projectId, commits.first().hash)
        assertTrue(secondDetail is GitCommitDetailResult.Success)
        val second = (secondDetail as GitCommitDetailResult.Success).detail
        assertTrue(
            second.files.any {
                it.path == "README.md" && it.change == GitChangeKind.MODIFIED
            }
        )
        assertNotNull(second.compareParent)
    }

    @Test
    fun `commits staged changes through the repository`() = runBlocking {
        initializeRepository()
        commitAll("initial")
        repository.refresh(projectId)
        File(projectDir, "notes.txt").writeText("note\n")
        repository.refresh(projectId)

        repository.stage(projectId, "notes.txt")
        awaitStatus {
            it.status?.staged?.any { change -> change.path == "notes.txt" } == true
        }

        repository.commit(projectId, "add notes")

        awaitStatus {
            !it.isBusy && it.status?.staged?.isEmpty() == true
        }
        val history = repository.history(projectId)
        val commits = (history as GitHistoryResult.Success).commits
        assertEquals("add notes", commits.first().subject)

        repository.clearError(projectId)
        repository.commit(projectId, "again")
        assertEquals(
            "No staged changes to commit.",
            repository.observe(projectId).value.error?.message
        )

        repository.clearError(projectId)
        repository.commit(projectId, "   ")
        assertEquals(
            "Enter a commit message.",
            repository.observe(projectId).value.error?.message
        )
    }

    private suspend fun initializeRepository() {
        repository.initRepository(projectId, "main")
        withTimeout(20_000) {
            repository.observe(projectId).first {
                it.availability == GitAvailability.READY && !it.isBusy
            }
        }
        val runner = LocalGitRunner()
        runner.run(
            projectPath = projectDir.absolutePath,
            args = listOf("config", "user.name", "MobileIDE Test"),
            options = GitRunOptions(readOnly = false)
        )
        runner.run(
            projectPath = projectDir.absolutePath,
            args = listOf("config", "user.email", "test@example.com"),
            options = GitRunOptions(readOnly = false)
        )
    }

    private suspend fun awaitStatus(predicate: (GitSnapshot) -> Boolean): GitSnapshot =
        withTimeout(20_000) {
            repository.observe(projectId).first { !it.isBusy && predicate(it) }
        }

    private suspend fun commitAll(message: String) {
        val runner = LocalGitRunner()
        assertTrue(
            runner.run(
                projectPath = projectDir.absolutePath,
                args = listOf("add", "-A"),
                options = GitRunOptions(readOnly = false)
            ).isSuccess
        )
        assertTrue(
            runner.run(
                projectPath = projectDir.absolutePath,
                args = listOf(
                    "-c", "user.name=MobileIDE Test",
                    "-c", "user.email=test@example.com",
                    "commit", "-m", message
                ),
                options = GitRunOptions(readOnly = false)
            ).isSuccess
        )
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
