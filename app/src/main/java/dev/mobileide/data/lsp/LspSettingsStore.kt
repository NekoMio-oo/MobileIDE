package dev.mobileide.data.lsp

import dev.mobileide.domain.lsp.LspSettings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.Properties

/** Persisted preferences plus the per-server switches. */
data class LspStoredSettings(
    val settings: LspSettings = LspSettings(),
    val enabled: Map<String, Boolean> = emptyMap(),
    val extraArgs: Map<String, List<String>> = emptyMap()
) {
    fun isEnabled(serverId: String): Boolean = enabled[serverId] ?: true

    fun argsFor(serverId: String): List<String> = extraArgs[serverId].orEmpty()
}

/**
 * Simple properties file, consistent with the runtime and terminal settings.
 * Nothing security-sensitive is stored here: just switches and extra CLI flags.
 */
class LspSettingsStore(private val file: File) {

    fun load(): LspStoredSettings {
        if (!file.isFile) return LspStoredSettings()
        val properties = Properties()
        runCatching {
            FileInputStream(file).use { properties.load(InputStreamReader(it, Charsets.UTF_8)) }
        }.getOrElse { return LspStoredSettings() }

        val servers = properties.getProperty("server.ids").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val enabled = servers.associateWith { id ->
            properties.getProperty("server.$id.enabled")?.toBooleanStrictOrNull() ?: true
        }
        val args = servers.associateWith { id ->
            properties.getProperty("server.$id.args").orEmpty()
                .split('|')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }.filterValues { it.isNotEmpty() }

        return LspStoredSettings(
            settings = LspSettings(
                enabled = properties.getProperty("enabled")?.toBooleanStrictOrNull() ?: true,
                autoStart = properties.getProperty("autoStart")?.toBooleanStrictOrNull() ?: true,
                maxSessions = (properties.getProperty("maxSessions")?.toIntOrNull() ?: 2)
                    .coerceIn(1, 3)
            ),
            enabled = enabled,
            extraArgs = args
        )
    }

    fun save(value: LspStoredSettings) {
        file.parentFile?.mkdirs()
        val properties = Properties()
        properties.setProperty("enabled", value.settings.enabled.toString())
        properties.setProperty("autoStart", value.settings.autoStart.toString())
        properties.setProperty("maxSessions", value.settings.maxSessions.toString())
        properties.setProperty("server.ids", value.enabled.keys.joinToString(","))
        value.enabled.forEach { (id, enabled) ->
            properties.setProperty("server.$id.enabled", enabled.toString())
        }
        value.extraArgs.forEach { (id, args) ->
            properties.setProperty("server.$id.args", args.joinToString("|"))
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { output ->
            properties.store(output, "MobileIDE language intelligence")
            output.fd.sync()
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }
}
