package dev.mobileide.data.terminal

import dev.mobileide.domain.terminal.TerminalEnvironment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

data class TerminalSettings(
    val defaultEnvironment: TerminalEnvironment = TerminalEnvironment.ANDROID,
    val fontSizeSp: Int = 12,
    val maxSessions: Int = 3,
    val scrollbackLines: Int = 600
) {
    fun normalized(): TerminalSettings = copy(
        fontSizeSp = fontSizeSp.coerceIn(9, 24),
        maxSessions = maxSessions.coerceIn(1, 5),
        scrollbackLines = scrollbackLines.coerceIn(100, 5_000)
    )
}

/**
 * Terminal preferences in the app's private storage. Same Properties + atomic
 * replace pattern as the build configuration, without pulling in DataStore.
 */
class TerminalPreferences(private val file: File) {

    fun load(): TerminalSettings {
        if (!file.isFile) return TerminalSettings()
        return runCatching {
            val properties = Properties()
            FileInputStream(file).use { properties.load(InputStreamReader(it, Charsets.UTF_8)) }
            TerminalSettings(
                defaultEnvironment = runCatching {
                    TerminalEnvironment.valueOf(
                        properties.getProperty("environment") ?: TerminalEnvironment.ANDROID.name
                    )
                }.getOrDefault(TerminalEnvironment.ANDROID),
                fontSizeSp = properties.getProperty("fontSize")?.toIntOrNull() ?: 12,
                maxSessions = properties.getProperty("maxSessions")?.toIntOrNull() ?: 3,
                scrollbackLines = properties.getProperty("scrollback")?.toIntOrNull() ?: 600
            ).normalized()
        }.getOrDefault(TerminalSettings())
    }

    fun save(settings: TerminalSettings): TerminalSettings {
        val normalized = settings.normalized()
        runCatching {
            file.parentFile?.mkdirs()
            val properties = Properties().apply {
                setProperty("environment", normalized.defaultEnvironment.name)
                setProperty("fontSize", normalized.fontSizeSp.toString())
                setProperty("maxSessions", normalized.maxSessions.toString())
                setProperty("scrollback", normalized.scrollbackLines.toString())
            }
            val writer = StringWriter()
            properties.store(writer, null)
            val temporary = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temporary).use { it.write(writer.toString().toByteArray(Charsets.UTF_8)) }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            }.onFailure {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return normalized
    }

    /** Encoding helper shared with tests; keeps the keys in one place. */
    internal fun encode(settings: TerminalSettings): String {
        val properties = Properties().apply {
            setProperty("environment", settings.defaultEnvironment.name)
            setProperty("fontSize", settings.fontSizeSp.toString())
            setProperty("maxSessions", settings.maxSessions.toString())
            setProperty("scrollback", settings.scrollbackLines.toString())
        }
        val writer = StringWriter()
        properties.store(writer, null)
        return writer.toString()
    }

    internal fun decode(content: String): TerminalSettings {
        val properties = Properties().apply { load(StringReader(content)) }
        return TerminalSettings(
            defaultEnvironment = runCatching {
                TerminalEnvironment.valueOf(
                    properties.getProperty("environment") ?: TerminalEnvironment.ANDROID.name
                )
            }.getOrDefault(TerminalEnvironment.ANDROID),
            fontSizeSp = properties.getProperty("fontSize")?.toIntOrNull() ?: 12,
            maxSessions = properties.getProperty("maxSessions")?.toIntOrNull() ?: 3,
            scrollbackLines = properties.getProperty("scrollback")?.toIntOrNull() ?: 600
        ).normalized()
    }
}
