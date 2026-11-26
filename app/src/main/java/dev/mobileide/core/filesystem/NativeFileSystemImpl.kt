package dev.mobileide.core.filesystem

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * java.io / java.nio implementation of [NativeFileSystem].
 *
 * [allowedRoots] are the shared-storage volumes the app may touch (primary
 * storage plus removable volumes). Paths are canonicalized before every check,
 * so symlinks and `..` cannot escape the roots, and Android/data|obb stay
 * inaccessible.
 */
class NativeFileSystemImpl(
    allowedRoots: List<String>
) : NativeFileSystem {

    private val roots: List<String> = allowedRoots
        .map { File(it).canonicalFile.absolutePath }
        .distinct()

    override fun isWithinAllowedRoots(path: String): Boolean =
        runCatching { guard(path) }.isSuccess

    override suspend fun listFiles(directoryPath: String): List<FileInfo> =
        withContext(Dispatchers.IO) {
            val directory = guard(directoryPath)
            if (!directory.isDirectory) {
                throw NativeFileException("Not a directory: $directoryPath")
            }
            directory.listFiles()
                ?.map { it.toInfo() }
                ?.sortedWith(compareByDescending<FileInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
                ?: throw NativeFileException("Could not read directory: $directoryPath")
        }

    override suspend fun listDirectories(directoryPath: String): List<FileInfo> =
        withContext(Dispatchers.IO) {
            listFiles(directoryPath).filter { it.isDirectory }
        }

    override suspend fun createFile(
        path: String,
        content: String,
        overwrite: Boolean
    ): FileInfo = withContext(Dispatchers.IO) {
        val file = guard(path)
        if (file.exists() && !overwrite) {
            throw NativeFileException("Already exists: ${file.name}")
        }
        val parent = file.parentFile
            ?: throw NativeFileException("Missing parent directory: $path")
        if (!parent.isDirectory) {
            throw NativeFileException("Parent is not a directory: ${parent.name}")
        }
        writeAtomically(file, content)
        file.toInfo()
    }

    override suspend fun createDirectory(path: String): FileInfo = withContext(Dispatchers.IO) {
        val directory = guard(path)
        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw NativeFileException("A file with this name already exists: ${directory.name}")
            }
            return@withContext directory.toInfo()
        }
        if (!directory.mkdirs()) {
            throw NativeFileException("Could not create directory: ${directory.name}")
        }
        directory.toInfo()
    }

    override suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        val file = guard(path)
        if (!file.isFile) throw NativeFileException("File not found: $path")
        try {
            file.readText(Charsets.UTF_8)
        } catch (error: IOException) {
            throw NativeFileException("Could not read ${file.name}: ${error.message}", error)
        }
    }

    override suspend fun writeFile(path: String, content: String) = withContext(Dispatchers.IO) {
        val file = guard(path)
        if (!file.isFile) throw NativeFileException("File not found: $path")
        writeAtomically(file, content)
    }

    override suspend fun appendFile(path: String, content: String) =
        withContext(Dispatchers.IO) {
            val file = guard(path)
            if (file.isDirectory) throw NativeFileException("Not a file: $path")
            file.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw NativeFileException("Could not create ${parent.absolutePath}")
                }
            }
            try {
                FileOutputStream(file, true).use { stream ->
                    stream.write(content.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            } catch (error: IOException) {
                throw NativeFileException("Could not append to $path", error)
            }
        }

    override suspend fun deleteFile(path: String, recursive: Boolean) =
        withContext(Dispatchers.IO) {
            val file = guard(path)
            if (roots.any { it == file.absolutePath }) {
                throw NativeFileException("Refusing to delete a storage root")
            }
            if (!file.exists()) return@withContext
            if (file.isDirectory && !recursive) {
                throw NativeFileException("Directory deletion requires recursive=true")
            }
            if (file.isDirectory) {
                deleteTree(file)
            } else {
                if (!file.delete() && file.exists()) {
                    throw NativeFileException("Could not delete ${file.name}")
                }
            }
        }

    /**
     * Deletes bottom-up without following symbolic links, so a link inside the
     * tree can never make us delete content outside it.
     */
    private fun deleteTree(file: File) {
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        if (file.isDirectory) {
            file.listFiles()?.forEach(::deleteTree)
        }
        if (!file.delete() && file.exists()) {
            throw NativeFileException("Could not delete ${file.name}")
        }
    }

    override suspend fun renameFile(path: String, newName: String): FileInfo =
        withContext(Dispatchers.IO) {
            val source = guard(path)
            if (!source.exists()) throw NativeFileException("File not found: $path")
            if (newName.isBlank() ||
                newName.contains('/') ||
                newName.contains('\\') ||
                newName == "." ||
                newName == ".."
            ) {
                throw NativeFileException("Invalid name: $newName")
            }
            val parent = source.parentFile
                ?: throw NativeFileException("Cannot rename a storage root")
            val target = guard(File(parent, newName).absolutePath)
            if (target.exists()) {
                throw NativeFileException("$newName already exists")
            }
            if (!source.renameTo(target)) {
                throw NativeFileException("Could not rename ${source.name}")
            }
            target.toInfo()
        }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        guard(path).exists()
    }

    override suspend fun getFileInfo(path: String): FileInfo? = withContext(Dispatchers.IO) {
        val file = guard(path)
        if (file.exists()) file.toInfo() else null
    }

    override suspend fun searchFiles(
        rootPath: String,
        query: String,
        limit: Int,
        extensions: Set<String>,
        maxDepth: Int
    ): List<FileInfo> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val root = guard(rootPath)
        if (!root.isDirectory) throw NativeFileException("Not a directory: $rootPath")

        val extensionFilter = extensions
            .map { it.lowercase().removePrefix(".") }
            .filter { it.isNotBlank() }
            .toSet()
        val results = mutableListOf<FileInfo>()
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && results.size < limit && visited < MAX_SEARCH_ENTRIES) {
            currentCoroutineContext().ensureActive()
            val (directory, depth) = queue.removeFirst()
            val children = directory.listFiles() ?: continue
            for (child in children) {
                if (results.size >= limit || visited >= MAX_SEARCH_ENTRIES) break
                visited++
                if (Files.isSymbolicLink(child.toPath())) continue

                val isDirectory = child.isDirectory
                val extension = if (isDirectory) {
                    ""
                } else {
                    child.name.substringAfterLast('.', "").lowercase()
                }
                val matchesType = extensionFilter.isEmpty() ||
                    (!isDirectory && extension in extensionFilter)

                if (matchesType && child.name.contains(query.trim(), ignoreCase = true)) {
                    results += child.toInfo()
                    if (results.size >= limit) break
                }

                if (isDirectory &&
                    depth < maxDepth &&
                    !child.name.startsWith(".") &&
                    child.name != "node_modules"
                ) {
                    queue.add(child to depth + 1)
                }
            }
        }
        results.sortedWith(
            compareByDescending<FileInfo> { it.isDirectory }.thenBy { it.path.lowercase() }
        )
    }

    override suspend fun countEntries(rootPath: String, limit: Int): Int =
        withContext(Dispatchers.IO) {
            val root = guard(rootPath)
            if (!root.exists()) return@withContext 0
            if (!root.isDirectory) return@withContext 1

            var count = 0
            val queue = ArrayDeque<File>()
            queue.add(root)
            var visited = 0

            while (queue.isNotEmpty() && count < limit && visited < MAX_COUNT_ENTRIES) {
                currentCoroutineContext().ensureActive()
                val directory = queue.removeFirst()
                val children = directory.listFiles() ?: continue
                for (child in children) {
                    if (count >= limit || visited >= MAX_COUNT_ENTRIES) break
                    visited++
                    if (Files.isSymbolicLink(child.toPath())) continue
                    count++
                    if (child.isDirectory) queue.add(child)
                }
            }
            count
        }

    private fun guard(path: String): File {
        if (path.isBlank()) throw NativeFileException("Empty path")
        val file = File(path)
        if (!file.isAbsolute) throw NativeFileException("Path must be absolute: $path")

        val canonical = try {
            file.canonicalFile
        } catch (error: IOException) {
            throw NativeFileException("Invalid path: $path", error)
        }
        val absolute = canonical.absolutePath
        if (absolute == File.separator) throw NativeFileException("Refusing to use the filesystem root")
        if (isRestricted(absolute)) {
            throw NativeFileException("Access to Android/data and Android/obb is not supported")
        }
        val inRoot = roots.any { root ->
            absolute == root || absolute.startsWith(root + File.separator)
        }
        if (!inRoot) {
            throw NativeFileException("Path is outside shared storage: $path")
        }
        return canonical
    }

    private fun isRestricted(absolutePath: String): Boolean {
        val normalized = "/" + absolutePath.trim('/') + "/"
        return normalized.contains("/Android/data/") || normalized.contains("/Android/obb/")
    }

    private fun writeAtomically(target: File, content: String) {
        val parent = target.parentFile
            ?: throw NativeFileException("Missing parent directory: ${target.absolutePath}")
        if (!parent.isDirectory) {
            throw NativeFileException("Parent is not a directory: ${parent.name}")
        }
        val temp = File.createTempFile(".${target.name}.", ".tmp", parent)
        try {
            temp.writeText(content, Charsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (error: AtomicMoveNotSupportedException) {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } catch (error: IOException) {
            throw NativeFileException("Could not write ${target.name}: ${error.message}", error)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun File.toInfo(): FileInfo = FileInfo(
        name = name,
        path = absolutePath,
        isDirectory = isDirectory,
        size = if (isDirectory) 0L else length(),
        lastModified = lastModified(),
        canRead = canRead(),
        canWrite = canWrite()
    )

    private companion object {
        const val MAX_SEARCH_ENTRIES = 12_000
        const val MAX_COUNT_ENTRIES = 20_000
    }
}
