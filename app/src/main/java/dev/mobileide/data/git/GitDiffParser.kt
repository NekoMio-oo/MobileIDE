package dev.mobileide.data.git

import dev.mobileide.domain.git.GitDiff
import dev.mobileide.domain.git.GitDiffFile
import dev.mobileide.domain.git.GitDiffHunk
import dev.mobileide.domain.git.GitDiffLine
import dev.mobileide.domain.git.GitDiffLineKind

/**
 * Parser for one file's unified diff (`git diff --no-color --unified=3 -- path`).
 *
 * The command is always scoped to a single path, so the parser builds one file
 * object and uses the requested path instead of reconstructing it from the
 * `diff --git` header (which is ambiguous for paths containing spaces).
 *
 * `\ No newline at end of file` markers are omitted; line numbers stay exact for
 * every rendered line.
 */
object GitDiffParser {

    private val HUNK_HEADER =
        Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$")

    private const val MAX_LINES = 5_000

    fun parse(output: String, fallbackPath: String): GitDiff {
        var truncated = false
        var binary = false
        var oldPath: String? = null
        val hunks = mutableListOf<GitDiffHunk>()

        var header: String? = null
        var oldStart = 0
        var oldCount = 0
        var newStart = 0
        var newCount = 0
        var oldLine = 0
        var newLine = 0
        var renderedLines = 0
        val currentLines = mutableListOf<GitDiffLine>()

        fun finishHunk() {
            val currentHeader = header ?: return
            hunks += GitDiffHunk(
                header = currentHeader,
                oldStart = oldStart,
                oldCount = oldCount,
                newStart = newStart,
                newCount = newCount,
                lines = currentLines.toList()
            )
            header = null
            currentLines.clear()
        }

        output.lineSequence().forEach { raw ->
            if (truncated) return@forEach
            when {
                raw.startsWith("diff --git ") -> {
                    // The command is scoped to one path; a second file section
                    // means we cannot represent the result faithfully.
                    if (header != null || hunks.isNotEmpty()) {
                        truncated = true
                    }
                }

                raw == "GIT binary patch" ||
                    (raw.startsWith("Binary files ") && raw.endsWith(" differ")) -> {
                    binary = true
                }

                raw.startsWith("--- ") -> {
                    oldPath = parsePath(raw.removePrefix("--- "), "a/")
                }

                raw.startsWith("@@ ") -> {
                    finishHunk()
                    val match = HUNK_HEADER.matchEntire(raw)
                    if (match == null) {
                        truncated = true
                    } else {
                        oldStart = match.groupValues[1].toIntOrNull() ?: 0
                        oldCount = match.groupValues[2].toIntOrNull() ?: 1
                        newStart = match.groupValues[3].toIntOrNull() ?: 0
                        newCount = match.groupValues[4].toIntOrNull() ?: 1
                        oldLine = oldStart
                        newLine = newStart
                        header = raw
                    }
                }

                header != null -> {
                    if (renderedLines >= MAX_LINES) {
                        truncated = true
                        return@forEach
                    }
                    when {
                        raw.startsWith("+") -> {
                            currentLines += GitDiffLine(
                                kind = GitDiffLineKind.ADDED,
                                text = raw.substring(1),
                                newLine = newLine
                            )
                            newLine++
                            renderedLines++
                        }

                        raw.startsWith("-") -> {
                            currentLines += GitDiffLine(
                                kind = GitDiffLineKind.REMOVED,
                                text = raw.substring(1),
                                oldLine = oldLine
                            )
                            oldLine++
                            renderedLines++
                        }

                        raw.startsWith(" ") -> {
                            currentLines += GitDiffLine(
                                kind = GitDiffLineKind.CONTEXT,
                                text = raw.substring(1),
                                oldLine = oldLine,
                                newLine = newLine
                            )
                            oldLine++
                            newLine++
                            renderedLines++
                        }

                        // "\ No newline at end of file" and other markers.
                        else -> Unit
                    }
                }
            }
        }
        finishHunk()

        val file = GitDiffFile(
            path = fallbackPath,
            oldPath = oldPath,
            isBinary = binary,
            hunks = hunks
        )
        return GitDiff(files = listOf(file), truncated = truncated)
    }

    private fun parsePath(value: String, prefix: String): String? {
        val trimmed = value.trim()
        if (trimmed == "/dev/null") return null
        val unquoted = if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
        return unquoted.removePrefix(prefix).takeIf { it.isNotEmpty() }
    }
}
