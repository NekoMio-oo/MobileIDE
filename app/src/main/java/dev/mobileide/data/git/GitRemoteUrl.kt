package dev.mobileide.data.git

import dev.mobileide.domain.git.GitRemoteAuth
import java.net.URI

/**
 * Parsed remote URL. Embedded HTTPS credentials are removed from [cleanUrl] so
 * they never land in `.git/config`; SSH usernames stay because they are part of
 * the address, not a secret.
 */
data class ParsedRemoteUrl(
    val url: String,
    val cleanUrl: String,
    val host: String? = null,
    val hostKey: String? = null,
    val username: String? = null,
    val password: String? = null,
    val auth: GitRemoteAuth = GitRemoteAuth.OTHER
)

object GitRemoteUrlParser {

    private val SCP_LIKE = Regex("^([^/@:]+)@([^/:]+):(.+)$")

    fun parse(value: String): ParsedRemoteUrl {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ParsedRemoteUrl(trimmed, trimmed)

        val scp = SCP_LIKE.matchEntire(trimmed)
        if (scp != null) {
            val host = scp.groupValues[2]
            return ParsedRemoteUrl(
                url = trimmed,
                cleanUrl = trimmed,
                host = host,
                hostKey = host,
                username = scp.groupValues[1],
                auth = GitRemoteAuth.SSH
            )
        }

        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return ParsedRemoteUrl(trimmed, trimmed)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        val hostKey = host?.let { value ->
            if (uri.port > 0) "$value:${uri.port}" else value
        }
        val userInfo = uri.userInfo
        val username = userInfo?.substringBefore(':')?.takeIf { it.isNotEmpty() }
        val password = userInfo
            ?.substringAfter(':', "")
            ?.takeIf { it.isNotEmpty() }
        val auth = when {
            scheme == "https" || scheme == "http" -> GitRemoteAuth.HTTPS
            scheme == "ssh" -> GitRemoteAuth.SSH
            else -> GitRemoteAuth.OTHER
        }
        val cleanUrl = if (auth == GitRemoteAuth.HTTPS &&
            uri.rawUserInfo != null &&
            uri.host != null
        ) {
            URI(
                uri.scheme,
                null,
                uri.host,
                uri.port,
                uri.path,
                uri.query,
                uri.fragment
            ).toString()
        } else {
            trimmed
        }
        return ParsedRemoteUrl(
            url = trimmed,
            cleanUrl = cleanUrl,
            host = host,
            hostKey = hostKey,
            username = username,
            password = password,
            auth = auth
        )
    }
}
