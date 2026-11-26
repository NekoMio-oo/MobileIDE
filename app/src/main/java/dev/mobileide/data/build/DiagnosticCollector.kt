package dev.mobileide.data.build

import dev.mobileide.domain.build.BuildLogLevel
import dev.mobileide.domain.build.BuildProblem
import dev.mobileide.domain.build.BuildProblemSeverity
import dev.mobileide.domain.build.DiagnosticDialect
import dev.mobileide.domain.build.GuestPaths

/**
 * Parses compiler output line by line. Unrecognised lines are never dropped:
 * they stay in the log and the build result keeps the raw text, so an unknown
 * format cannot hide a failure.
 */
class DiagnosticCollector(
    private val dialect: DiagnosticDialect,
    private val maxProblems: Int = 300
) {

    private val collected = mutableListOf<BuildProblem>()
    private var pendingRust: Pair<BuildProblemSeverity, String>? = null

    val problems: List<BuildProblem> get() = collected

    fun onLine(rawLine: String): BuildProblem? {
        if (dialect == DiagnosticDialect.NONE) return null
        val line = rawLine.replace(ANSI, "")
        val problem = when (dialect) {
            DiagnosticDialect.CC -> parseCc(line)
            DiagnosticDialect.JAVA -> parseJava(line)
            DiagnosticDialect.GO -> parseGo(line)
            DiagnosticDialect.RUST -> parseRust(line)
            DiagnosticDialect.TYPESCRIPT -> parseTypeScript(line)
            DiagnosticDialect.NONE -> null
        } ?: return null
        if (collected.size >= maxProblems) return null
        collected += problem
        return problem
    }

    /** Coarse level for the log view; exact severities come from [onLine]. */
    fun levelOf(line: String): BuildLogLevel {
        val lower = line.lowercase()
        return when {
            lower.contains("fatal error") -> BuildLogLevel.ERROR
            lower.contains(": error") || lower.contains("error:") -> BuildLogLevel.ERROR
            lower.startsWith("error") -> BuildLogLevel.ERROR
            lower.contains(": warning") || lower.contains("warning:") -> BuildLogLevel.WARNING
            lower.startsWith("warning") -> BuildLogLevel.WARNING
            else -> BuildLogLevel.INFO
        }
    }

    private fun parseCc(line: String): BuildProblem? {
        val match = CC.matchEntire(line) ?: return null
        val severity = when (match.groupValues[4]) {
            "error", "fatal error" -> BuildProblemSeverity.ERROR
            "warning" -> BuildProblemSeverity.WARNING
            else -> return null
        }
        val column = match.groupValues[3].toIntOrNull()
        return problem(
            severity = severity,
            path = match.groupValues[1],
            line = match.groupValues[2],
            column = column,
            message = match.groupValues[5],
            raw = line
        )
    }

    private fun parseJava(line: String): BuildProblem? {
        val match = JAVA.matchEntire(line) ?: return null
        return problem(
            severity = if (match.groupValues[3] == "error") {
                BuildProblemSeverity.ERROR
            } else {
                BuildProblemSeverity.WARNING
            },
            path = match.groupValues[1],
            line = match.groupValues[2],
            column = null,
            message = match.groupValues[4],
            raw = line
        )
    }

    private fun parseGo(line: String): BuildProblem? {
        val match = GO.matchEntire(line) ?: return null
        val column = match.groupValues[3].toIntOrNull()
        return problem(
            severity = BuildProblemSeverity.ERROR,
            path = match.groupValues[1],
            line = match.groupValues[2],
            column = column,
            message = match.groupValues[4],
            raw = line
        )
    }

    private fun parseRust(line: String): BuildProblem? {
        val arrow = RUST_ARROW.matchEntire(line)
        if (arrow != null) {
            val pending = pendingRust ?: return null
            pendingRust = null
            return problem(
                severity = pending.first,
                path = arrow.groupValues[1],
                line = arrow.groupValues[2],
                column = arrow.groupValues[3].toIntOrNull(),
                message = pending.second,
                raw = line.trim()
            )
        }
        val header = RUST_HEADER.matchEntire(line.trim())
        if (header != null) {
            val severity = if (header.groupValues[1] == "warning") {
                BuildProblemSeverity.WARNING
            } else {
                BuildProblemSeverity.ERROR
            }
            val message = header.groupValues[2].ifBlank { line.trim() }
            pendingRust = severity to message
        }
        return null
    }

    private fun parseTypeScript(line: String): BuildProblem? {
        val match = TYPESCRIPT.matchEntire(line) ?: return null
        return problem(
            severity = if (match.groupValues[4] == "error") {
                BuildProblemSeverity.ERROR
            } else {
                BuildProblemSeverity.WARNING
            },
            path = match.groupValues[1],
            line = match.groupValues[2],
            column = match.groupValues[3].toIntOrNull(),
            message = match.groupValues[5],
            raw = line
        )
    }

    private fun problem(
        severity: BuildProblemSeverity,
        path: String?,
        line: String,
        column: Int?,
        message: String,
        raw: String
    ): BuildProblem = BuildProblem(
        severity = severity,
        path = normalize(path),
        line = line.toIntOrNull()?.coerceAtLeast(1) ?: 1,
        column = column?.takeIf { it > 0 },
        message = message.trim().take(600),
        raw = raw.trim().take(1_000)
    )

    private fun normalize(raw: String?): String? {
        val value = raw?.trim()?.trim('"')?.takeIf { it.isNotBlank() } ?: return null
        val withoutRoot = if (value.startsWith(GuestPaths.ROOT + "/")) {
            value.removePrefix(GuestPaths.ROOT + "/")
        } else {
            value
        }
        return BuildPaths.sanitizeRelative(withoutRoot)
    }

    private companion object {
        val ANSI = Regex("\u001B\\[[;\\d]*m")
        val CC = Regex("^(.+?):(\\d+):(?:(\\d+):)?\\s*(fatal error|error|warning|note):\\s*(.+)$")
        val JAVA = Regex("^(.+?\\.java):(\\d+):\\s*(error|warning):\\s*(.+)$")
        val GO = Regex("^(.+?\\.go):(\\d+):(?:(\\d+):)?\\s*(.+)$")
        val RUST_ARROW = Regex("^\\s*-->\\s+(.+?):(\\d+):(\\d+)\\s*$")
        val RUST_HEADER = Regex("^(error|warning)(?:\\[[A-Za-z0-9]+\\])?:\\s*(.*)$")
        val TYPESCRIPT =
            Regex("^(.+?)\\((\\d+),(\\d+)\\):\\s*(error|warning)\\s+(?:TS\\d+:\\s*)?(.+)$")
    }
}
