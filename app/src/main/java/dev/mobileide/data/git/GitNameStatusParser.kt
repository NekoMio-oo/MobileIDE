package dev.mobileide.data.git

import dev.mobileide.domain.git.GitChangeKind
import dev.mobileide.domain.git.GitCommitFile

/**
 * Parser for `git diff --name-status -z <parent> <commit>` (or diff-tree for a
 * root commit). Each record is `status NUL path NUL`; renames and copies carry
 * `status NUL oldPath NUL newPath NUL`.
 */
object GitNameStatusParser {

    fun parse(output: String, limit: Int = MAX_FILES): Result {
        var tokens = output.split('\u0000')
        if (output.isNotEmpty() && !output.endsWith('\u0000')) {
            // A capped capture may end inside a record.
            tokens = tokens.dropLast(1)
        }

        val files = mutableListOf<GitCommitFile>()
        var index = 0
        var truncated = false

        while (index < tokens.size) {
            val status = tokens[index].trim()
            if (status.isEmpty()) {
                index++
                continue
            }
            val change = kindOf(status.first())
            if (change == null) {
                index++
                continue
            }
            val renamed = status.first() == 'R' || status.first() == 'C'
            if (renamed) {
                val oldPath = tokens.getOrNull(index + 1)
                val newPath = tokens.getOrNull(index + 2)
                if (oldPath == null || newPath == null) {
                    truncated = true
                    break
                }
                files += GitCommitFile(
                    path = newPath,
                    oldPath = oldPath,
                    change = change
                )
                index += 3
            } else {
                val path = tokens.getOrNull(index + 1)
                if (path == null) {
                    truncated = true
                    break
                }
                files += GitCommitFile(path = path, change = change)
                index += 2
            }
            if (files.size >= limit) {
                truncated = true
                break
            }
        }
        return Result(files = files, truncated = truncated)
    }

    data class Result(
        val files: List<GitCommitFile>,
        val truncated: Boolean = false
    )

    private fun kindOf(status: Char): GitChangeKind? = when (status) {
        'M' -> GitChangeKind.MODIFIED
        'A' -> GitChangeKind.ADDED
        'D' -> GitChangeKind.DELETED
        'R' -> GitChangeKind.RENAMED
        'C' -> GitChangeKind.COPIED
        'T' -> GitChangeKind.TYPE_CHANGED
        'U' -> GitChangeKind.CONFLICTED
        else -> null
    }

    private const val MAX_FILES = 1_000
}
