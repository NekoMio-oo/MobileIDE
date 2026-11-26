package dev.mobileide.data.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Extracts zip / tar / tar.gz / tar.xz trees with path-traversal protection.
 * Symbolic links are recreated as links (never followed while extracting), and
 * device/fifo entries are skipped because the app cannot create them.
 */
object ArchiveExtractor {

    fun extract(
        archive: File,
        targetDir: File,
        stripComponents: Int = 0,
        onEntry: (String) -> Unit = {}
    ) {
        val name = archive.name.lowercase()
        BufferedInputStream(FileInputStream(archive)).use { input ->
            when {
                name.endsWith(".zip") -> extractZip(input, targetDir, stripComponents, onEntry)

                name.endsWith(".tar.xz") || name.endsWith(".txz") ->
                    XZCompressorInputStream(input).use { tar(it, targetDir, stripComponents, onEntry) }

                name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                    GzipCompressorInputStream(input).use { tar(it, targetDir, stripComponents, onEntry) }

                name.endsWith(".tar") -> tar(input, targetDir, stripComponents, onEntry)

                else -> throw IOException("Unsupported archive: ${archive.name}")
            }
        }
    }

    private fun tar(
        stream: InputStream,
        targetDir: File,
        stripComponents: Int,
        onEntry: (String) -> Unit
    ) {
        targetDir.mkdirs()
        TarArchiveInputStream(stream).use { tar ->
            while (true) {
                val entry: TarArchiveEntry = tar.nextEntry ?: break
                if (!extractEntry(entry, targetDir, stripComponents, onEntry, tar)) continue
            }
        }
    }

    private fun extractEntry(
        entry: TarArchiveEntry,
        targetDir: File,
        stripComponents: Int,
        onEntry: (String) -> Unit,
        input: InputStream
    ): Boolean {
        val target = safeTarget(targetDir, entry.name, stripComponents) ?: return false
        onEntry(entry.name)

        when {
            entry.isDirectory -> {
                target.mkdirs()
                target.setExecutable(true, false)
                applyMode(target, entry.mode)
            }

            entry.isSymbolicLink -> {
                createSymlink(target, entry.linkName)
            }

            entry.isLink -> {
                val source = safeTarget(targetDir, entry.linkName, stripComponents)
                if (source != null && source.isFile) {
                    target.parentFile?.mkdirs()
                    if (target.exists()) target.delete()
                    runCatching { Files.createLink(target.toPath(), source.toPath()) }
                        .onFailure { source.copyTo(target, overwrite = true) }
                }
            }

            entry.isFile -> {
                target.parentFile?.mkdirs()
                target.outputStream().buffered().use { output -> input.copyTo(output) }
                applyMode(target, entry.mode)
            }

            // Character/block devices and FIFOs cannot be created by an app.
            else -> return false
        }
        return true
    }

    private fun extractZip(
        stream: InputStream,
        targetDir: File,
        stripComponents: Int,
        onEntry: (String) -> Unit
    ) {
        targetDir.mkdirs()
        ZipArchiveInputStream(stream).use { zip ->
            while (true) {
                val entry: ZipArchiveEntry = zip.nextEntry ?: break
                val target = safeTarget(targetDir, entry.name, stripComponents) ?: continue
                onEntry(entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                    target.setExecutable(true, false)
                    continue
                }
                if (entry.isUnixSymlink) {
                    // Create after the rest of the tree so the link target exists.
                    val linkTarget = runCatching {
                        val bytes = zip.readBytes()
                        if (bytes.size > 4_096) null else String(bytes, Charsets.UTF_8)
                    }.getOrNull()
                    if (linkTarget != null) createSymlink(target, linkTarget)
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().buffered().use { output -> zip.copyTo(output) }
                applyMode(target, entry.unixMode)
            }
        }
    }

    private fun createSymlink(target: File, linkName: String) {
        if (linkName.isBlank()) return
        target.parentFile?.mkdirs()
        runCatching {
            if (target.exists()) target.delete()
            Files.createSymbolicLink(target.toPath(), Paths.get(linkName))
        }
    }

    /**
     * Rejects absolute paths and `..`, and refuses to write through a symlinked
     * parent that escapes [targetDir].
     */
    private fun safeTarget(targetDir: File, rawName: String, stripComponents: Int): File? {
        val raw = rawName.replace('\\', '/')
        if (raw.startsWith("/")) return null
        val parts = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        val stripped = parts.drop(stripComponents)
        if (stripped.isEmpty()) return null

        val root = targetDir.canonicalFile
        val parent = File(root, stripped.dropLast(1).joinToString("/")).canonicalFile
        if (parent != root && !parent.path.startsWith(root.path + File.separator)) return null
        return File(parent, stripped.last())
    }

    private fun applyMode(file: File, mode: Int) {
        if (mode <= 0) {
            file.setReadable(true, true)
            file.setWritable(true, true)
            if (file.isDirectory) file.setExecutable(true, true)
            return
        }
        val owner = (mode shr 6) and 7
        // The runtime tree is private to the app; only owner bits are needed.
        file.setReadable(owner and 4 != 0, true)
        file.setWritable(owner and 2 != 0, true)
        file.setExecutable(owner and 1 != 0, true)
        if (file.isDirectory) file.setExecutable(true, true)
    }
}
