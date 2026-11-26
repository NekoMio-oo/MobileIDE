package dev.mobileide.data.repository

import android.util.Log
import dev.mobileide.core.filesystem.FileInfo
import dev.mobileide.core.filesystem.NativeFileException
import dev.mobileide.core.filesystem.NativeFileSystem
import dev.mobileide.core.storage.StorageAccess
import dev.mobileide.data.local.ProjectDao
import dev.mobileide.data.local.ProjectEntity
import dev.mobileide.domain.ProjectTypeDetector
import dev.mobileide.domain.model.Project
import dev.mobileide.domain.model.ProjectType
import dev.mobileide.domain.repository.AccessStatus
import dev.mobileide.domain.repository.CreateProjectEntryResult
import dev.mobileide.domain.repository.CreateProjectResult
import dev.mobileide.domain.repository.DeleteProjectEntryResult
import dev.mobileide.domain.repository.DeleteProjectResult
import dev.mobileide.domain.repository.EntryFailure
import dev.mobileide.domain.repository.ListProjectFilesResult
import dev.mobileide.domain.repository.MAX_EDITABLE_FILE_BYTES
import dev.mobileide.domain.repository.OpenProjectResult
import dev.mobileide.domain.repository.ProjectFailure
import dev.mobileide.domain.repository.ProjectFileEntry
import dev.mobileide.domain.repository.ProjectFileError
import dev.mobileide.domain.repository.ProjectFileInfoResult
import dev.mobileide.domain.repository.ProjectRepository
import dev.mobileide.domain.repository.ReadProjectFileResult
import dev.mobileide.domain.repository.RelinkProjectResult
import dev.mobileide.domain.repository.RenameProjectEntryResult
import dev.mobileide.domain.repository.RenameProjectResult
import dev.mobileide.domain.repository.SaveProjectDocumentResult
import dev.mobileide.domain.repository.SearchProjectFilesResult
import dev.mobileide.domain.repository.WriteProjectFileResult
import dev.mobileide.domain.template.ProjectTemplates
import dev.mobileide.domain.template.TemplateEntry
import dev.mobileide.domain.validation.EntryNameValidator
import dev.mobileide.domain.validation.ProjectNameValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Real-filesystem project repository. Project roots are absolute paths under
 * shared storage; SAF is no longer involved anywhere.
 */
class ProjectRepositoryImpl(
    private val projectDao: ProjectDao,
    private val fileSystem: NativeFileSystem,
    private val storageAccess: StorageAccess,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis
) : ProjectRepository {

    override fun observeProjects(): Flow<List<Project>> =
        projectDao.observeAll().map { entities -> entities.map(ProjectEntity::toDomain) }

    override fun observeRecentProjects(limit: Int): Flow<List<Project>> =
        projectDao.observeRecent(limit).map { entities -> entities.map(ProjectEntity::toDomain) }

    override suspend fun getProject(projectId: Long): Project? = withContext(ioDispatcher) {
        projectDao.findById(projectId)?.toDomain()
    }

    override suspend fun createProject(
        name: String,
        type: ProjectType,
        rootPath: String
    ): CreateProjectResult = withContext(ioDispatcher) {
        val normalized = ProjectNameValidator.normalize(name)
        ProjectNameValidator.validate(normalized)?.let {
            return@withContext CreateProjectResult.InvalidName(it)
        }
        if (!storageAccess.hasAccess()) {
            return@withContext CreateProjectResult.Failure(ProjectFailure.PERMISSION_DENIED)
        }
        if (rootPath.isBlank() || !fileSystem.isWithinAllowedRoots(rootPath)) {
            return@withContext CreateProjectResult.Failure(
                ProjectFailure.INVALID_PATH,
                rootPath
            )
        }

        try {
            if (!fileSystem.exists(rootPath)) {
                fileSystem.createDirectory(rootPath)
            }
            val rootInfo = fileSystem.getFileInfo(rootPath)
                ?: return@withContext CreateProjectResult.Failure(
                    ProjectFailure.INVALID_PATH,
                    "Project root is unavailable"
                )
            if (!rootInfo.isDirectory) {
                return@withContext CreateProjectResult.Failure(
                    ProjectFailure.INVALID_PATH,
                    "Project root is not a directory"
                )
            }
            if (!rootInfo.canWrite) {
                return@withContext CreateProjectResult.Failure(
                    ProjectFailure.PERMISSION_DENIED,
                    "Project root is not writable"
                )
            }

            val projectPath = File(rootPath, normalized).absolutePath
            if (fileSystem.exists(projectPath)) {
                return@withContext CreateProjectResult.DuplicateName
            }

            fileSystem.createDirectory(projectPath)
            try {
                writeTemplate(projectPath, ProjectTemplates.forType(type, normalized))
            } catch (error: Exception) {
                runCatching { fileSystem.deleteFile(projectPath, recursive = true) }
                throw error
            }

            val now = clock()
            val projectId = projectDao.insert(
                ProjectEntity(
                    name = normalized,
                    type = type.name,
                    path = projectPath,
                    legacyUri = null,
                    locationName = File(projectPath).parentFile?.name.orEmpty(),
                    createdAt = now,
                    lastOpenedAt = now,
                    updatedAt = now
                )
            )
            CreateProjectResult.Success(projectId)
        } catch (error: SecurityException) {
            CreateProjectResult.Failure(ProjectFailure.PERMISSION_DENIED, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "createProject failed for $rootPath", error)
            CreateProjectResult.Failure(ProjectFailure.IO, error.message)
        }
    }

    override suspend fun openProject(path: String): OpenProjectResult =
        withContext(ioDispatcher) {
            if (!storageAccess.hasAccess()) {
                return@withContext OpenProjectResult.Failure(ProjectFailure.PERMISSION_DENIED)
            }
            if (path.isBlank() || !fileSystem.isWithinAllowedRoots(path) || !hasAllowedParent(path)) {
                return@withContext OpenProjectResult.Failure(
                    ProjectFailure.INVALID_PATH,
                    "Path is outside shared storage"
                )
            }
            try {
                val info = fileSystem.getFileInfo(path)
                    ?: return@withContext OpenProjectResult.Failure(ProjectFailure.NOT_FOUND)
                if (!info.isDirectory) {
                    return@withContext OpenProjectResult.Failure(
                        ProjectFailure.INVALID_PATH,
                        "Selected path is not a folder"
                    )
                }
                if (!info.canRead || !info.canWrite) {
                    return@withContext OpenProjectResult.Failure(
                        ProjectFailure.PERMISSION_DENIED,
                        "Folder is not readable or writable"
                    )
                }

                val now = clock()
                val existing = projectDao.findByPath(path)
                if (existing != null) {
                    projectDao.touch(existing.id, now)
                    return@withContext OpenProjectResult.Success(existing.id)
                }

                val projectId = projectDao.insert(
                    ProjectEntity(
                        name = File(path).name,
                        type = ProjectTypeDetector.detect(
                            runCatching { collectDetectionNames(path) }.getOrDefault(emptyList())
                        ).name,
                        path = path,
                        legacyUri = null,
                        locationName = File(path).parentFile?.name.orEmpty(),
                        createdAt = now,
                        lastOpenedAt = now,
                        updatedAt = now
                    )
                )
                OpenProjectResult.Success(projectId)
            } catch (error: SecurityException) {
                OpenProjectResult.Failure(ProjectFailure.PERMISSION_DENIED, error.message)
            } catch (error: Exception) {
                Log.w(TAG, "openProject failed for $path", error)
                OpenProjectResult.Failure(ProjectFailure.IO, error.message)
            }
        }

    override suspend fun relinkProject(
        projectId: Long,
        path: String
    ): RelinkProjectResult = withContext(ioDispatcher) {
        val entity = projectDao.findById(projectId)
            ?: return@withContext RelinkProjectResult.NotFound
        if (!storageAccess.hasAccess()) {
            return@withContext RelinkProjectResult.Failure(ProjectFailure.PERMISSION_DENIED)
        }
        if (path.isBlank() || !fileSystem.isWithinAllowedRoots(path) || !hasAllowedParent(path)) {
            return@withContext RelinkProjectResult.Failure(
                ProjectFailure.INVALID_PATH,
                "Path is outside shared storage"
            )
        }
        try {
            val info = fileSystem.getFileInfo(path)
                ?: return@withContext RelinkProjectResult.Failure(ProjectFailure.NOT_FOUND)
            if (!info.isDirectory) {
                return@withContext RelinkProjectResult.Failure(
                    ProjectFailure.INVALID_PATH,
                    "Selected path is not a folder"
                )
            }
            if (!info.canRead || !info.canWrite) {
                return@withContext RelinkProjectResult.Failure(
                    ProjectFailure.PERMISSION_DENIED,
                    "Folder is not readable or writable"
                )
            }
            val existing = projectDao.findByPath(path)
            if (existing != null && existing.id != entity.id) {
                return@withContext RelinkProjectResult.AlreadyRegistered
            }
            projectDao.updatePath(
                projectId = projectId,
                path = path,
                locationName = File(path).parentFile?.name.orEmpty(),
                updatedAt = clock()
            )
            RelinkProjectResult.Success
        } catch (error: SecurityException) {
            RelinkProjectResult.Failure(ProjectFailure.PERMISSION_DENIED, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "relinkProject failed for $path", error)
            RelinkProjectResult.Failure(ProjectFailure.IO, error.message)
        }
    }

    override suspend fun markOpened(projectId: Long) = withContext(ioDispatcher) {
        projectDao.touch(projectId, clock())
    }

    override suspend fun renameProject(
        projectId: Long,
        newName: String
    ): RenameProjectResult = withContext(ioDispatcher) {
        val entity = projectDao.findById(projectId)
            ?: return@withContext RenameProjectResult.NotFound
        val path = entity.path ?: return@withContext RenameProjectResult.NotFound

        val normalized = ProjectNameValidator.normalize(newName)
        ProjectNameValidator.validate(normalized)?.let {
            return@withContext RenameProjectResult.InvalidName(it)
        }
        if (!storageAccess.hasAccess()) {
            return@withContext RenameProjectResult.Failure(ProjectFailure.PERMISSION_DENIED)
        }
        if (!fileSystem.isWithinAllowedRoots(path)) {
            return@withContext RenameProjectResult.Failure(ProjectFailure.INVALID_PATH)
        }

        try {
            fileSystem.renameFile(path, normalized)
            val newPath = File(File(path).parentFile, normalized).absolutePath
            projectDao.updateNameAndPath(
                projectId = projectId,
                name = normalized,
                path = newPath,
                updatedAt = clock()
            )
            RenameProjectResult.Success
        } catch (error: SecurityException) {
            RenameProjectResult.Failure(ProjectFailure.PERMISSION_DENIED, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "renameProject failed for $path", error)
            RenameProjectResult.Failure(ProjectFailure.IO, error.message)
        }
    }

    override suspend fun removeProject(projectId: Long): DeleteProjectResult =
        withContext(ioDispatcher) {
            projectDao.deleteById(projectId)
            DeleteProjectResult.Success
        }

    override suspend fun deleteProjectFiles(projectId: Long): DeleteProjectResult =
        withContext(ioDispatcher) {
            val entity = projectDao.findById(projectId)
                ?: return@withContext DeleteProjectResult.Failure(ProjectFailure.NOT_FOUND)
            val path = entity.path
                ?: return@withContext DeleteProjectResult.Failure(ProjectFailure.NOT_FOUND)
            if (!storageAccess.hasAccess()) {
                return@withContext DeleteProjectResult.Failure(ProjectFailure.PERMISSION_DENIED)
            }
            if (!fileSystem.isWithinAllowedRoots(path)) {
                return@withContext DeleteProjectResult.Failure(ProjectFailure.INVALID_PATH)
            }
            val containsOtherProjects = projectDao.getAll().any { other ->
                other.id != projectId &&
                    other.path?.startsWith(path + File.separator) == true
            }
            if (containsOtherProjects) {
                return@withContext DeleteProjectResult.Failure(
                    ProjectFailure.IO,
                    "This folder contains other registered projects"
                )
            }
            try {
                fileSystem.deleteFile(path, recursive = true)
                projectDao.deleteById(projectId)
                DeleteProjectResult.Success
            } catch (error: SecurityException) {
                DeleteProjectResult.Failure(ProjectFailure.PERMISSION_DENIED, error.message)
            } catch (error: Exception) {
                Log.w(TAG, "deleteProjectFiles failed for $path", error)
                DeleteProjectResult.Failure(ProjectFailure.IO, error.message)
            }
        }

    override suspend fun checkAccess(projectId: Long): AccessStatus =
        withContext(ioDispatcher) {
            val entity = projectDao.findById(projectId)
                ?: return@withContext AccessStatus(false, ProjectFailure.NOT_FOUND)
            val path = entity.path
                ?: return@withContext AccessStatus(false, ProjectFailure.NOT_FOUND)
            if (!storageAccess.hasAccess()) {
                return@withContext AccessStatus(false, ProjectFailure.PERMISSION_DENIED)
            }
            val info = runCatching { fileSystem.getFileInfo(path) }.getOrNull()
                ?: return@withContext AccessStatus(false, ProjectFailure.NOT_FOUND)
            if (!info.isDirectory) {
                return@withContext AccessStatus(false, ProjectFailure.INVALID_PATH)
            }
            if (!info.canRead || !info.canWrite) {
                return@withContext AccessStatus(false, ProjectFailure.PERMISSION_DENIED)
            }
            AccessStatus(true)
        }

    override suspend fun listProjectFiles(
        projectId: Long,
        directoryPath: String
    ): ListProjectFilesResult = withContext(ioDispatcher) {
        val project = projectDao.findById(projectId)
            ?: return@withContext ListProjectFilesResult.Failure(ProjectFileError.NOT_FOUND)
        val rootPath = project.path
            ?: return@withContext ListProjectFilesResult.Failure(ProjectFileError.NOT_FOUND)
        if (!storageAccess.hasAccess()) {
            return@withContext ListProjectFilesResult.Failure(ProjectFileError.PERMISSION_DENIED)
        }
        val absolute = try {
            resolveProjectPath(rootPath, directoryPath)
        } catch (error: Exception) {
            return@withContext ListProjectFilesResult.Failure(
                ProjectFileError.INVALID_PATH,
                error.message
            )
        }
        try {
            val entries = fileSystem.listFiles(absolute)
            ListProjectFilesResult.Success(
                entries.map { node -> node.toEntry(joinPath(directoryPath, node.name)) }
            )
        } catch (error: SecurityException) {
            ListProjectFilesResult.Failure(ProjectFileError.PERMISSION_DENIED, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "listProjectFiles failed for $absolute", error)
            ListProjectFilesResult.Failure(ProjectFileError.IO, error.message)
        }
    }

    override suspend fun createProjectEntry(
        projectId: Long,
        directoryPath: String,
        name: String,
        isDirectory: Boolean
    ): CreateProjectEntryResult = withContext(ioDispatcher) {
        val normalized = EntryNameValidator.normalize(name)
        EntryNameValidator.validate(normalized)?.let {
            return@withContext CreateProjectEntryResult.InvalidName(it)
        }
        val project = projectDao.findById(projectId)
            ?: return@withContext CreateProjectEntryResult.Failure(EntryFailure.NOT_FOUND)
        val rootPath = project.path
            ?: return@withContext CreateProjectEntryResult.Failure(EntryFailure.NOT_FOUND)
        if (!storageAccess.hasAccess()) {
            return@withContext CreateProjectEntryResult.Failure(EntryFailure.PERMISSION_DENIED)
        }
        val parentAbsolute = try {
            resolveProjectPath(rootPath, directoryPath)
        } catch (error: Exception) {
            return@withContext CreateProjectEntryResult.Failure(
                EntryFailure.INVALID_PATH,
                error.message
            )
        }
        val relativePath = joinPath(directoryPath, normalized)
        try {
            val parentInfo = fileSystem.getFileInfo(parentAbsolute)
                ?: return@withContext CreateProjectEntryResult.Failure(EntryFailure.NOT_FOUND)
            if (!parentInfo.isDirectory) {
                return@withContext CreateProjectEntryResult.Failure(EntryFailure.NOT_A_DIRECTORY)
            }
            if (!parentInfo.canWrite) {
                return@withContext CreateProjectEntryResult.Failure(
                    EntryFailure.PERMISSION_DENIED,
                    "The folder is read-only"
                )
            }
            // Resolve again with the new name so symlinked parents are checked.
            val absolute = resolveProjectPath(rootPath, relativePath)
            if (fileSystem.exists(absolute)) {
                return@withContext CreateProjectEntryResult.DuplicateName
            }
            if (isDirectory) {
                fileSystem.createDirectory(absolute)
            } else {
                fileSystem.createFile(absolute, "")
            }
            val info = fileSystem.getFileInfo(absolute)
                ?: return@withContext CreateProjectEntryResult.Failure(
                    EntryFailure.IO,
                    "The entry was created but could not be read back"
                )
            CreateProjectEntryResult.Success(info.toEntry(relativePath))
        } catch (error: SecurityException) {
            CreateProjectEntryResult.Failure(EntryFailure.PERMISSION_DENIED, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "createProjectEntry failed for $relativePath", error)
            CreateProjectEntryResult.Failure(EntryFailure.IO, error.message)
        }
    }

    override suspend fun renameProjectEntry(
        projectId: Long,
        path: String,
        newName: String
    ): RenameProjectEntryResult = withContext(ioDispatcher) {
        if (path.isBlank()) {
            // The project root is renamed from the project list, not here.
            return@withContext RenameProjectEntryResult.NotFound
        }
        val normalized = EntryNameValidator.normalize(newName)
        EntryNameValidator.validate(normalized)?.let {
            return@withContext RenameProjectEntryResult.InvalidName(it)
        }
        val project = projectDao.findById(projectId)
            ?: return@withContext RenameProjectEntryResult.NotFound
        val rootPath = project.path ?: return@withContext RenameProjectEntryResult.NotFound
        if (!storageAccess.hasAccess()) {
            return@withContext RenameProjectEntryResult.Failure(EntryFailure.PERMISSION_DENIED)
        }
        val absolute = try {
            resolveProjectPath(rootPath, path)
        } catch (error: Exception) {
            return@withContext RenameProjectEntryResult.Failure(
                EntryFailure.INVALID_PATH,
                error.message
            )
        }
        val parentRelative = path.substringBeforeLast('/', "")
        val newRelative = joinPath(parentRelative, normalized)
        try {
            val info = fileSystem.getFileInfo(absolute)
                ?: return@withContext RenameProjectEntryResult.NotFound
            if (info.name == normalized) {
                return@withContext RenameProjectEntryResult.Success(info.toEntry(path))
            }
            val target = resolveProjectPath(rootPath, newRelative)
            if (fileSystem.exists(target)) {
                return@withContext RenameProjectEntryResult.DuplicateName
            }
            val renamed = fileSystem.renameFile(absolute, normalized)
            RenameProjectEntryResult.Success(renamed.toEntry(newRelative))
        } catch (error: SecurityException) {
            RenameProjectEntryResult.Failure(EntryFailure.PERMISSION_DENIED, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "renameProjectEntry failed for $path", error)
            RenameProjectEntryResult.Failure(EntryFailure.IO, error.message)
        }
    }

    override suspend fun deleteProjectEntry(
        projectId: Long,
        path: String
    ): DeleteProjectEntryResult = withContext(ioDispatcher) {
        if (path.isBlank()) {
            return@withContext DeleteProjectEntryResult.Failure(
                EntryFailure.INVALID_PATH,
                "The project root cannot be deleted from the file browser"
            )
        }
        val project = projectDao.findById(projectId)
            ?: return@withContext DeleteProjectEntryResult.Failure(EntryFailure.NOT_FOUND)
        val rootPath = project.path
            ?: return@withContext DeleteProjectEntryResult.Failure(EntryFailure.NOT_FOUND)
        if (!storageAccess.hasAccess()) {
            return@withContext DeleteProjectEntryResult.Failure(EntryFailure.PERMISSION_DENIED)
        }
        val absolute = try {
            resolveProjectPath(rootPath, path)
        } catch (error: Exception) {
            return@withContext DeleteProjectEntryResult.Failure(
                EntryFailure.INVALID_PATH,
                error.message
            )
        }
        try {
            val info = fileSystem.getFileInfo(absolute)
            // Already gone is not a failure for a delete request.
            if (info != null) {
                fileSystem.deleteFile(absolute, recursive = info.isDirectory)
            }
            DeleteProjectEntryResult.Success
        } catch (error: SecurityException) {
            DeleteProjectEntryResult.Failure(EntryFailure.PERMISSION_DENIED, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "deleteProjectEntry failed for $path", error)
            DeleteProjectEntryResult.Failure(EntryFailure.IO, error.message)
        }
    }

    override suspend fun countProjectEntries(
        projectId: Long,
        path: String,
        limit: Int
    ): Int? = withContext(ioDispatcher) {
        val project = projectDao.findById(projectId) ?: return@withContext null
        val rootPath = project.path ?: return@withContext null
        if (!storageAccess.hasAccess()) return@withContext null
        val absolute = try {
            resolveProjectPath(rootPath, path)
        } catch (error: Exception) {
            return@withContext null
        }
        runCatching { fileSystem.countEntries(absolute, limit) }.getOrNull()
    }

    override suspend fun searchProjectFiles(
        projectId: Long,
        query: String,
        extensions: Set<String>,
        limit: Int
    ): SearchProjectFilesResult = withContext(ioDispatcher) {
        if (query.isBlank()) return@withContext SearchProjectFilesResult.Success(emptyList())
        val project = projectDao.findById(projectId)
            ?: return@withContext SearchProjectFilesResult.Failure(ProjectFileError.NOT_FOUND)
        val rootPath = project.path
            ?: return@withContext SearchProjectFilesResult.Failure(ProjectFileError.NOT_FOUND)
        if (!storageAccess.hasAccess()) {
            return@withContext SearchProjectFilesResult.Failure(ProjectFileError.PERMISSION_DENIED)
        }
        val absoluteRoot = try {
            resolveProjectPath(rootPath, "")
        } catch (error: Exception) {
            return@withContext SearchProjectFilesResult.Failure(
                ProjectFileError.INVALID_PATH,
                error.message
            )
        }
        try {
            val matches = fileSystem.searchFiles(
                rootPath = absoluteRoot,
                query = query.trim(),
                limit = limit,
                extensions = extensions
            )
            SearchProjectFilesResult.Success(
                matches.map { info ->
                    info.toEntry(info.path.removePrefix(absoluteRoot + File.separator))
                }
            )
        } catch (error: SecurityException) {
            SearchProjectFilesResult.Failure(ProjectFileError.PERMISSION_DENIED, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "searchProjectFiles failed for $absoluteRoot", error)
            SearchProjectFilesResult.Failure(ProjectFileError.IO, error.message)
        }
    }

    override suspend fun readProjectFile(
        projectId: Long,
        path: String
    ): ReadProjectFileResult = withContext(ioDispatcher) {
        if (path.isBlank()) {
            return@withContext ReadProjectFileResult.Failure(ProjectFileError.NOT_FOUND)
        }
        val project = projectDao.findById(projectId)
            ?: return@withContext ReadProjectFileResult.Failure(ProjectFileError.NOT_FOUND)
        val rootPath = project.path
            ?: return@withContext ReadProjectFileResult.Failure(ProjectFileError.NOT_FOUND)
        if (!storageAccess.hasAccess()) {
            return@withContext ReadProjectFileResult.Failure(ProjectFileError.PERMISSION_DENIED)
        }
        val absolute = try {
            resolveProjectPath(rootPath, path)
        } catch (error: Exception) {
            return@withContext ReadProjectFileResult.Failure(
                ProjectFileError.INVALID_PATH,
                error.message
            )
        }
        try {
            val info = fileSystem.getFileInfo(absolute)
                ?: return@withContext ReadProjectFileResult.Failure(ProjectFileError.NOT_FOUND)
            if (info.isDirectory) {
                return@withContext ReadProjectFileResult.Failure(ProjectFileError.NOT_A_FILE)
            }
            if (info.size > MAX_EDITABLE_FILE_BYTES) {
                return@withContext ReadProjectFileResult.Failure(
                    ProjectFileError.FILE_TOO_LARGE,
                    info.size.toString()
                )
            }
            val content = fileSystem.readFile(absolute)
            val actualSize = content.toByteArray(Charsets.UTF_8).size
            if (actualSize > MAX_EDITABLE_FILE_BYTES) {
                return@withContext ReadProjectFileResult.Failure(
                    ProjectFileError.FILE_TOO_LARGE,
                    actualSize.toString()
                )
            }
            if (content.indexOf('\u0000') >= 0) {
                return@withContext ReadProjectFileResult.Failure(ProjectFileError.BINARY)
            }
            ReadProjectFileResult.Success(
                content = content,
                size = actualSize.toLong(),
                lastModified = info.lastModified
            )
        } catch (error: SecurityException) {
            ReadProjectFileResult.Failure(ProjectFileError.PERMISSION_DENIED, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "readProjectFile failed for $absolute", error)
            ReadProjectFileResult.Failure(ProjectFileError.IO, error.message)
        }
    }

    override suspend fun writeProjectFile(
        projectId: Long,
        path: String,
        content: String
    ): WriteProjectFileResult = withContext(ioDispatcher) {
        if (path.isBlank()) {
            return@withContext WriteProjectFileResult.Failure(ProjectFileError.NOT_FOUND)
        }
        val project = projectDao.findById(projectId)
            ?: return@withContext WriteProjectFileResult.Failure(ProjectFileError.NOT_FOUND)
        val rootPath = project.path
            ?: return@withContext WriteProjectFileResult.Failure(ProjectFileError.NOT_FOUND)
        if (!storageAccess.hasAccess()) {
            return@withContext WriteProjectFileResult.Failure(ProjectFileError.PERMISSION_DENIED)
        }
        val absolute = try {
            resolveProjectPath(rootPath, path)
        } catch (error: Exception) {
            return@withContext WriteProjectFileResult.Failure(
                ProjectFileError.INVALID_PATH,
                error.message
            )
        }
        try {
            fileSystem.writeFile(absolute, content)
            WriteProjectFileResult.Success
        } catch (error: SecurityException) {
            WriteProjectFileResult.Failure(ProjectFileError.PERMISSION_DENIED, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "writeProjectFile failed for $absolute", error)
            WriteProjectFileResult.Failure(ProjectFileError.IO, error.message)
        }
    }

    override suspend fun saveProjectDocument(
        projectId: Long,
        path: String,
        content: String,
        append: Boolean
    ): SaveProjectDocumentResult = withContext(ioDispatcher) {
        if (path.isBlank()) {
            return@withContext SaveProjectDocumentResult.Failure(ProjectFileError.INVALID_PATH)
        }
        val project = projectDao.findById(projectId)
            ?: return@withContext SaveProjectDocumentResult.Failure(ProjectFileError.NOT_FOUND)
        val rootPath = project.path
            ?: return@withContext SaveProjectDocumentResult.Failure(ProjectFileError.NOT_FOUND)
        if (!storageAccess.hasAccess()) {
            return@withContext SaveProjectDocumentResult.Failure(
                ProjectFileError.PERMISSION_DENIED
            )
        }
        val absolute = try {
            resolveProjectPath(rootPath, path)
        } catch (error: Exception) {
            return@withContext SaveProjectDocumentResult.Failure(
                ProjectFileError.INVALID_PATH,
                error.message
            )
        }
        try {
            val target = File(absolute)
            val parent = target.parentFile
            if (parent != null && !parent.isDirectory) {
                fileSystem.createDirectory(parent.absolutePath)
            }
            when {
                append -> fileSystem.appendFile(absolute, content)
                target.isFile -> fileSystem.writeFile(absolute, content)
                else -> fileSystem.createFile(absolute, content, overwrite = true)
            }
            SaveProjectDocumentResult.Success
        } catch (error: SecurityException) {
            SaveProjectDocumentResult.Failure(ProjectFileError.PERMISSION_DENIED, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "saveProjectDocument failed for $absolute", error)
            SaveProjectDocumentResult.Failure(ProjectFileError.IO, error.message)
        }
    }

    override suspend fun getProjectFileInfo(
        projectId: Long,
        path: String
    ): ProjectFileInfoResult = withContext(ioDispatcher) {
        if (path.isBlank()) {
            return@withContext ProjectFileInfoResult.Failure(ProjectFileError.INVALID_PATH)
        }
        val project = projectDao.findById(projectId)
            ?: return@withContext ProjectFileInfoResult.Failure(ProjectFileError.NOT_FOUND)
        val rootPath = project.path
            ?: return@withContext ProjectFileInfoResult.Failure(ProjectFileError.NOT_FOUND)
        if (!storageAccess.hasAccess()) {
            return@withContext ProjectFileInfoResult.Failure(ProjectFileError.PERMISSION_DENIED)
        }
        val absolute = try {
            resolveProjectPath(rootPath, path)
        } catch (error: Exception) {
            return@withContext ProjectFileInfoResult.Failure(
                ProjectFileError.INVALID_PATH,
                error.message
            )
        }
        try {
            val info = fileSystem.getFileInfo(absolute)
                ?: return@withContext ProjectFileInfoResult.Failure(ProjectFileError.NOT_FOUND)
            ProjectFileInfoResult.Success(info.toEntry(path))
        } catch (error: SecurityException) {
            ProjectFileInfoResult.Failure(ProjectFileError.PERMISSION_DENIED, error.message)
        } catch (error: Exception) {
            Log.w(TAG, "getProjectFileInfo failed for $absolute", error)
            ProjectFileInfoResult.Failure(ProjectFileError.IO, error.message)
        }
    }

    /**
     * Type detection looks at the root plus one level of subdirectories, which
     * covers layouts like `src/Main.kt` without walking the whole tree.
     */
    private suspend fun collectDetectionNames(rootPath: String): List<String> {
        val names = mutableListOf<String>()
        val rootEntries = fileSystem.listFiles(rootPath)
        names += rootEntries.map { it.name }
        rootEntries.filter { it.isDirectory }
            .take(MAX_DETECTION_DIRECTORIES)
            .forEach { directory ->
                runCatching { fileSystem.listFiles(directory.path) }
                    .getOrDefault(emptyList())
                    .forEach { names += it.name }
            }
        return names
    }

    private suspend fun writeTemplate(projectPath: String, entries: List<TemplateEntry>) {
        entries.filter { it.isDirectory }
            .sortedBy { entry -> entry.path.count { it == '/' } }
            .forEach { entry ->
                val path = File(projectPath, entry.path).absolutePath
                if (!fileSystem.exists(path)) {
                    fileSystem.createDirectory(path)
                }
            }
        entries.filterNot { it.isDirectory }.forEach { entry ->
            val path = File(projectPath, entry.path).absolutePath
            if (!fileSystem.exists(path)) {
                fileSystem.createFile(path, entry.content.orEmpty())
            }
        }
    }

    /**
     * Resolves a project-relative path to an absolute one. Both the project root
     * and the result are canonicalized, so `..`, absolute paths and symlinks
     * cannot point outside the project folder.
     */
    private fun resolveProjectPath(rootPath: String, relativePath: String): String {
        val root = canonical(rootPath, "Invalid project path")
        if (relativePath.isBlank()) return root.path
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw NativeFileException("Absolute paths are not allowed: $relativePath")
        }
        val segments = relativePath.split('/', '\\').filter { it.isNotBlank() }
        if (segments.isEmpty()) return root.path
        if (segments.any { it == "." || it == ".." }) {
            throw NativeFileException("Invalid path: $relativePath")
        }
        val candidate = File(root, segments.joinToString(File.separator))
        val resolved = canonical(candidate.path, "Invalid path: $relativePath")
        if (resolved.path != root.path &&
            !resolved.path.startsWith(root.path + File.separator)
        ) {
            throw NativeFileException("Path escapes the project: $relativePath")
        }
        return resolved.path
    }

    private fun canonical(path: String, message: String): File = try {
        File(path).canonicalFile
    } catch (error: IOException) {
        throw NativeFileException(message, error)
    }

    private fun FileInfo.toEntry(relativePath: String): ProjectFileEntry = ProjectFileEntry(
        name = name,
        path = relativePath,
        isDirectory = isDirectory,
        size = size,
        lastModified = lastModified,
        canRead = canRead,
        canWrite = canWrite
    )

    /** Rejects volume roots such as /storage/emulated/0 as project folders. */
    private fun hasAllowedParent(path: String): Boolean {
        val parent = File(path).parentFile ?: return false
        return fileSystem.isWithinAllowedRoots(parent.absolutePath)
    }

    private companion object {
        const val TAG = "MobileIDE"
        const val MAX_DETECTION_DIRECTORIES = 8
    }
}

private fun joinPath(directory: String, name: String): String =
    if (directory.isBlank()) name else "$directory/$name"

private fun ProjectEntity.toDomain(): Project = Project(
    id = id,
    name = name,
    type = ProjectType.fromStorage(type),
    path = path,
    locationName = locationName,
    createdAt = createdAt,
    lastOpenedAt = lastOpenedAt,
    updatedAt = updatedAt
)
