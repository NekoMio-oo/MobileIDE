package dev.mobileide.data.repository

import dev.mobileide.core.filesystem.NativeFileSystem
import dev.mobileide.domain.repository.DirectoryEntry
import dev.mobileide.domain.repository.DirectoryListingResult
import dev.mobileide.domain.repository.DirectoryValidation
import dev.mobileide.domain.repository.ProjectFileError
import dev.mobileide.domain.repository.StorageBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class StorageBrowserImpl(
    private val fileSystem: NativeFileSystem
) : StorageBrowser {

    override fun canGoUp(path: String): Boolean {
        val parent = File(path).parentFile ?: return false
        return fileSystem.isWithinAllowedRoots(parent.absolutePath)
    }

    override suspend fun listDirectories(path: String): DirectoryListingResult =
        withContext(Dispatchers.IO) {
            if (!fileSystem.isWithinAllowedRoots(path)) {
                return@withContext DirectoryListingResult.Failure(
                    ProjectFileError.PERMISSION_DENIED,
                    "Path is outside shared storage"
                )
            }
            try {
                val directories = fileSystem.listDirectories(path)
                    .filterNot { it.name.startsWith(".") }
                    .map { DirectoryEntry(name = it.name, path = it.path) }
                DirectoryListingResult.Success(path = path, directories = directories)
            } catch (error: SecurityException) {
                DirectoryListingResult.Failure(ProjectFileError.PERMISSION_DENIED, error.message)
            } catch (error: Exception) {
                DirectoryListingResult.Failure(ProjectFileError.IO, error.message)
            }
        }

    override suspend fun validateProjectDirectory(path: String): DirectoryValidation =
        withContext(Dispatchers.IO) {
            if (!fileSystem.isWithinAllowedRoots(path)) {
                return@withContext DirectoryValidation.OUTSIDE_STORAGE
            }
            // The storage root itself is not a project; require a parent that
            // is also inside shared storage so volume roots are rejected.
            val parent = File(path).parentFile
            if (parent == null || !fileSystem.isWithinAllowedRoots(parent.absolutePath)) {
                return@withContext DirectoryValidation.OUTSIDE_STORAGE
            }
            val info = try {
                fileSystem.getFileInfo(path)
            } catch (error: Exception) {
                return@withContext DirectoryValidation.OUTSIDE_STORAGE
            } ?: return@withContext DirectoryValidation.NOT_FOUND

            when {
                !info.isDirectory -> DirectoryValidation.NOT_A_DIRECTORY
                !info.canRead || !info.canWrite -> DirectoryValidation.NOT_WRITABLE
                else -> DirectoryValidation.VALID
            }
        }
}
