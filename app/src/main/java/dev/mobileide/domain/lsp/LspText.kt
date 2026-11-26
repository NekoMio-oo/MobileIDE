package dev.mobileide.domain.lsp

/**
 * Text helpers shared by the protocol parser and the editor UI.
 *
 * Hover markup is reduced to readable plain text (no markdown renderer is
 * shipped) and snippet placeholders are stripped because the client advertises
 * `snippetSupport = false`.
 */
object LspText {

    fun markupToPlainText(markup: String): String {
        val out = StringBuilder(markup.length)
        var index = 0
        var inFence = false
        while (index < markup.length) {
            if (markup.startsWith("```", index)) {
                inFence = !inFence
                index += 3
                // Skip the language tag up to the end of the line.
                while (index < markup.length && markup[index] != '\n') index++
                continue
            }
            val char = markup[index]
            when (char) {
                '`', '*', '_' -> index++
                '#' -> {
                    while (index < markup.length && markup[index] == '#') index++
                    if (index < markup.length && markup[index] == ' ') index++
                }

                '[' -> {
                    val close = markup.indexOf(']', index)
                    val open = if (close > 0) markup.indexOf('(', close) else -1
                    val end = if (open > 0) markup.indexOf(')', open) else -1
                    if (close > 0 && open == close + 1 && end > open) {
                        out.append(markup, index + 1, close)
                        index = end + 1
                    } else {
                        out.append(char)
                        index++
                    }
                }

                '\\' -> {
                    if (index + 1 < markup.length) {
                        out.append(markup[index + 1])
                        index += 2
                    } else {
                        index++
                    }
                }

                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        // Collapse the blank lines that fences leave behind.
        return out.toString()
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Removes snippet placeholders. The client advertises no snippet support,
     * so this is only a safety net for servers that send `$1` anyway.
     */
    fun stripSnippets(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char == '\\' && index + 1 < text.length) {
                out.append(text[index + 1])
                index += 2
                continue
            }
            if (char == '$' && index + 1 < text.length) {
                var cursor = index + 1
                if (text[cursor] == '{') {
                    cursor++
                    while (cursor < text.length && text[cursor].isDigit()) cursor++
                    if (cursor < text.length && text[cursor] == '}') {
                        index = cursor + 1
                        continue
                    }
                    if (cursor < text.length && text[cursor] == ':') {
                        cursor++
                        var depth = 1
                        val start = cursor
                        while (cursor < text.length && depth > 0) {
                            when (text[cursor]) {
                                '{' -> depth++
                                '}' -> depth--
                            }
                            cursor++
                        }
                        val inner = text.substring(start, (cursor - 1).coerceAtLeast(start))
                        out.append(stripSnippets(inner))
                        index = cursor
                        continue
                    }
                    if (cursor < text.length && text[cursor] == '|') {
                        val close = text.indexOf('|', cursor + 1)
                        if (close > 0) {
                            out.append(text.substring(cursor + 1, close).substringBefore(','))
                            index = if (close + 1 < text.length && text[close + 1] == '}') {
                                close + 2
                            } else {
                                close + 1
                            }
                            continue
                        }
                    }
                } else {
                    while (cursor < text.length && text[cursor].isDigit()) cursor++
                    if (cursor > index + 1) {
                        index = cursor
                        continue
                    }
                }
            }
            out.append(char)
            index++
        }
        return out.toString()
    }
}
