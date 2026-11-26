package dev.mobileide.data.lsp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class LspMessageCodecTest {

    @Test
    fun `reads messages split across small reads`() {
        val payload = concat(
            LspMessageCodec.encode(message(1, "alpha")),
            LspMessageCodec.encode(message(2, "beta"))
        )
        val reader = LspFrameReader(ChunkedStream(payload, 3))

        assertEquals("alpha", reader.readMessage()?.optString("method"))
        assertEquals("beta", reader.readMessage()?.optString("method"))
        assertNull(reader.readMessage())
    }

    @Test
    fun `reads several messages from one buffer`() {
        val payload = concat(
            LspMessageCodec.encode(message(1, "one")),
            LspMessageCodec.encode(message(2, "two")),
            LspMessageCodec.encode(message(3, "three"))
        )
        val reader = LspFrameReader(ByteArrayInputStream(payload))

        assertEquals("one", reader.readMessage()?.optString("method"))
        assertEquals("two", reader.readMessage()?.optString("method"))
        assertEquals("three", reader.readMessage()?.optString("method"))
    }

    @Test
    fun `keeps multi byte payloads intact`() {
        val source = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "note")
            .put("params", JSONObject().put("text", "中文 🙂 café"))
        val reader = LspFrameReader(ChunkedStream(LspMessageCodec.encode(source), 5))

        val message = reader.readMessage()
        assertEquals("中文 🙂 café", message?.getJSONObject("params")?.getString("text"))
    }

    @Test
    fun `accepts a lower case content length header`() {
        val body = "{\"jsonrpc\":\"2.0\",\"method\":\"x\"}"
        val raws = "content-length: ${body.toByteArray().size}\r\n\r\n$body".toByteArray()
        val reader = LspFrameReader(ByteArrayInputStream(raws))

        assertEquals("x", reader.readMessage()?.optString("method"))
    }

    @Test
    fun `rejects a header without length`() {
        val raws = "X-Test: 1\r\n\r\n{}".toByteArray()
        assertThrows(LspProtocolException::class.java) {
            LspFrameReader(ByteArrayInputStream(raws)).readMessage()
        }
    }

    @Test
    fun `rejects an oversized body`() {
        val raws = "Content-Length: 999999999\r\n\r\n".toByteArray()
        assertThrows(LspProtocolException::class.java) {
            LspFrameReader(ByteArrayInputStream(raws)).readMessage()
        }
    }

    @Test
    fun `rejects a truncated body`() {
        val raws = "Content-Length: 10\r\n\r\n{}".toByteArray()
        assertThrows(LspProtocolException::class.java) {
            LspFrameReader(ByteArrayInputStream(raws)).readMessage()
        }
    }

    @Test
    fun `skips invalid json and keeps the stream usable`() {
        val bad = "Content-Length: 3\r\n\r\n{xx".toByteArray()
        val good = LspMessageCodec.encode(JSONObject().put("jsonrpc", "2.0").put("method", "ok"))
        val reader = LspFrameReader(ByteArrayInputStream(concat(bad, good)))

        assertThrows(LspMessageFormatException::class.java) { reader.readMessage() }
        assertEquals("ok", reader.readMessage()?.optString("method"))
    }

    @Test
    fun `ends cleanly at end of stream`() {
        assertNull(LspFrameReader(ByteArrayInputStream(ByteArray(0))).readMessage())
    }

    private fun message(id: Int, method: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", method)

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        parts.forEach { part ->
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    private class ChunkedStream(
        private val data: ByteArray,
        private val chunk: Int
    ) : InputStream() {

        private var offset = 0

        override fun read(): Int =
            if (offset < data.size) data[offset++].toInt() and 0xFF else -1

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (offset >= data.size) return -1
            val count = minOf(len, chunk, data.size - offset)
            System.arraycopy(data, offset, buffer, off, count)
            offset += count
            return count
        }
    }
}
