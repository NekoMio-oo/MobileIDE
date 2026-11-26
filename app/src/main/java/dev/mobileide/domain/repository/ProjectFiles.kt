package dev.mobileide.domain.repository

import dev.mobileide.domain.model.Project
import dev.mobileide.domain.validation.EntryNameError

/** One entry shown by the in-project file browser. [path] is project-relative. */
data class ProjectFileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val canRead: Boolean = true,
    val canWrite: Boolean = true
)

enum class ProjectFileError {
    NOT_FOUND,
    NOT_A_FILE,
    PERMISSION_DENIED,
    FILE_TOO_LARGE,
    BINARY,
    INVALID_PATH,
    IO
}

/** Why a create, rename or delete of a single project entry failed. */
enum class EntryFailure {
    DUPLICATE,
    NOT_FOUND,
    NOT_A_DIRECTORY,
    PERMISSION_DENIED,
    INVALID_PATH,
    IO
}

sealed interface ListProjectFilesResult {
    data class Success(val entries: List<ProjectFileEntry>) : ListProjectFilesResult
    data class Failure(
        val error: ProjectFileError,
        val detail: String? = null
    ) : ListProjectFilesResult
}

sealed interface CreateProjectEntryResult {
    data class Success(val entry: ProjectFileEntry) : CreateProjectEntryResult
    data class InvalidName(val error: EntryNameError) : CreateProjectEntryResult
    data object DuplicateName : CreateProjectEntryResult
    data class Failure(
        val reason: EntryFailure,
        val detail: String? = null
    ) : CreateProjectEntryResult
}

sealed interface RenameProjectEntryResult {
    data class Success(val entry: ProjectFileEntry) : RenameProjectEntryResult
    data class InvalidName(val error: EntryNameError) : RenameProjectEntryResult
    data object DuplicateName : RenameProjectEntryResult
    data object NotFound : RenameProjectEntryResult
    data class Failure(
        val reason: EntryFailure,
        val detail: String? = null
    ) : RenameProjectEntryResult
}

sealed interface DeleteProjectEntryResult {
    data object Success : DeleteProjectEntryResult
    data class Failure(
        val reason: EntryFailure,
        val detail: String? = null
    ) : DeleteProjectEntryResult
}

sealed interface SearchProjectFilesResult {
    data class Success(val entries: List<ProjectFileEntry>) : SearchProjectFilesResult
    data class Failure(
        val error: ProjectFileError,
        val detail: String? = null
    ) : SearchProjectFilesResult
}

sealed interface ReadProjectFileResult {
    data class Success(
        val content: String,
        val size: Long,
        val lastModified: Long = 0L
    ) : ReadProjectFileResult

    data class Failure(
        val error: ProjectFileError,
        val detail: String? = null
    ) : ReadProjectFileResult
}

sealed interface ProjectFileInfoResult {
    data class Success(val entry: ProjectFileEntry) : ProjectFileInfoResult
    data class Failure(
        val error: ProjectFileError,
        val detail: String? = null
    ) : ProjectFileInfoResult
}

sealed interface WriteProjectFileResult {
    data object Success : WriteProjectFileResult
    data class Failure(
        val error: ProjectFileError,
        val detail: String? = null
    ) : WriteProjectFileResult
}

/**
 * Result of writing an app-managed document inside the project (build config,
 * task metadata, build logs). Unlike [WriteProjectFileResult] this may create
 * missing parent folders and append to an existing file.
 */
sealed interface SaveProjectDocumentResult {
    data object Success : SaveProjectDocumentResult
    data class Failure(
        val error: ProjectFileError,
        val detail: String? = null
    ) : SaveProjectDocumentResult
}

/** Files larger than this are refused instead of silently truncated. */
const val MAX_EDITABLE_FILE_BYTES: Long = 2L * 1024 * 1024

/** Keeps the editor boundary explicit; the file system only deals with paths. */
data class EditorTarget(val project: Project, val path: String)
