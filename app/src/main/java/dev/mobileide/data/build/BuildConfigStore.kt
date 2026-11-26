package dev.mobileide.data.build

import dev.mobileide.domain.build.BuildConfig
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildMode
import dev.mobileide.domain.repository.ProjectRepository
import dev.mobileide.domain.repository.ReadProjectFileResult
import dev.mobileide.domain.repository.SaveProjectDocumentResult
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * Build configuration persisted as `.mobileide/build.properties` inside the
 * project. It travels with the project folder, shows up in the file browser and
 * survives deleting the `build/` output folder.
 */
class BuildConfigStore(private val repository: ProjectRepository) {

    suspend fun load(projectId: Long): BuildConfig? {
        val result = runCatching { repository.readProjectFile(projectId, BuildPaths.CONFIG_FILE) }
            .getOrNull()
        val content = (result as? ReadProjectFileResult.Success)?.content ?: return null
        return runCatching { BuildConfigCodec.parse(content) }.getOrNull()
    }

    suspend fun save(projectId: Long, config: BuildConfig): SaveProjectDocumentResult =
        repository.saveProjectDocument(
            projectId = projectId,
            path = BuildPaths.CONFIG_FILE,
            content = BuildConfigCodec.encode(config)
        )
}

/** Properties encoding of [BuildConfig]; kept separate so it can be tested alone. */
internal object BuildConfigCodec {

    fun parse(content: String): BuildConfig {
        val properties = Properties().apply { load(StringReader(content)) }
        fun text(key: String): String = properties.getProperty(key).orEmpty()
        fun number(key: String): Int = text(key).toIntOrNull() ?: 0
        fun list(key: String): List<String> =
            (1..number("$key.count")).mapNotNull { index ->
                properties.getProperty("$key.$index")?.takeIf { it.isNotBlank() }
            }
        return BuildConfig(
            language = BuildLanguage.fromId(text("language")) ?: BuildLanguage.C,
            mode = if (text("mode").equals("RELEASE", true)) BuildMode.RELEASE else BuildMode.DEBUG,
            compiler = text("compiler"),
            standard = text("standard"),
            sourcesAuto = text("sources.auto").ifBlank { "true" }.toBoolean(),
            sources = list("source"),
            entryFile = text("entry").takeIf { it.isNotBlank() },
            outputName = text("output").ifBlank { "app" },
            includeDirs = list("include"),
            libraryDirs = list("libdir"),
            libraries = list("lib"),
            compileFlags = list("cflag"),
            linkFlags = list("lflag"),
            classpath = list("classpath"),
            target = text("target")
        )
    }

    fun encode(config: BuildConfig): String {
        val properties = Properties()
        properties["version"] = "1"
        properties["language"] = config.language.name
        properties["mode"] = config.mode.name
        properties["compiler"] = config.compiler
        properties["standard"] = config.standard
        properties["sources.auto"] = config.sourcesAuto.toString()
        properties["entry"] = config.entryFile.orEmpty()
        properties["output"] = config.outputName
        properties["target"] = config.target
        putList(properties, "source", config.sources)
        putList(properties, "include", config.includeDirs)
        putList(properties, "libdir", config.libraryDirs)
        putList(properties, "lib", config.libraries)
        putList(properties, "cflag", config.compileFlags)
        putList(properties, "lflag", config.linkFlags)
        putList(properties, "classpath", config.classpath)

        val writer = StringWriter()
        properties.store(writer, null)
        return writer.toString()
    }

    private fun putList(properties: Properties, key: String, values: List<String>) {
        properties["$key.count"] = values.size.toString()
        values.forEachIndexed { index, value ->
            properties["$key.${index + 1}"] = value
        }
    }
}
