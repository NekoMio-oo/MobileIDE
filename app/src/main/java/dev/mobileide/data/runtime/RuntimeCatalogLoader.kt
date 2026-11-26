package dev.mobileide.data.runtime

import dev.mobileide.domain.runtime.LanguageSpec
import dev.mobileide.domain.runtime.LinuxEnvironmentSpec
import dev.mobileide.domain.runtime.RemoteFile
import dev.mobileide.domain.runtime.RuntimeCatalog
import dev.mobileide.domain.runtime.RuntimeId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Properties

/**
 * Loads the runtime catalog. Assets provide the defaults; a user-editable copy
 * at files/runtime-config/catalog.properties wins when present.
 */
class RuntimeCatalogLoader(
    private val openDefault: () -> InputStream?,
    private val overrideFile: File? = null
) {

    suspend fun load(): RuntimeCatalog = withContext(Dispatchers.IO) {
        val properties = Properties()
        val override = overrideFile?.takeIf { it.isFile }
        if (override != null) {
            FileInputStream(override).use { properties.load(InputStreamReader(it, Charsets.UTF_8)) }
        } else {
            openDefault()?.use { properties.load(InputStreamReader(it, Charsets.UTF_8)) }
        }
        parse(properties)
    }

    fun parse(properties: Properties): RuntimeCatalog {
        val languages = buildList {
            val count = properties.int("language.count") ?: 0
            for (index in 1..count) {
                val id = properties.getProperty("language.$index.id")
                    ?.let { runCatching { RuntimeId.valueOf(it) }.getOrNull() }
                    ?: continue
                val name = properties.getProperty("language.$index.name") ?: id.name
                val tool = properties.getProperty("language.$index.tool").orEmpty()
                val versionArgs = properties.getProperty("language.$index.versionArgs")
                    .orEmpty()
                    .split(' ')
                    .filter { it.isNotBlank() }
                val apt = properties.getProperty("language.$index.apt")
                    .orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                val unsupported = properties.getProperty("language.$index.unsupported")
                    ?.takeIf { it.isNotBlank() }
                add(
                    LanguageSpec(
                        id = id,
                        displayName = name,
                        toolName = tool,
                        versionArgs = versionArgs,
                        aptPackages = apt,
                        unsupportedReason = unsupported
                    )
                )
            }
        }
        return RuntimeCatalog(
            version = properties.int("version") ?: 1,
            linux = parseLinux(properties),
            languages = languages
        )
    }

    private fun parseLinux(properties: Properties): LinuxEnvironmentSpec? {
        val rootfs = properties.remote("linux.rootfs") ?: return null
        val proot = properties.remote("linux.proot") ?: return null
        val libCount = properties.int("linux.prootlib.count") ?: 0
        val libs = (1..libCount).mapNotNull { properties.remote("linux.prootlib.$it") }
        return LinuxEnvironmentSpec(
            name = properties.getProperty("linux.name") ?: "Debian",
            version = properties.getProperty("linux.version") ?: "unknown",
            rootfs = rootfs,
            proot = proot,
            prootLibs = libs,
            rootfsStripComponents = properties.int("linux.rootfs.strip") ?: 0
        )
    }

    private fun Properties.remote(prefix: String): RemoteFile? {
        val url = getProperty("$prefix.url")?.takeIf { it.isNotBlank() } ?: return null
        val sha = getProperty("$prefix.sha256")?.trim()?.lowercase().orEmpty()
        if (sha.length != 64) return null
        val fileName = getProperty("$prefix.filename")?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/')
        return RemoteFile(
            url = url,
            sha256 = sha,
            fileName = fileName,
            sizeBytes = int("$prefix.size")?.toLong()
        )
    }

    private fun Properties.int(key: String): Int? =
        getProperty(key)?.toIntOrNull()
}
