package dev.mobileide.data.git

/**
 * Parser for `git remote -v`. A remote may have different fetch and push URLs;
 * the fetch URL is the primary one shown in the UI.
 */
object GitRemoteParser {

    data class ParsedRemote(
        val name: String,
        val fetchUrl: String,
        val pushUrl: String? = null
    )

    fun parse(output: String): List<ParsedRemote> {
        val fetch = linkedMapOf<String, String>()
        val push = linkedMapOf<String, String>()

        output.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            val name = line.substring(0, tab).trim()
            val rest = line.substring(tab + 1)
            when {
                rest.endsWith(" (fetch)") ->
                    fetch[name] = rest.removeSuffix(" (fetch)").trim()

                rest.endsWith(" (push)") ->
                    push[name] = rest.removeSuffix(" (push)").trim()
            }
        }

        return fetch.map { (name, fetchUrl) ->
            val pushUrl = push[name]?.takeIf { it != fetchUrl }
            ParsedRemote(name = name, fetchUrl = fetchUrl, pushUrl = pushUrl)
        }
    }
}
