package dev.mobileide.data.runtime

import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Extracts the `data.tar.*` payload of a Debian package (.deb is an ar archive
 * with debian-binary, control.tar.* and data.tar.* members).
 */
object DebExtractor {

    fun extractData(deb: File, targetDir: File, onEntry: (String) -> Unit = {}) {
        ArArchiveInputStream(BufferedInputStream(FileInputStream(deb))).use { ar ->
            while (true) {
                val entry = ar.nextEntry ?: break
                if (!entry.name.startsWith("data.tar")) continue

                val suffix = when {
                    entry.name.endsWith(".tar.xz") -> ".tar.xz"
                    entry.name.endsWith(".tar.gz") -> ".tar.gz"
                    else -> ".tar"
                }
                val temp = File.createTempFile("deb-", suffix, targetDir.parentFile)
                try {
                    FileOutputStream(temp).use { output -> ar.copyTo(output) }
                    ArchiveExtractor.extract(temp, targetDir, onEntry = onEntry)
                } finally {
                    temp.delete()
                }
                return
            }
        }
        throw IOException("No data.tar payload in ${deb.name}")
    }
}
