package dev.mobileide.data.lsp

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.Properties

/** One provisioned language server, persisted in files/lsp-config/installed.properties. */
data class InstalledLspServer(
    val id: String,
    val version: String,
    val installedAt: Long,
    val path: String,
    val sizeBytes: Long,
    val packages: List<String> = emptyList()
)

/**
 * Records what was installed and when. Only the archive-based server has a
 * directory of its own; apt-installed binaries are tracked by package name and
 * detected again from the environment.
 */
class LspManifestStore(private val file: File) {

    fun load(): Map<String, InstalledLspServer> {
        if (!file.isFile) return emptyMap()
        val properties = Properties()
        runCatching {
            FileInputStream(file).use { properties.load(InputStreamReader(it, Charsets.UTF_8)) }
        }.getOrElse { return emptyMap() }

        val count = properties.getProperty("server.count")?.toIntOrNull() ?: 0
        val records = mutableMapOf<String, InstalledLspServer>()
        for (index in 1..count) {
            val prefix = "server.$index."
            val id = properties.getProperty(prefix + "id")?.trim().orEmpty()
            if (id.isEmpty()) continue
            records[id] = InstalledLspServer(
                id = id,
                version = properties.getProperty(prefix + "version").orEmpty(),
                installedAt = properties.getProperty(prefix + "installedAt")?.toLongOrNull() ?: 0L,
                path = properties.getProperty(prefix + "path").orEmpty(),
                sizeBytes = properties.getProperty(prefix + "size")?.toLongOrNull() ?: 0L,
                packages = properties.getProperty(prefix + "packages").orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            )
        }
        return records
    }

    fun put(record: InstalledLspServer) {
        val records = load().toMutableMap()
        records[record.id] = record
        save(records)
    }

    fun remove(id: String) {
        val records = load().toMutableMap()
        if (records.remove(id) != null) save(records)
    }

    private fun save(records: Map<String, InstalledLspServer>) {
        file.parentFile?.mkdirs()
        val properties = Properties()
        val ordered = records.values.sortedBy { it.id }
        properties.setProperty("server.count", ordered.size.toString())
        ordered.forEachIndexed { index, record ->
            val prefix = "server.${index + 1}."
            properties.setProperty(prefix + "id", record.id)
            properties.setProperty(prefix + "version", record.version)
            properties.setProperty(prefix + "installedAt", record.installedAt.toString())
            properties.setProperty(prefix + "path", record.path)
            properties.setProperty(prefix + "size", record.sizeBytes.toString())
            properties.setProperty(prefix + "packages", record.packages.joinToString(","))
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { output ->
            properties.store(output, "MobileIDE language servers")
            output.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
