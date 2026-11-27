package dev.mobileide.data.git

import dev.mobileide.domain.git.GitCommit

/**
 * Parser for the history format used by [GitRepositoryImpl]:
 *
 * `%H US %h US %an US %ae US %aI US %s US %D US %P RS`
 *
 * US is 0x1F and RS is 0x1E. Git writes an extra newline between commits, so
 * records are trimmed before their fields are read.
 */
object GitLogParser {

    fun parse(output: String): List<GitCommit> =
        output.split('\u001e')
            .map { it.trim('\n', '\r') }
            .filter { it.isNotEmpty() }
            .mapNotNull(::parseRecord)

    fun parseSingle(output: String): GitCommit? = parse(output).firstOrNull()

    private fun parseRecord(record: String): GitCommit? {
        val fields = record.split('\u001f')
        if (fields.size < 8) return null
        val parents = fields[7]
            .split(' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return GitCommit(
            hash = fields[0].trim(),
            shortHash = fields[1].trim(),
            authorName = fields[2],
            authorEmail = fields[3],
            date = fields[4].trim(),
            subject = fields[5],
            refs = parseRefs(fields[6]),
            parents = parents
        )
    }

    private fun parseRefs(value: String): List<String> =
        value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
