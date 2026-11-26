package dev.mobileide.domain.editor

/** Where a local completion candidate came from; also drives display order. */
enum class PythonSuggestionKind {
    KEYWORD,
    BUILTIN,
    FUNCTION,
    CLASS,
    MODULE,
    VARIABLE,
    IDENTIFIER
}

data class PythonSuggestion(
    val label: String,
    val kind: PythonSuggestionKind,
    val detail: String
)

/**
 * Word-based Python completion that needs no language server, PRoot or Debian.
 *
 * It offers keywords, builtins and the names that already appear in the file
 * (definitions, classes, imports, assignments and other words), filtered by the
 * identifier being typed. The existing [PythonHighlighter] scanner marks the
 * string/comment ranges so words inside them do not leak into the list.
 *
 * It is intentionally not a semantic engine: no type inference, no cross-file
 * symbols. When pylsp is READY its results are appended to this same list by
 * [dev.mobileide.feature.editor.lsp.LspLanguage], so the two sources coexist.
 */
object PythonCompletion {

    private const val MAX_SCAN_CHARS = 300_000
    private const val MAX_ITEMS = 100

    /**
     * Returns suggestions for the identifier ending at [column] on [line]
     * (both zero-based). An empty prefix returns nothing: opening a completion
     * window on a blank cursor would only show an unusable wall of words.
     */
    fun suggest(text: CharSequence, line: Int, column: Int): List<PythonSuggestion> {
        val source = text.toString()
        val lines = source.split('\n')
        val caretLine = lines.getOrNull(line) ?: return emptyList()
        val end = column.coerceIn(0, caretLine.length)
        var start = end
        while (start > 0 && isIdentifierChar(caretLine[start - 1])) start--
        val prefix = caretLine.substring(start, end)
        if (prefix.isEmpty()) return emptyList()

        val found = LinkedHashMap<String, PythonSuggestion>()
        fun offer(label: String, kind: PythonSuggestionKind, detail: String) {
            if (label.length < 2) return
            val existing = found[label]
            if (existing == null || priority(kind) < priority(existing.kind)) {
                found[label] = PythonSuggestion(label, kind, detail)
            }
        }

        PythonHighlighter.KEYWORDS.forEach {
            offer(it, PythonSuggestionKind.KEYWORD, "keyword")
        }
        PythonHighlighter.BUILTINS.forEach {
            offer(it, PythonSuggestionKind.BUILTIN, "built-in")
        }

        if (source.length <= MAX_SCAN_CHARS) {
            val skipped = skippedRanges(source, lines)
            for ((index, content) in lines.withIndex()) {
                var previous: String? = null
                var cursor = 0
                while (cursor < content.length) {
                    val char = content[cursor]
                    val blocked = skipped[index].orEmpty().any { cursor in it }
                    if (!isIdentifierStart(char) || blocked) {
                        if (blocked || (!isIdentifierChar(char) && !char.isWhitespace())) {
                            previous = null
                        }
                        cursor++
                        continue
                    }
                    val begin = cursor
                    while (cursor < content.length && isIdentifierChar(content[cursor])) cursor++
                    val word = content.substring(begin, cursor)
                    // The word currently being typed is the prefix, not a candidate.
                    val typingHere = index == line && begin <= start && cursor >= end
                    if (!typingHere) {
                        var look = cursor
                        while (look < content.length && content[look] == ' ') look++
                        val next = content.getOrNull(look) ?: ' '
                        val afterNext = content.getOrNull(look + 1) ?: ' '
                        val kind = when {
                            previous == "def" -> PythonSuggestionKind.FUNCTION
                            previous == "class" -> PythonSuggestionKind.CLASS
                            previous == "import" || previous == "from" -> PythonSuggestionKind.MODULE
                            next == '=' && afterNext != '=' -> PythonSuggestionKind.VARIABLE
                            else -> PythonSuggestionKind.IDENTIFIER
                        }
                        offer(word, kind, "in this file")
                    }
                    previous = word
                }
            }
        }

        return found.values
            .asSequence()
            .filter { it.label.length > prefix.length && it.label.startsWith(prefix) }
            .sortedWith(compareBy({ priority(it.kind) }, { it.label }))
            .take(MAX_ITEMS)
            .toList()
    }

    /**
     * Expands the highlighter's style-change events into per-line column ranges
     * that must not be harvested. A style active at the end of a line carries
     * over to the next one, which keeps triple-quoted strings covered.
     */
    private fun skippedRanges(source: String, lines: List<String>): Map<Int, List<IntRange>> {
        val events = mutableMapOf<Int, MutableList<Pair<Int, PythonTokenKind?>>>()
        PythonHighlighter.tokenize(source) { line, column, kind ->
            events.getOrPut(line) { mutableListOf() } += column to kind
        }
        val result = mutableMapOf<Int, MutableList<IntRange>>()
        var carry: PythonTokenKind? = null
        for (index in lines.indices) {
            var kind = carry
            var from = 0
            for ((column, next) in events[index].orEmpty()) {
                if (column > from && isSkipped(kind)) {
                    result.getOrPut(index) { mutableListOf() } += from until column
                }
                from = column
                kind = next
            }
            if (isSkipped(kind) && lines[index].length > from) {
                result.getOrPut(index) { mutableListOf() } += from until lines[index].length
            }
            carry = kind
        }
        return result
    }

    private fun isSkipped(kind: PythonTokenKind?): Boolean =
        kind == PythonTokenKind.STRING || kind == PythonTokenKind.COMMENT

    private fun priority(kind: PythonSuggestionKind): Int = when (kind) {
        PythonSuggestionKind.KEYWORD -> 0
        PythonSuggestionKind.BUILTIN -> 1
        PythonSuggestionKind.FUNCTION -> 2
        PythonSuggestionKind.CLASS -> 3
        PythonSuggestionKind.MODULE -> 4
        PythonSuggestionKind.VARIABLE -> 5
        PythonSuggestionKind.IDENTIFIER -> 6
    }

    private fun isIdentifierStart(char: Char): Boolean = char.isLetter() || char == '_'

    private fun isIdentifierChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_'
}
