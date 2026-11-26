package dev.mobileide.data.lsp

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Framing is broken: the stream cannot be trusted any more. */
class LspProtocolException(message: String) : IOException(message)

/** Payload was framed correctly but is not a JSON object; skip and continue. */
class LspMessageFormatException(message: String) : IOException(message)

/**
 * Reads `Content-Length` framed LSP messages from a byte stream.
 *
 * A line-based reader cannot work here: the payload is length-delimited UTF-8
 * and may contain newlines. Partial reads, several messages in one read and
 * multi-byte characters split across reads are all handled.
 */
class LspFrameReader(private val input: InputStream) {

    /** Returns the next message, or null at a clean end of stream. */
    fun readMessage(): JSONObject? {
        val header = readHeader() ?: return null
        val length = parseContentLength(header)
        val body = ByteArray(length)
        readFully(body)
        val text = String(body, Charsets.UTF_8)
        return try {
            JSONObject(text)
        } catch (error: JSONException) {
            throw LspMessageFormatException("Invalid JSON payload: ${error.message}")
        }
    }

    private fun readHeader(): String? {
        val buffer = ByteArrayOutputStream(128)
        var b1 = 0
        var b2 = 0
        var b3 = 0
        var b4 = 0
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (buffer.size() == 0) return null
                throw LspProtocolException("Stream ended inside a message header")
            }
            buffer.write(value)
            b1 = b2
            b2 = b3
            b3 = b4
            b4 = value
            // Accept both the spec's CRLFCRLF and a bare LFLF.
            if (b1 == '\r'.code && b2 == '\n'.code && b3 == '\r'.code && b4 == '\n'.code) {
                break
            }
            if (b3 == '\n'.code && b4 == '\n'.code) break
            if (buffer.size() > MAX_HEADER_BYTES) {
                throw LspProtocolException("Message header too large")
            }
        }
        val text = String(buffer.toByteArray(), Charsets.UTF_8)
        return text.removeSuffix("\r\n\r\n").removeSuffix("\n\n")
    }

    private fun parseContentLength(header: String): Int {
        var length: Int? = null
        for (line in header.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val separator = trimmed.indexOf(':')
            if (separator <= 0) {
                throw LspProtocolException("Malformed header line: $trimmed")
            }
            val name = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            if (name.equals("Content-Length", ignoreCase = true)) {
                val parsed = value.toIntOrNull()
                    ?: throw LspProtocolException("Invalid Content-Length: $value")
                length = parsed
            }
        }
        val resolved = length ?: throw LspProtocolException("Missing Content-Length header")
        if (resolved <= 0 || resolved > MAX_MESSAGE_BYTES) {
            throw LspProtocolException("Content-Length out of range: $resolved")
        }
        return resolved
    }

    private fun readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw LspProtocolException("Stream ended inside a message body")
            offset += read
        }
    }

    companion object {
        const val MAX_HEADER_BYTES = 8 * 1024
        const val MAX_MESSAGE_BYTES = 8 * 1024 * 1024
    }
}

/** Encodes a JSON-RPC message with its `Content-Length` header. */
object LspMessageCodec {

    fun encode(message: JSONObject): ByteArray {
        val body = message.toString().toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream(header.size + body.size)
        out.write(header)
        out.write(body)
        return out.toByteArray()
    }
}
