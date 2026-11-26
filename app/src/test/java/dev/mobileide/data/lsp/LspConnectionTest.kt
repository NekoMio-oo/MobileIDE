package dev.mobileide.data.lsp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class LspConnectionTest {

    @Test
    fun `matches responses by id even when they arrive out of order`() = runBlocking {
        val harness = Harness()
        val server = harness.startServer { reader, writer ->
            val first = reader.readMessage()!!
            val second = reader.readMessage()!!
            writer.write(
                LspMessageCodec.encode(
                    result(second.getLong("id"), second.optString("method"))
                )
            )
            writer.write(
                LspMessageCodec.encode(
                    result(first.getLong("id"), first.optString("method"))
                )
            )
        }

        val first = async(Dispatchers.IO) {
            harness.connection.requestObject("first", null, 3_000)
        }
        val second = async(Dispatchers.IO) {
            harness.connection.requestObject("second", null, 3_000)
        }

        assertEquals("first", first.await()?.optString("value"))
        assertEquals("second", second.await()?.optString("value"))
        harness.close()
        server.join(1_000)
    }

    @Test
    fun `delivers server notifications`() = runBlocking {
        val harness = Harness()
        harness.startServer { _, writer ->
            writer.write(
                LspMessageCodec.encode(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("method", "textDocument/publishDiagnostics")
                        .put(
                            "params",
                            JSONObject().put("uri", "file:///workspace/a.c")
                        )
                )
            )
            Thread.sleep(400)
        }

        withTimeout(2_000) {
            while (harness.handler.notifications.isEmpty()) delay(10)
        }
        assertEquals(
            "textDocument/publishDiagnostics",
            harness.handler.notifications.first().first
        )
        harness.close()
    }

    @Test
    fun `answers server initiated requests`() = runBlocking {
        val harness = Harness()
        val response = CompletableDeferred<JSONObject>()
        harness.startServer { reader, writer ->
            writer.write(
                LspMessageCodec.encode(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", 99)
                        .put("method", "client/registerCapability")
                        .put("params", JSONObject())
                )
            )
            reader.readMessage()?.let { response.complete(it) }
        }

        val message = withTimeout(3_000) { response.await() }
        assertEquals(99L, message.optLong("id"))
        assertTrue(message.has("result"))
        harness.close()
    }

    @Test
    fun `surfaces json rpc errors`() {
        val harness = Harness()
        harness.startServer { reader, writer ->
            val request = reader.readMessage()!!
            writer.write(
                LspMessageCodec.encode(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", request.getLong("id"))
                        .put(
                            "error",
                            JSONObject().put("code", -32000).put("message", "boom")
                        )
                )
            )
            Thread.sleep(300)
        }

        val error = assertThrows(LspResponseException::class.java) {
            runBlocking { harness.connection.requestObject("explode", null, 3_000) }
        }
        assertEquals(-32000, error.code)
        harness.close()
    }

    @Test
    fun `cancels a request that timed out`() {
        val harness = Harness()
        val cancelled = CompletableDeferred<String>()
        harness.startServer { reader, _ ->
            reader.readMessage()
            // Waits for the client's $/cancelRequest.
            reader.readMessage()?.let { cancelled.complete(it.optString("method")) }
        }

        val error = assertThrows(LspTimeoutException::class.java) {
            runBlocking { harness.connection.requestObject("slow", null, 250) }
        }
        assertEquals("slow", error.method)

        val method = runBlocking { withTimeout(2_000) { cancelled.await() } }
        assertEquals("\$/cancelRequest", method)
        harness.close()
    }

    @Test
    fun `fails pending requests when the connection closes`() {
        val harness = Harness()
        harness.startServer { reader, _ -> reader.readMessage() }

        val pending = runBlocking {
            val deferred = async(Dispatchers.IO) {
                runCatching { harness.connection.requestObject("never", null, 5_000) }
            }
            delay(150)
            harness.connection.close()
            deferred.await()
        }
        val error = pending.exceptionOrNull()
        assertTrue(error is LspConnectionClosedException || error is IOException)
    }

    private fun result(id: Long, value: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("result", JSONObject().put("value", value))

    private class TestHandler : LspConnection.Handler {
        val notifications = CopyOnWriteArrayList<Pair<String, JSONObject?>>()
        var closed = false

        override suspend fun onRequest(
            method: String,
            params: JSONObject?
        ): LspConnection.ServerRequestResult =
            LspConnection.ServerRequestResult.Success(JSONObject().put("ok", true))

        override fun onNotification(method: String, params: JSONObject?) {
            notifications += method to params
        }

        override fun onClosed(cause: Throwable?) {
            closed = true
        }
    }

    private class Harness {
        private val clientToServer = PipedOutputStream()
        private val serverInput = PipedInputStream(clientToServer, 1 shl 16)
        private val serverToClient = PipedOutputStream()
        private val clientInput = PipedInputStream(serverToClient, 1 shl 16)

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val handler = TestHandler()
        val connection = LspConnection(
            input = clientInput,
            output = clientToServer,
            scope = scope,
            handler = handler,
            log = { _, _ -> },
            defaultTimeoutMs = 2_000
        ).also { it.start() }

        fun startServer(block: (LspFrameReader, OutputStream) -> Unit): Thread =
            thread(isDaemon = true) {
                runCatching { block(LspFrameReader(serverInput), serverToClient) }
            }

        fun close() {
            connection.close()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
