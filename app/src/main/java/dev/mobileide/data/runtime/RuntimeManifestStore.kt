package dev.mobileide.data.runtime

import dev.mobileide.domain.runtime.InstalledRuntimeRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties

/** Install records for runtimes the app has provisioned. */
class RuntimeManifestStore(private val file: File) {

    suspend fun read(): Map<String, InstalledRuntimeRecord> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyMap()
        val properties = Properties()
        try {
            FileInputStream(file).use { properties.load(it) }
        } catch (error: Exception) {
            return@withContext emptyMap()
        }
        properties.stringPropertyNames().mapNotNull { key ->
            parse(key, properties.getProperty(key))?.let { key to it }
        }.toMap()
    }

    suspend fun record(id: String): InstalledRuntimeRecord? =
        read()[id]

    suspend fun put(record: InstalledRuntimeRecord) {
        val current = read().toMutableMap()
        current[record.id] = record
        write(current)
    }

    suspend fun remove(id: String) {
        val current = read().toMutableMap()
        current.remove(id)
        write(current)
    }

    private suspend fun write(records: Map<String, InstalledRuntimeRecord>) =
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val properties = Properties()
            records.forEach { (id, record) ->
                properties.setProperty(id, encode(record))
            }
            val temp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temp).use { properties.store(it, "MobileIDE runtimes") }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (error: Exception) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

    private fun encode(record: InstalledRuntimeRecord): String {
        val path = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(record.path.toByteArray(Charsets.UTF_8))
        return listOf(
            record.version,
            record.installedAt.toString(),
            record.sha256,
            record.sizeBytes.toString(),
            path
        ).joinToString("|")
    }

    private fun parse(id: String, value: String?): InstalledRuntimeRecord? {
        val parts = value?.split('|') ?: return null
        if (parts.size != 5) return null
        return runCatching {
            InstalledRuntimeRecord(
                id = id,
                version = parts[0],
                installedAt = parts[1].toLong(),
                sha256 = parts[2],
                sizeBytes = parts[3].toLong(),
                path = String(
                    Base64.getUrlDecoder().decode(parts[4]),
                    Charsets.UTF_8
                )
            )
        }.getOrNull()
    }
}
