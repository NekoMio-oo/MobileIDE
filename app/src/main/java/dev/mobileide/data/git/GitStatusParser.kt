package dev.mobileide.data.git

import dev.mobileide.domain.git.GitChangeKind
import dev.mobileide.domain.git.GitFileChange
import dev.mobileide.domain.git.GitRepoStatus

/**
 * Parser for `git status --porcelain=v2 --branch -z`.
 *
 * With `-z` every record is NUL terminated; rename/copy records use a second NUL
 * between the target and the original path. The parser therefore consumes one
 * extra token for records that start with `2`.
 *
 * Output caps can cut the stream mid-record. In that case the incomplete tail is
 * dropped and [GitRepoStatus.truncated] is set instead of exposing a broken path.
 */
object GitStatusParser {

    private const val MAX_CHANGES = 5_000
    private val BRANCH_AHEAD_BEHIND = Regex("^# branch\\.ab \\+(\\d+) -(\\d+)$")

    fun parse(output: String, truncated: Boolean = false): GitRepoStatus {
        var branch: String? = null
        var detached = false
        var head: String? = null
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        val changes = ArrayList<GitFileChange>()
        var incomplete = truncated

        var tokens = output.split('\u0000')
        if (output.isNotEmpty() && !output.endsWith('\u0000')) {
            // The runner only kept a prefix; the last record is not reliable.
            tokens = tokens.dropLast(1)
            incomplete = true
        }

        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.isEmpty()) {
                index++
                continue
            }
            when (token[0]) {
                '#' -> when {
                    token.startsWith("# branch.oid ") -> {
                        val value = token.removePrefix("# branch.oid ").trim()
                        head = value.takeUnless { it == "(initial)" }
                    }

                    token.startsWith("# branch.head ") -> {
                        val value = token.removePrefix("# branch.head ").trim()
                        if (value == "(detached)") detached = true else branch = value
                    }

                    token.startsWith("# branch.upstream ") -> {
                        upstream = token.removePrefix("# branch.upstream ").trim()
                    }

                    else -> {
                        BRANCH_AHEAD_BEHIND.matchEntire(token)?.let { match ->
                            ahead = match.groupValues[1].toIntOrNull() ?: 0
                            behind = match.groupValues[2].toIntOrNull() ?: 0
                        }
                    }
                }

                '1' -> parseOrdinary(token)?.let(changes::add)

                '2' -> {
                    val original = tokens.getOrNull(index + 1)
                    if (original == null) {
                        incomplete = true
                    } else {
                        index++
                    }
                    parseRename(token, original)?.let(changes::add)
                }

                'u' -> parseUnmerged(token)?.let(changes::add)

                '?' -> parseUntracked(token)?.let(changes::add)

                // Ignored files are not part of the change list.
                '!' -> Unit
            }

            if (changes.size >= MAX_CHANGES) {
                incomplete = true
                break
            }
            index++
        }

        return GitRepoStatus(
            branch = branch,
            detached = detached,
            headCommit = head,
            upstream = upstream,
            ahead = ahead,
            behind = behind,
            changes = changes,
            truncated = incomplete
        )
    }

    private fun parseOrdinary(token: String): GitFileChange? {
        val fields = token.split(' ', limit = 9)
        if (fields.size < 9) return null
        val xy = fields[1]
        if (xy.length < 2) return null
        val indexChange = kindOf(xy[0])
        val workTreeChange = kindOf(xy[1])
        if (indexChange == null && workTreeChange == null) return null
        return GitFileChange(
            path = fields[8],
            indexStatus = xy[0],
            workTreeStatus = xy[1],
            indexChange = indexChange,
            workTreeChange = workTreeChange
        )
    }

    private fun parseRename(token: String, originalPath: String?): GitFileChange? {
        if (originalPath.isNullOrEmpty()) return null
        val fields = token.split(' ', limit = 10)
        if (fields.size < 10) return null
        val xy = fields[1]
        if (xy.length < 2) return null
        return GitFileChange(
            path = fields[9],
            originalPath = originalPath,
            indexStatus = xy[0],
            workTreeStatus = xy[1],
            indexChange = kindOf(xy[0]),
            workTreeChange = kindOf(xy[1])
        )
    }

    private fun parseUnmerged(token: String): GitFileChange? {
        val fields = token.split(' ', limit = 11)
        if (fields.size < 11) return null
        val xy = fields[1]
        if (xy.length < 2) return null
        return GitFileChange(
            path = fields[10],
            indexStatus = xy[0],
            workTreeStatus = xy[1],
            conflicted = true
        )
    }

    private fun parseUntracked(token: String): GitFileChange? {
        if (token.length <= 2) return null
        val path = token.substring(2)
        if (path.isBlank()) return null
        return GitFileChange(
            path = path,
            indexStatus = '?',
            workTreeStatus = '?',
            untracked = true
        )
    }

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
}
