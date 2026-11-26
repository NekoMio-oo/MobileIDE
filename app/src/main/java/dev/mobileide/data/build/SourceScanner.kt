package dev.mobileide.data.build

import dev.mobileide.domain.build.BuildLanguage
import dev.mobileide.domain.repository.ListProjectFilesResult
import dev.mobileide.domain.repository.ProjectRepository

/**
 * Finds the source files of a project for one language. Output folders, hidden
 * folders and vendored trees are skipped so a build never compiles its own
 * artifacts or a `node_modules` dump.
 */
class SourceScanner(private val repository: ProjectRepository) {

    suspend fun scan(
        projectId: Long,
        language: BuildLanguage,
        maxFiles: Int = 400,
        maxDepth: Int = 8
    ): List<String> {
        val results = mutableListOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add("" to 0)
        var visited = 0

        while (queue.isNotEmpty() && results.size < maxFiles && visited < MAX_ENTRIES) {
            val (path, depth) = queue.removeFirst()
            val listing = repository.listProjectFiles(projectId, path)
            val entries = (listing as? ListProjectFilesResult.Success)?.entries ?: continue
            for (entry in entries) {
                visited++
                if (visited > MAX_ENTRIES) break
                if (entry.isDirectory) {
                    if (depth + 1 > maxDepth) continue
                    if (!isTraversable(entry.name)) continue
                    queue.add(entry.path to depth + 1)
                } else {
                    val extension = entry.name.substringAfterLast('.', "").lowercase()
                    if (extension in language.sourceExtensions) {
                        results += entry.path
                    }
                }
            }
        }
        return results.sorted()
    }

    private fun isTraversable(name: String): Boolean {
        if (name.startsWith(".")) return false
        return name !in SKIPPED_DIRECTORIES
    }

    private companion object {
        const val MAX_ENTRIES = 12_000
        val SKIPPED_DIRECTORIES = setOf("build", "node_modules", "vendor", "target", "out")
    }
}
