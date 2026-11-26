package dev.mobileide.domain.repository

import dev.mobileide.domain.model.Project
import dev.mobileide.domain.model.ProjectType
import dev.mobileide.domain.validation.ProjectNameError
import kotlinx.coroutines.flow.Flow

enum class ProjectFailure {
    PERMISSION_DENIED,
    NOT_FOUND,
    INVALID_PATH,
    IO
}

sealed interface CreateProjectResult {
    data class Success(val projectId: Long) : CreateProjectResult
    data class InvalidName(val error: ProjectNameError) : CreateProjectResult
    data object DuplicateName : CreateProjectResult
    data class Failure(val reason: ProjectFailure, val detail: String? = null) : CreateProjectResult
}

sealed interface OpenProjectResult {
    data class Success(val projectId: Long) : OpenProjectResult
    data class Failure(val reason: ProjectFailure, val detail: String? = null) : OpenProjectResult
}

/** Binds an old SAF-era record to a real directory. */
sealed interface RelinkProjectResult {
    data object Success : RelinkProjectResult
    data object NotFound : RelinkProjectResult
    data object AlreadyRegistered : RelinkProjectResult
    data class Failure(val reason: ProjectFailure, val detail: String? = null) : RelinkProjectResult
}

sealed interface RenameProjectResult {
    data object Success : RenameProjectResult
    data class InvalidName(val error: ProjectNameError) : RenameProjectResult
    data object NotFound : RenameProjectResult
    data class Failure(val reason: ProjectFailure, val detail: String? = null) : RenameProjectResult
}

sealed interface DeleteProjectResult {
    data object Success : DeleteProjectResult
    data class Failure(val reason: ProjectFailure, val detail: String? = null) : DeleteProjectResult
}

data class AccessStatus(
    val available: Boolean,
    val reason: ProjectFailure? = null
)

interface ProjectRepository {

    fun observeProjects(): Flow<List<Project>>

    fun observeRecentProjects(limit: Int): Flow<List<Project>>

    suspend fun getProject(projectId: Long): Project?

    suspend fun createProject(
        name: String,
        type: ProjectType,
        rootPath: String
    ): CreateProjectResult

    suspend fun openProject(path: String): OpenProjectResult

    suspend fun relinkProject(projectId: Long, path: String): RelinkProjectResult

    suspend fun markOpened(projectId: Long)

    suspend fun renameProject(projectId: Long, newName: String): RenameProjectResult

    /** Removes the database record only; user files are untouched. */
    suspend fun removeProject(projectId: Long): DeleteProjectResult

    /** Deletes the project folder on disk, then the database record. */
    suspend fun deleteProjectFiles(projectId: Long): DeleteProjectResult

    suspend fun checkAccess(projectId: Long): AccessStatus

    suspend fun listProjectFiles(
        projectId: Long,
        directoryPath: String = ""
    ): ListProjectFilesResult

    suspend fun createProjectEntry(
        projectId: Long,
        directoryPath: String,
        name: String,
        isDirectory: Boolean
    ): CreateProjectEntryResult

    suspend fun renameProjectEntry(
        projectId: Long,
        path: String,
        newName: String
    ): RenameProjectEntryResult

    /** Deletes a file or an entire folder; the caller must confirm with the user. */
    suspend fun deleteProjectEntry(
        projectId: Long,
        path: String
    ): DeleteProjectEntryResult

    /** Number of descendants of a folder, capped at [limit]. Null when unreadable. */
    suspend fun countProjectEntries(
        projectId: Long,
        path: String,
        limit: Int = 500
    ): Int?

    suspend fun searchProjectFiles(
        projectId: Long,
        query: String,
        extensions: Set<String> = emptySet(),
        limit: Int = 200
    ): SearchProjectFilesResult

    suspend fun readProjectFile(projectId: Long, path: String): ReadProjectFileResult

    /** Metadata for one entry; used for external-change checks before saving. */
    suspend fun getProjectFileInfo(
        projectId: Long,
        path: String
    ): ProjectFileInfoResult

    suspend fun writeProjectFile(
        projectId: Long,
        path: String,
        content: String
    ): WriteProjectFileResult

    /**
     * Writes an app-managed document (build config, task metadata or log) inside
     * the project. Creates missing parent folders; when [append] is true the
     * text is appended instead of replacing the file. Never used by the editor,
     * which must not recreate a deleted file.
     */
    suspend fun saveProjectDocument(
        projectId: Long,
        path: String,
        content: String,
        append: Boolean = false
    ): SaveProjectDocumentResult
}
