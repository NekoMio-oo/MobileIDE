package dev.mobileide.data.build

import dev.mobileide.domain.build.BuildArtifact
import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.build.BuildMode
import dev.mobileide.domain.build.BuildState
import dev.mobileide.domain.build.BuildTask
import dev.mobileide.domain.repository.ListProjectFilesResult
import dev.mobileide.domain.repository.ProjectRepository
import dev.mobileide.domain.repository.ReadProjectFileResult
import dev.mobileide.domain.repository.SaveProjectDocumentResult
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * Persists one metadata file per build under `.mobileide/tasks` and keeps the
 * full log under `.mobileide/logs`. History survives an app restart and is the
 * source of truth for "interrupted" detection.
 */
class BuildTaskStore(private val repository: ProjectRepository) {

    suspend fun save(projectId: Long, task: BuildTask): Boolean =
        repository.saveProjectDocument(
            projectId = projectId,
            path = BuildPaths.taskPath(task.id),
            content = BuildTaskCodec.encode(task)
        ) is SaveProjectDocumentResult.Success

    suspend fun load(projectId: Long, taskId: String): BuildTask? {
        val sanitized = BuildPaths.sanitizeRelative(taskId) ?: return null
        if (sanitized.contains('/')) return null
        val result = runCatching {
            repository.readProjectFile(projectId, BuildPaths.taskPath(sanitized))
        }.getOrNull()
        val content = (result as? ReadProjectFileResult.Success)?.content ?: return null
        return runCatching { BuildTaskCodec.decode(content) }.getOrNull()
    }

    suspend fun history(projectId: Long, limit: Int = 30): List<BuildTask> {
        val listing = repository.listProjectFiles(projectId, BuildPaths.TASK_DIR)
        val entries = (listing as? ListProjectFilesResult.Success)?.entries ?: return emptyList()
        return entries
            .filter { !it.isDirectory && it.name.endsWith(".properties") }
            .sortedByDescending { it.name }
            .take(limit * 2)
            .mapNotNull { entry -> load(projectId, entry.name.removeSuffix(".properties")) }
            .sortedByDescending { it.startedAt }
            .take(limit)
    }

    suspend fun appendLog(projectId: Long, taskId: String, text: String): Boolean {
        if (text.isEmpty()) return true
        return repository.saveProjectDocument(
            projectId = projectId,
            path = BuildPaths.logPath(taskId),
            content = text,
            append = true
        ) is SaveProjectDocumentResult.Success
    }

    suspend fun readLog(projectId: Long, taskId: String): String? {
        val sanitized = BuildPaths.sanitizeRelative(taskId) ?: return null
        if (sanitized.contains('/')) return null
        val result = runCatching {
            repository.readProjectFile(projectId, BuildPaths.logPath(sanitized))
        }.getOrNull()
        return (result as? ReadProjectFileResult.Success)?.content
    }
}

/** Properties encoding of [BuildTask]; separated for testability. */
internal object BuildTaskCodec {

    fun encode(task: BuildTask): String {
        val properties = Properties()
        properties["version"] = "1"
        properties["id"] = task.id
        properties["language"] = task.language.name
        properties["mode"] = task.mode.name
        properties["state"] = task.state.name
        properties["startedAt"] = task.startedAt.toString()
        task.finishedAt?.let { properties["finishedAt"] = it.toString() }
        task.durationMs?.let { properties["durationMs"] = it.toString() }
        task.exitCode?.let { properties["exitCode"] = it.toString() }
        properties["errorCount"] = task.errorCount.toString()
        properties["warningCount"] = task.warningCount.toString()
        properties["output"] = task.outputPath.orEmpty()
        properties["failure"] = task.failureMessage.orEmpty()
        properties["artifact.count"] = task.artifacts.size.toString()
        task.artifacts.forEachIndexed { index, artifact ->
            val prefix = "artifact.${index + 1}"
            properties["$prefix.path"] = artifact.path
            properties["$prefix.name"] = artifact.name
            properties["$prefix.size"] = artifact.sizeBytes.toString()
            properties["$prefix.modified"] = artifact.modifiedAt.toString()
        }
        val writer = StringWriter()
        properties.store(writer, null)
        return writer.toString()
    }

    fun decode(content: String): BuildTask {
        val properties = Properties().apply { load(StringReader(content)) }
        fun text(key: String): String = properties.getProperty(key).orEmpty()
        val count = text("artifact.count").toIntOrNull() ?: 0
        val artifacts = (1..count).mapNotNull { index ->
            val prefix = "artifact.$index"
            val path = text("$prefix.path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BuildArtifact(
                path = path,
                name = text("$prefix.name").ifBlank { path.substringAfterLast('/') },
                sizeBytes = text("$prefix.size").toLongOrNull() ?: 0L,
                modifiedAt = text("$prefix.modified").toLongOrNull() ?: 0L
            )
        }
        return BuildTask(
            id = text("id"),
            projectId = 0L,
            language = BuildLanguage.fromId(text("language")) ?: BuildLanguage.C,
            mode = if (text("mode").equals("RELEASE", true)) BuildMode.RELEASE else BuildMode.DEBUG,
            state = runCatching { BuildState.valueOf(text("state")) }.getOrDefault(BuildState.FAILED),
            startedAt = text("startedAt").toLongOrNull() ?: 0L,
            finishedAt = text("finishedAt").toLongOrNull(),
            durationMs = text("durationMs").toLongOrNull(),
            exitCode = text("exitCode").toIntOrNull(),
            errorCount = text("errorCount").toIntOrNull() ?: 0,
            warningCount = text("warningCount").toIntOrNull() ?: 0,
            artifacts = artifacts,
            outputPath = text("output").takeIf { it.isNotBlank() },
            failureMessage = text("failure").takeIf { it.isNotBlank() }
        )
    }
}
