package dev.mobileide.core.filesystem

/** Metadata for a real filesystem entry. */
data class FileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val canRead: Boolean,
    val canWrite: Boolean
)

class NativeFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Real filesystem access. Every path is absolute and must live under one of the
 * shared-storage roots the app is allowed to touch. No content:// URIs.
 */
interface NativeFileSystem {

    fun isWithinAllowedRoots(path: String): Boolean

    suspend fun listFiles(directoryPath: String): List<FileInfo>

    suspend fun listDirectories(directoryPath: String): List<FileInfo>

    suspend fun createFile(path: String, content: String, overwrite: Boolean = false): FileInfo

    suspend fun createDirectory(path: String): FileInfo

    suspend fun readFile(path: String): String

    suspend fun writeFile(path: String, content: String)

    /** Appends UTF-8 text, creating the file (and parents) when missing. */
    suspend fun appendFile(path: String, content: String)

    /** Deletes a file, or a directory when [recursive] is true. Missing paths are a no-op. */
    suspend fun deleteFile(path: String, recursive: Boolean = false)

    suspend fun renameFile(path: String, newName: String): FileInfo

    suspend fun exists(path: String): Boolean

    suspend fun getFileInfo(path: String): FileInfo?

    /**
     * Recursive name search bounded by [limit] results and [maxDepth]. Hidden
     * directories, `node_modules` and symbolic links are not traversed.
     */
    suspend fun searchFiles(
        rootPath: String,
        query: String,
        limit: Int = 200,
        extensions: Set<String> = emptySet(),
        maxDepth: Int = DEFAULT_SEARCH_DEPTH
    ): List<FileInfo>

    /** Number of descendants of [rootPath], capped at [limit]. */
    suspend fun countEntries(rootPath: String, limit: Int = 500): Int

    companion object {
        const val DEFAULT_SEARCH_DEPTH = 12
    }
}
