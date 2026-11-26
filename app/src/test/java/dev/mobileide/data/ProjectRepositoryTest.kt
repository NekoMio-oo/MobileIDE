package dev.mobileide.data

import dev.mobileide.core.filesystem.NativeFileSystemImpl
import dev.mobileide.core.storage.StorageAccess
import dev.mobileide.data.local.ProjectDao
import dev.mobileide.data.local.ProjectEntity
import dev.mobileide.data.repository.ProjectRepositoryImpl
import dev.mobileide.domain.model.ProjectType
import dev.mobileide.domain.repository.CreateProjectEntryResult
import dev.mobileide.domain.repository.CreateProjectResult
import dev.mobileide.domain.repository.DeleteProjectEntryResult
import dev.mobileide.domain.repository.DeleteProjectResult
import dev.mobileide.domain.repository.EntryFailure
import dev.mobileide.domain.repository.ListProjectFilesResult
import dev.mobileide.domain.repository.MAX_EDITABLE_FILE_BYTES
import dev.mobileide.domain.repository.OpenProjectResult
import dev.mobileide.domain.repository.ProjectFailure
import dev.mobileide.domain.repository.ProjectFileError
import dev.mobileide.domain.repository.ProjectFileInfoResult
import dev.mobileide.domain.repository.ReadProjectFileResult
import dev.mobileide.domain.repository.RelinkProjectResult
import dev.mobileide.domain.repository.RenameProjectEntryResult
import dev.mobileide.domain.repository.RenameProjectResult
import dev.mobileide.domain.repository.SearchProjectFilesResult
import dev.mobileide.domain.repository.WriteProjectFileResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var storageRoot: File
    private lateinit var fileSystem: NativeFileSystemImpl
    private lateinit var dao: FakeProjectDao
    private val now = 1_000_000L

    @Before
    fun setUp() {
        storageRoot = temporaryFolder.newFolder("storage")
        fileSystem = NativeFileSystemImpl(listOf(storageRoot.absolutePath))
        dao = FakeProjectDao()
    }

    private fun repository(
        dispatcher: CoroutineDispatcher,
        hasAccess: Boolean = true
    ) = ProjectRepositoryImpl(
        projectDao = dao,
        fileSystem = fileSystem,
        storageAccess = StorageAccess { hasAccess },
        ioDispatcher = dispatcher,
        clock = { now }
    )

    @Test
    fun `creating a project writes the template and records the absolute path`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))

        val result = repository.createProject("my-app", ProjectType.C, storageRoot.absolutePath)

        assertTrue(result is CreateProjectResult.Success)
        val entity = dao.currentProjects().single()
        assertEquals(File(storageRoot, "my-app").absolutePath, entity.path)
        assertNull(entity.legacyUri)
        assertTrue(File(entity.path!!, "src/main.c").isFile)
        assertTrue(File(entity.path, "include").isDirectory)
        assertTrue(File(entity.path, "README.md").isFile)
    }

    @Test
    fun `creating a project rejects an existing folder`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        File(storageRoot, "my-app").mkdirs()

        val result = repository.createProject("my-app", ProjectType.C, storageRoot.absolutePath)

        assertEquals(CreateProjectResult.DuplicateName, result)
        assertTrue(dao.currentProjects().isEmpty())
    }

    @Test
    fun `creating a project rejects an invalid name`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))

        val result = repository.createProject("bad/name", ProjectType.C, storageRoot.absolutePath)

        assertTrue(result is CreateProjectResult.InvalidName)
        assertTrue(dao.currentProjects().isEmpty())
    }

    @Test
    fun `creating a project without storage access fails`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler), hasAccess = false)

        val result = repository.createProject("my-app", ProjectType.C, storageRoot.absolutePath)

        assertTrue(result is CreateProjectResult.Failure)
        assertEquals(
            ProjectFailure.PERMISSION_DENIED,
            (result as CreateProjectResult.Failure).reason
        )
    }

    @Test
    fun `creating a project creates a missing root directory`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val root = File(storageRoot, "nested/AndroidProjects").absolutePath

        val result = repository.createProject("demo", ProjectType.PYTHON, root)

        assertTrue(result is CreateProjectResult.Success)
        assertTrue(File(root, "demo/main.py").isFile)
    }

    @Test
    fun `opening a folder registers it once and detects the language`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "notes-app").apply { mkdirs() }
        File(project, "src").mkdirs()
        File(project, "src/Main.kt").writeText("fun main() {}")

        val first = repository.openProject(project.absolutePath)
        val second = repository.openProject(project.absolutePath)

        assertTrue(first is OpenProjectResult.Success)
        assertTrue(second is OpenProjectResult.Success)
        assertEquals(
            (first as OpenProjectResult.Success).projectId,
            (second as OpenProjectResult.Success).projectId
        )
        val entity = dao.currentProjects().single()
        assertEquals(ProjectType.KOTLIN.name, entity.type)
        assertEquals(project.absolutePath, entity.path)
    }

    @Test
    fun `opening a folder outside shared storage is rejected`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val outside = temporaryFolder.newFolder("outside")

        val result = repository.openProject(outside.absolutePath)

        assertTrue(result is OpenProjectResult.Failure)
        assertEquals(
            ProjectFailure.INVALID_PATH,
            (result as OpenProjectResult.Failure).reason
        )
    }

    @Test
    fun `relinking a legacy project stores the chosen path`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val legacy = dao.seed(path = null, legacyUri = "content://old/tree")
        val project = File(storageRoot, "legacy-app").apply { mkdirs() }

        val result = repository.relinkProject(legacy.id, project.absolutePath)

        assertEquals(RelinkProjectResult.Success, result)
        val updated = dao.currentProjects().single { it.id == legacy.id }
        assertEquals(project.absolutePath, updated.path)
        assertNull(updated.legacyUri)
    }

    @Test
    fun `relinking refuses a path used by another project`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "taken").apply { mkdirs() }
        dao.seed(path = project.absolutePath)
        val legacy = dao.seed(path = null, legacyUri = "content://old/tree")

        val result = repository.relinkProject(legacy.id, project.absolutePath)

        assertEquals(RelinkProjectResult.AlreadyRegistered, result)
        assertNull(dao.currentProjects().single { it.id == legacy.id }.path)
    }

    @Test
    fun `renaming a project renames the folder and updates the path`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "old-name").apply { mkdirs() }
        val entity = dao.seed(path = project.absolutePath)

        val result = repository.renameProject(entity.id, "new-name")

        assertEquals(RenameProjectResult.Success, result)
        assertFalse(project.exists())
        assertTrue(File(storageRoot, "new-name").isDirectory)
        val updated = dao.currentProjects().single()
        assertEquals("new-name", updated.name)
        assertEquals(File(storageRoot, "new-name").absolutePath, updated.path)
    }

    @Test
    fun `renaming a legacy project is refused`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val legacy = dao.seed(path = null, legacyUri = "content://old/tree")

        val result = repository.renameProject(legacy.id, "renamed")

        assertEquals(RenameProjectResult.NotFound, result)
    }

    @Test
    fun `removing a project keeps its files`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "keep-me").apply { mkdirs() }
        val entity = dao.seed(path = project.absolutePath)

        val result = repository.removeProject(entity.id)

        assertEquals(DeleteProjectResult.Success, result)
        assertTrue(dao.currentProjects().isEmpty())
        assertTrue(project.isDirectory)
    }

    @Test
    fun `deleting project files removes the folder and the record`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "delete-me/src").apply { mkdirs() }
        File(project, "main.c").writeText("x")
        val entity = dao.seed(path = File(storageRoot, "delete-me").absolutePath)

        val result = repository.deleteProjectFiles(entity.id)

        assertEquals(DeleteProjectResult.Success, result)
        assertFalse(File(storageRoot, "delete-me").exists())
        assertTrue(dao.currentProjects().isEmpty())
    }

    @Test
    fun `deleting a folder that contains another project is refused`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val container = File(storageRoot, "container").apply { mkdirs() }
        val child = File(container, "child").apply { mkdirs() }
        val parentEntity = dao.seed(path = container.absolutePath)
        dao.seed(path = child.absolutePath)

        val result = repository.deleteProjectFiles(parentEntity.id)

        assertTrue(result is DeleteProjectResult.Failure)
        assertTrue(container.isDirectory)
        assertTrue(child.isDirectory)
        assertTrue(dao.currentProjects().any { it.id == parentEntity.id })
    }

    @Test
    fun `legacy records have no readable files until relocated`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val legacy = dao.seed(path = null, legacyUri = "content://old/tree")

        val read = repository.readProjectFile(legacy.id, "main.c")
        val access = repository.checkAccess(legacy.id)

        assertTrue(read is ReadProjectFileResult.Failure)
        assertEquals(ProjectFileError.NOT_FOUND, (read as ReadProjectFileResult.Failure).error)
        assertFalse(access.available)
    }

    @Test
    fun `reading and writing a file goes through the real filesystem`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo/src").apply { mkdirs() }
        File(project, "main.c").writeText("int main() {}")
        val entity = dao.seed(path = File(storageRoot, "demo").absolutePath)

        val read = repository.readProjectFile(entity.id, "src/main.c")
        assertTrue(read is ReadProjectFileResult.Success)
        assertEquals("int main() {}", (read as ReadProjectFileResult.Success).content)

        val write = repository.writeProjectFile(entity.id, "src/main.c", "int main(void) {}")
        assertEquals(WriteProjectFileResult.Success, write)
        assertEquals("int main(void) {}", File(project, "main.c").readText())
    }

    @Test
    fun `files above the editor limit are refused`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        val big = File(project, "big.c")
        RandomAccessFile(big, "rw").use { it.setLength(MAX_EDITABLE_FILE_BYTES + 1) }
        val entity = dao.seed(path = project.absolutePath)

        val result = repository.readProjectFile(entity.id, "big.c")

        assertTrue(result is ReadProjectFileResult.Failure)
        assertEquals(
            ProjectFileError.FILE_TOO_LARGE,
            (result as ReadProjectFileResult.Failure).error
        )
    }

    @Test
    fun `binary files are refused`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        File(project, "blob.bin").writeBytes(byteArrayOf(0x61, 0x00, 0x62))
        val entity = dao.seed(path = project.absolutePath)

        val result = repository.readProjectFile(entity.id, "blob.bin")

        assertTrue(result is ReadProjectFileResult.Failure)
        assertEquals(ProjectFileError.BINARY, (result as ReadProjectFileResult.Failure).error)
    }

    @Test
    fun `listing a folder maps entries to relative paths`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        File(project, "src").mkdirs()
        File(project, "src/main.c").writeText("x")
        val entity = dao.seed(path = project.absolutePath)

        val result = repository.listProjectFiles(entity.id, "")

        assertTrue(result is ListProjectFilesResult.Success)
        val entries = (result as ListProjectFilesResult.Success).entries
        assertTrue(entries.any { it.name == "src" && it.path == "src" && it.isDirectory })
    }

    @Test
    fun `writing without storage access fails`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler), hasAccess = false)
        val project = File(storageRoot, "demo").apply { mkdirs() }
        File(project, "a.txt").writeText("old")
        val entity = dao.seed(path = project.absolutePath)

        val result = repository.writeProjectFile(entity.id, "a.txt", "new")

        assertTrue(result is WriteProjectFileResult.Failure)
        assertEquals(
            ProjectFileError.PERMISSION_DENIED,
            (result as WriteProjectFileResult.Failure).error
        )
        assertEquals("old", File(project, "a.txt").readText())
    }

    @Test
    fun `checking access reports missing folders`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "gone").apply { mkdirs() }
        val entity = dao.seed(path = project.absolutePath)
        project.deleteRecursively()

        val access = repository.checkAccess(entity.id)

        assertFalse(access.available)
        assertEquals(ProjectFailure.NOT_FOUND, access.reason)
    }

    @Test
    fun `creating a file and a folder writes real entries`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        File(project, "src").mkdirs()
        val entity = dao.seed(path = project.absolutePath)

        val file = repository.createProjectEntry(entity.id, "", "notes.md", isDirectory = false)
        val folder = repository.createProjectEntry(entity.id, "src", "utils", isDirectory = true)

        assertTrue(file is CreateProjectEntryResult.Success)
        assertEquals("notes.md", (file as CreateProjectEntryResult.Success).entry.path)
        assertTrue(File(project, "notes.md").isFile)
        assertEquals(0L, File(project, "notes.md").length())

        assertTrue(folder is CreateProjectEntryResult.Success)
        assertEquals("src/utils", (folder as CreateProjectEntryResult.Success).entry.path)
        assertTrue(File(project, "src/utils").isDirectory)
    }

    @Test
    fun `creating an entry never overwrites an existing file`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        File(project, "main.c").writeText("keep me")
        val entity = dao.seed(path = project.absolutePath)

        val result = repository.createProjectEntry(entity.id, "", "main.c", isDirectory = false)

        assertEquals(CreateProjectEntryResult.DuplicateName, result)
        assertEquals("keep me", File(project, "main.c").readText())
    }

    @Test
    fun `creating an entry rejects invalid names and path escapes`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        val entity = dao.seed(path = project.absolutePath)

        val invalid = repository.createProjectEntry(entity.id, "", "a/b.txt", false)
        assertTrue(invalid is CreateProjectEntryResult.InvalidName)

        val escape = repository.createProjectEntry(entity.id, "..", "evil.txt", false)
        assertTrue(escape is CreateProjectEntryResult.Failure)
        assertEquals(
            EntryFailure.INVALID_PATH,
            (escape as CreateProjectEntryResult.Failure).reason
        )
        assertFalse(File(storageRoot, "evil.txt").exists())
    }

    @Test
    fun `renaming an entry moves the file and keeps its content`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        File(project, "draft.txt").writeText("hello")
        val entity = dao.seed(path = project.absolutePath)

        val result = repository.renameProjectEntry(entity.id, "draft.txt", "final.txt")

        assertTrue(result is RenameProjectEntryResult.Success)
        assertEquals("final.txt", (result as RenameProjectEntryResult.Success).entry.path)
        assertFalse(File(project, "draft.txt").exists())
        assertEquals("hello", File(project, "final.txt").readText())
    }

    @Test
    fun `renaming an entry refuses an existing target`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        File(project, "a.txt").writeText("a")
        File(project, "b.txt").writeText("b")
        val entity = dao.seed(path = project.absolutePath)

        val result = repository.renameProjectEntry(entity.id, "a.txt", "b.txt")

        assertEquals(RenameProjectEntryResult.DuplicateName, result)
        assertEquals("a", File(project, "a.txt").readText())
        assertEquals("b", File(project, "b.txt").readText())
    }

    @Test
    fun `deleting a folder removes its contents and the project root is protected`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        File(project, "src/nested").mkdirs()
        File(project, "src/nested/main.c").writeText("x")
        val entity = dao.seed(path = project.absolutePath)

        val rootAttempt = repository.deleteProjectEntry(entity.id, "")
        assertTrue(rootAttempt is DeleteProjectEntryResult.Failure)
        assertEquals(
            EntryFailure.INVALID_PATH,
            (rootAttempt as DeleteProjectEntryResult.Failure).reason
        )
        assertTrue(project.isDirectory)

        val deleted = repository.deleteProjectEntry(entity.id, "src")
        assertEquals(DeleteProjectEntryResult.Success, deleted)
        assertFalse(File(project, "src").exists())
        assertTrue(project.isDirectory)
    }

    @Test
    fun `searching returns project relative paths and filters by extension`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        File(project, "src").mkdirs()
        File(project, "src/main.c").writeText("x")
        File(project, "src/Main.kt").writeText("x")
        File(project, "README.md").writeText("x")
        val entity = dao.seed(path = project.absolutePath)

        val all = repository.searchProjectFiles(entity.id, "main")
        assertTrue(all is SearchProjectFilesResult.Success)
        val allPaths = (all as SearchProjectFilesResult.Success).entries.map { it.path }.sorted()
        assertEquals(listOf("src/Main.kt", "src/main.c"), allPaths)

        val kotlinOnly = repository.searchProjectFiles(
            projectId = entity.id,
            query = "main",
            extensions = setOf("kt")
        )
        assertTrue(kotlinOnly is SearchProjectFilesResult.Success)
        val kotlinEntries = (kotlinOnly as SearchProjectFilesResult.Success).entries
        assertEquals(1, kotlinEntries.size)
        assertEquals("src/Main.kt", kotlinEntries.single().path)
    }

    @Test
    fun `counting entries reports the folder contents`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        File(project, "src").mkdirs()
        File(project, "src/a.c").writeText("x")
        File(project, "b.md").writeText("x")
        val entity = dao.seed(path = project.absolutePath)

        assertEquals(3, repository.countProjectEntries(entity.id, ""))
    }

    @Test
    fun `file info reports the metadata used for change detection`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        val file = File(project, "notes.txt").apply { writeText("hello") }
        val entity = dao.seed(path = project.absolutePath)

        val info = repository.getProjectFileInfo(entity.id, "notes.txt")

        assertTrue(info is ProjectFileInfoResult.Success)
        val entry = (info as ProjectFileInfoResult.Success).entry
        assertEquals("notes.txt", entry.path)
        assertEquals(5L, entry.size)
        assertEquals(file.lastModified(), entry.lastModified)

        val missing = repository.getProjectFileInfo(entity.id, "gone.txt")
        assertTrue(missing is ProjectFileInfoResult.Failure)
        assertEquals(
            ProjectFileError.NOT_FOUND,
            (missing as ProjectFileInfoResult.Failure).error
        )
    }

    @Test
    fun `saving a deleted file does not recreate it`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        val entity = dao.seed(path = project.absolutePath)

        val result = repository.writeProjectFile(entity.id, "gone.c", "int main() {}")

        assertTrue(result is WriteProjectFileResult.Failure)
        assertFalse(File(project, "gone.c").exists())
    }

    @Test
    fun `symlinks that leave the project are rejected`() = runTest {
        val repository = repository(StandardTestDispatcher(testScheduler))
        val project = File(storageRoot, "demo").apply { mkdirs() }
        val outside = File(storageRoot, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("secret")
        val link = File(project, "link")
        val supported = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        org.junit.Assume.assumeTrue("Filesystem does not support symlinks", supported)
        val entity = dao.seed(path = project.absolutePath)

        val read = repository.readProjectFile(entity.id, "link/secret.txt")

        assertTrue(read is ReadProjectFileResult.Failure)
        assertEquals(
            ProjectFileError.INVALID_PATH,
            (read as ReadProjectFileResult.Failure).error
        )
    }
}

private class FakeProjectDao : ProjectDao {

    private val projects = MutableStateFlow<List<ProjectEntity>>(emptyList())
    private var nextId = 1L

    fun currentProjects(): List<ProjectEntity> = projects.value

    fun seed(
        name: String = "sample",
        path: String? = null,
        legacyUri: String? = null,
        type: ProjectType = ProjectType.KOTLIN
    ): ProjectEntity {
        val entity = ProjectEntity(
            id = nextId++,
            name = name,
            type = type.name,
            path = path,
            legacyUri = legacyUri,
            locationName = "",
            createdAt = 0L,
            lastOpenedAt = 0L,
            updatedAt = 0L
        )
        projects.update { it + entity }
        return entity
    }

    override fun observeAll(): Flow<List<ProjectEntity>> =
        projects.map { list -> list.sortedByDescending { it.lastOpenedAt } }

    override fun observeRecent(limit: Int): Flow<List<ProjectEntity>> =
        observeAll().map { list -> list.take(limit) }

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
    ) {
        projects.update { list ->
            list.map {
                if (it.id == projectId) {
                    it.copy(name = name, path = path, updatedAt = updatedAt)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun updatePath(
        projectId: Long,
        path: String,
        locationName: String,
        updatedAt: Long
    ) {
        projects.update { list ->
            list.map {
                if (it.id == projectId) {
                    it.copy(
                        path = path,
                        locationName = locationName,
                        legacyUri = null,
                        updatedAt = updatedAt
                    )
                } else {
                    it
                }
            }
        }
    }

    override suspend fun touch(projectId: Long, openedAt: Long) {
        projects.update { list ->
            list.map {
                if (it.id == projectId) {
                    it.copy(lastOpenedAt = openedAt, updatedAt = openedAt)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun deleteById(projectId: Long) {
        projects.update { list -> list.filterNot { it.id == projectId } }
    }
}
