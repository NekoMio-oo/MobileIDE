package dev.mobileide.domain.repository

/** A directory shown by the in-app folder picker. */
data class DirectoryEntry(
    val name: String,
    val path: String
)

enum class DirectoryValidation {
    VALID,
    NOT_FOUND,
    NOT_A_DIRECTORY,
    NOT_WRITABLE,
    OUTSIDE_STORAGE
}

sealed interface DirectoryListingResult {
    data class Success(
        val path: String,
        val directories: List<DirectoryEntry>
    ) : DirectoryListingResult

    data class Failure(
        val error: ProjectFileError,
        val detail: String? = null
    ) : DirectoryListingResult
}

/**
 * Directory browsing for the folder picker. It only exposes real paths under
 * shared storage and never starts a SAF picker.
 */
interface StorageBrowser {

    fun canGoUp(path: String): Boolean

    suspend fun listDirectories(path: String): DirectoryListingResult

    suspend fun validateProjectDirectory(path: String): DirectoryValidation
}
