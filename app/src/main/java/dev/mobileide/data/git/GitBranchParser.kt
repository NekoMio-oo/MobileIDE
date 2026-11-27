package dev.mobileide.data.git

import dev.mobileide.domain.git.GitBranch

/**
 * Parser for `git branch --format=...` with 0x1F separated fields.
 *
 * Local fields: name, HEAD marker ("*" or " "), short hash, upstream, track.
 * Remote fields: name, short hash. The symbolic `<remote>/HEAD` entry is
 * omitted because it is not a real branch.
 */
object GitBranchParser {

    private val AHEAD = Regex("ahead (\\d+)")
    private val BEHIND = Regex("behind (\\d+)")

    fun parseLocal(output: String): List<GitBranch> =
        output.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val fields = line.split('\u001f')
                if (fields.size < 5) return@mapNotNull null
                val track = fields[4]
                GitBranch(
                    name = fields[0],
                    isCurrent = fields[1].trim() == "*",
                    shortHash = fields[2].takeIf { it.isNotBlank() },
                    upstream = fields[3].takeIf { it.isNotBlank() },
                    ahead = AHEAD.find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                    behind = BEHIND.find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                    upstreamGone = track.contains("gone", ignoreCase = true)
                )
            }
            .toList()

    fun parseRemote(output: String): List<GitBranch> =
        output.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val fields = line.split('\u001f')
                if (fields.size < 2) return@mapNotNull null
                val name = fields[0]
                if (name.endsWith("/HEAD")) return@mapNotNull null
                GitBranch(
                    name = name,
                    isRemote = true,
                    shortHash = fields[1].takeIf { it.isNotBlank() }
                )
            }
            .toList()
}
