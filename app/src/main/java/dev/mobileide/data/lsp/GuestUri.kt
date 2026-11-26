package dev.mobileide.data.lsp

import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * Maps project paths to `file:///workspace/...` URIs and back.
 *
 * Language servers run inside PRoot and only ever see `/workspace`, so the URI
 * is both the protocol identifier and the security boundary: anything that does
 * not resolve inside `/workspace` is rejected instead of being handed to the
 * server.
 */
object GuestUri {

    const val GUEST_ROOT = "/workspace"

    fun encode(relativePath: String): String {
        val normalized = relativePath.trim('/')
        if (normalized.isEmpty()) return "file://$GUEST_ROOT"
        val encoded = normalized.split('/').joinToString("/") { encodeSegment(it) }
        return "file://$GUEST_ROOT/$encoded"
    }

    /** Project-relative path, or null when the URI points outside the project. */
    fun relativePath(uri: String): String? {
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("file", ignoreCase = true)) return null
        val authority = parsed.authority
        if (!authority.isNullOrEmpty() && !authority.equals("localhost", ignoreCase = true)) return null
        val raw = parsed.rawPath ?: return null
        val decoded = decodePath(raw) ?: return null
        if (decoded == GUEST_ROOT) return ""
        if (!decoded.startsWith("$GUEST_ROOT/")) return null
        val relative = decoded.removePrefix("$GUEST_ROOT/")
        if (relative.isEmpty()) return ""
        val segments = relative.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." || '\\' in it }) return null
        return relative
    }

    fun guestPath(relativePath: String): String {
        val normalized = relativePath.trim('/')
        return if (normalized.isEmpty()) GUEST_ROOT else "$GUEST_ROOT/$normalized"
    }

    private fun encodeSegment(segment: String): String {
        val out = StringBuilder(segment.length)
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (char.isLetterOrDigit() && value < 0x80 ||
                char in "-._~!$&'()*+,;=:@"
            ) {
                out.append(char)
            } else {
                out.append('%')
                out.append(HEX[value shr 4])
                out.append(HEX[value and 0x0F])
            }
        }
        return out.toString()
    }

    private fun decodePath(raw: String): String? {
        val out = ByteArrayOutputStream(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char == '%') {
                if (index + 2 >= raw.length) return null
                val high = hex(raw[index + 1]) ?: return null
                val low = hex(raw[index + 2]) ?: return null
                out.write((high shl 4) or low)
                index += 3
            } else {
                out.write(char.toString().toByteArray(Charsets.UTF_8))
                index++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hex(char: Char): Int? = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> null
    }

    private const val HEX = "0123456789ABCDEF"
}
