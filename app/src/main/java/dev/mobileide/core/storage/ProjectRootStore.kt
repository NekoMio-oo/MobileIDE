package dev.mobileide.core.storage

import dev.mobileide.core.filesystem.NativeFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fixed project root. The path is intentionally not user-editable in this
 * phase; it is created once storage access is granted.
 */
class ProjectRootStore(
    private val fileSystem: NativeFileSystem
) {

    @Suppress("DEPRECATION")
    fun rootPath(): String = File(
        android.os.Environment.getExternalStorageDirectory(),
        FOLDER_NAME
    ).absolutePath

    /** Creates the root when missing and verifies it is writable. */
    suspend fun ensureRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val path = rootPath()
            if (!fileSystem.exists(path)) {
                fileSystem.createDirectory(path)
            }
            val info = fileSystem.getFileInfo(path)
            info?.isDirectory == true && info.canWrite
        }.getOrDefault(false)
    }

    companion object {
        const val FOLDER_NAME = "AndroidProjects"
    }
}
