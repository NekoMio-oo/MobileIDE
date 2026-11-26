package dev.mobileide.domain.editor

/** Token classes the lightweight Python scanner distinguishes. */
enum class PythonTokenKind {
    KEYWORD,
    STRING,
    COMMENT,
    NUMBER,
    DECORATOR,
    FUNCTION,
    BUILTIN
}

/**
 * A small, stateful Python scanner used for syntax highlighting.
 *
 * It is deliberately not a parser: keywords, strings (including triple-quoted
 * blocks spanning lines), comments, numbers, decorators, definition names and
 * builtins are enough to make code readable. The scan is linear and emits
 * style-change positions directly, so the editor side needs no intermediate
 * token list. Highlighting is independent from LSP.
 */
object PythonHighlighter {

    /** [kind] is null when the style returns to normal text. */
    fun interface SpanSink {
        fun onSpan(line: Int, column: Int, kind: PythonTokenKind?)
    }

    private val KEYWORDS = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await",
        "break", "class", "continue", "def", "del", "elif", "else", "except",
        "finally", "for", "from", "global", "if", "import", "in", "is",
        "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
        "while", "with", "yield", "match", "case"
    )

    private val BUILTINS = setOf(
        "abs", "all", "any", "ascii", "bin", "bool", "breakpoint", "bytearray",
        "bytes", "callable", "chr", "classmethod", "compile", "complex",
        "delattr", "dict", "dir", "divmod", "enumerate", "eval", "exec",
        "filter", "float", "format", "frozenset", "getattr", "globals",
        "hasattr", "hash", "help", "hex", "id", "input", "int", "isinstance",
        "issubclass", "iter", "len", "list", "locals", "map", "max",
        "memoryview", "min", "next", "object", "oct", "open", "ord", "pow",
        "print", "property", "range", "repr", "reversed", "round", "set",
        "setattr", "slice", "sorted", "staticmethod", "str", "sum", "super",
        "tuple", "type", "vars", "zip", "__import__",
        "Exception", "ValueError", "TypeError", "KeyError", "IndexError",
        "RuntimeError", "StopIteration", "OSError", "FileNotFoundError",
        "AttributeError", "NameError", "ZeroDivisionError", "ImportError"
    )

    /**
     * Emits style changes in document order and returns the number of lines,
     * including the trailing empty line when [text] ends with a newline.
     */
    fun tokenize(text: CharSequence, sink: SpanSink): Int {
        var line = 0
        var lineStart = 0
        var triple: String? = null
        var expectDefinition = false

        while (true) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) text.length else newline
            val state = scanLine(text, lineStart, lineEnd, line, triple, expectDefinition, sink)
            triple = state.triple
            expectDefinition = state.expectDefinition
            if (newline < 0) return line + 1
            lineStart = newline + 1
            line++
        }
    }

    private data class LineState(val triple: String?, val expectDefinition: Boolean)

    private fun scanLine(
        text: CharSequence,
        lineStart: Int,
        lineEnd: Int,
        line: Int,
        triple: String?,
        expectDefinition: Boolean,
        sink: SpanSink
    ): LineState {
        var index = lineStart
        var expectDef = expectDefinition
        var openTriple: String? = null
        // True while a style is still active on this line; a normal span closes
        // it so the next line never inherits a stale colour.
        var styled = false

        fun emit(position: Int, kind: PythonTokenKind?) {
            sink.onSpan(line, position - lineStart, kind)
            styled = kind != null
        }

        if (triple != null) {
            val close = findTripleClose(text, index, lineEnd, triple)
            if (close < 0) {
                if (lineEnd > index) emit(index, PythonTokenKind.STRING)
                openTriple = triple
                index = lineEnd
            } else {
                emit(index, PythonTokenKind.STRING)
                emit(close + 3, null)
                index = close + 3
                openTriple = null
            }
        }

        while (index < lineEnd) {
            val char = text[index]
            when {
                char == '#' -> {
                    emit(index, PythonTokenKind.COMMENT)
                    index = lineEnd
                }

                char == '"' || char == '\'' -> {
                    val isTriple = index + 2 < lineEnd &&
                        text[index + 1] == char && text[index + 2] == char
                    if (isTriple) {
                        val delimiter = "$char$char$char"
                        val close = findTripleClose(text, index + 3, lineEnd, delimiter)
                        if (close < 0) {
                            emit(index, PythonTokenKind.STRING)
                            openTriple = delimiter
                            index = lineEnd
                        } else {
                            val end = close + 3
                            emit(index, PythonTokenKind.STRING)
                            emit(end, null)
                            index = end
                        }
                    } else {
                        var cursor = index + 1
                        while (cursor < lineEnd) {
                            if (text[cursor] == '\\') {
                                cursor += 2
                                continue
                            }
                            if (text[cursor] == char) break
                            cursor++
                        }
                        val end = (cursor + 1).coerceAtMost(lineEnd)
                        emit(index, PythonTokenKind.STRING)
                        emit(end, null)
                        index = end
                    }
                }

                char == '@' && index + 1 < lineEnd &&
                    (text[index + 1].isLetter() || text[index + 1] == '_') -> {
                    var cursor = index + 1
                    while (cursor < lineEnd &&
                        (text[cursor].isLetterOrDigit() || text[cursor] == '_' || text[cursor] == '.')
                    ) {
                        cursor++
                    }
                    emit(index, PythonTokenKind.DECORATOR)
                    emit(cursor, null)
                    index = cursor
                }

                char.isDigit() -> {
                    var cursor = index + 1
                    while (cursor < lineEnd) {
                        val current = text[cursor]
                        if (current.isLetterOrDigit() || current == '_' || current == '.') {
                            cursor++
                            continue
                        }
                        if ((current == '+' || current == '-') &&
                            (text[cursor - 1] == 'e' || text[cursor - 1] == 'E')
                        ) {
                            cursor++
                            continue
                        }
                        break
                    }
                    emit(index, PythonTokenKind.NUMBER)
                    emit(cursor, null)
                    index = cursor
                }

                char.isLetter() || char == '_' -> {
                    var cursor = index + 1
                    while (cursor < lineEnd &&
                        (text[cursor].isLetterOrDigit() || text[cursor] == '_')
                    ) {
                        cursor++
                    }
                    val word = text.subSequence(index, cursor).toString()
                    when {
                        word in KEYWORDS -> {
                            emit(index, PythonTokenKind.KEYWORD)
                            emit(cursor, null)
                            if (word == "def" || word == "class") expectDef = true
                        }

                        expectDef -> {
                            emit(index, PythonTokenKind.FUNCTION)
                            emit(cursor, null)
                            expectDef = false
                        }

                        word in BUILTINS -> {
                            emit(index, PythonTokenKind.BUILTIN)
                            emit(cursor, null)
                        }
                    }
                    index = cursor
                }

                else -> index++
            }
        }

        // A triple-quoted string deliberately keeps its colour on the next line.
        // Every other line must end on normal text.
        if (openTriple == null && styled) emit(lineEnd, null)
        return LineState(openTriple, expectDef)
    }

    /** Finds a triple quote, skipping escaped characters. */
    private fun findTripleClose(text: CharSequence, from: Int, end: Int, quote: String): Int {
        var index = from
        while (index + 2 < end) {
            if (text[index] == '\\') {
                index += 2
                continue
            }
            if (text[index] == quote[0] &&
                text[index + 1] == quote[0] &&
                text[index + 2] == quote[0]
            ) {
                return index
            }
            index++
        }
        return -1
    }
}
