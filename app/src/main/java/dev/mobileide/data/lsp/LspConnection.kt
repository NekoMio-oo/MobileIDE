package dev.mobileide.data.lsp

import dev.mobileide.domain.lsp.LspLogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** The server answered with a JSON-RPC error object. */
class LspResponseException(val code: Int, message: String) : IOException(message)

/** No response within the timeout; the request was cancelled. */
class LspTimeoutException(val method: String) : IOException("Timed out waiting for $method")

/** The pipe or process is gone. */
class LspConnectionClosedException(message: String = "Language server connection closed") :
    IOException(message)

/**
 * JSON-RPC 2.0 over a byte stream, framed for LSP.
 *
 * Reads happen on a single dispatcher thread; writes are serialized. Requests
 * are matched by id and may time out or be cancelled, and server-initiated
 * requests are answered so a server never blocks waiting for the client.
 */
class LspConnection(
    private val input: InputStream,
    private val output: OutputStream,
    private val scope: CoroutineScope,
    private val handler: Handler,
    private val log: (LspLogLevel, String) -> Unit,
    private val defaultTimeoutMs: Long = 15_000L
) {

    interface Handler {
        /** Handles a server-initiated request; return [ServerRequestResult]. */
        suspend fun onRequest(method: String, params: JSONObject?): ServerRequestResult

        fun onNotification(method: String, params: JSONObject?)

        /** Called when the stream ends or breaks, not for a client-initiated close. */
        fun onClosed(cause: Throwable?)
    }

    sealed interface ServerRequestResult {
        data class Success(val result: Any?) : ServerRequestResult
        data object Unhandled : ServerRequestResult
    }

    private val nextId = AtomicLong(0L)
    private val pending = mutableMapOf<Long, CompletableDeferred<Any?>>()
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    private var readJob: Job? = null

    fun start() {
        if (readJob != null) return
        readJob = scope.launch(Dispatchers.IO) { readLoop() }
    }

    val isClosed: Boolean
        get() = closed.get()

    suspend fun requestRaw(
        method: String,
        params: Any?,
        timeoutMs: Long = defaultTimeoutMs
    ): Any? {
        if (closed.get()) throw LspConnectionClosedException()
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<Any?>()
        synchronized(pending) { pending[id] = deferred }

        val message = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        if (params != null) message.put("params", params)
        try {
            send(message)
        } catch (error: IOException) {
            synchronized(pending) { pending.remove(id) }
            throw error
        }

        try {
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            cancel(id, method)
            throw LspTimeoutException(method)
        } catch (cancelled: CancellationException) {
            // The caller moved on (typing, tab switch): tell the server to stop.
            cancel(id, method)
            throw cancelled
        } finally {
            synchronized(pending) { pending.remove(id) }
        }
    }

    suspend fun requestObject(
        method: String,
        params: Any?,
        timeoutMs: Long = defaultTimeoutMs
    ): JSONObject? = requestRaw(method, params, timeoutMs) as? JSONObject

    suspend fun requestArray(
        method: String,
        params: Any?,
        timeoutMs: Long = defaultTimeoutMs
    ): JSONArray? = requestRaw(method, params, timeoutMs) as? JSONArray

    fun notify(method: String, params: Any?) {
        if (closed.get()) return
        val message = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
        if (params != null) message.put("params", params)
        runCatching { send(message) }.onFailure { log(LspLogLevel.ERROR, "Send failed: $it") }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        readJob?.cancel()
        runCatching { input.close() }
        runCatching { output.close() }
        failPending(LspConnectionClosedException())
    }

    private suspend fun readLoop() {
        val reader = LspFrameReader(input)
        var cause: Throwable? = null
        try {
            while (currentCoroutineContext().isActive) {
                val message = try {
                    reader.readMessage()
                } catch (invalid: LspMessageFormatException) {
                    log(LspLogLevel.WARN, invalid.message ?: "Invalid message payload")
                    continue
                }
                if (message == null) break
                dispatch(message)
            }
        } catch (cancelled: CancellationException) {
            // Expected on close.
        } catch (error: Throwable) {
            cause = error
            log(LspLogLevel.ERROR, "Read loop stopped: ${error.message}")
        } finally {
            failPending(cause ?: LspConnectionClosedException())
            if (!closed.get()) {
                closed.set(true)
                handler.onClosed(cause)
            }
        }
    }

    private fun dispatch(message: JSONObject) {
        val method = message.optString("method").takeIf { it.isNotEmpty() }
        val hasId = message.has("id") && !message.isNull("id")
        if (method == null) {
            if (!hasId) {
                log(LspLogLevel.WARN, "Response without id: ${message.toString().take(200)}")
                return
            }
            val id = message.optLong("id", -1L)
            val deferred = synchronized(pending) { pending.remove(id) }
            if (deferred == null) {
                log(LspLogLevel.WARN, "Response for unknown request $id")
                return
            }
            val error = message.optJSONObject("error")
            if (error != null) {
                deferred.completeExceptionally(
                    LspResponseException(error.optInt("code"), error.optString("message"))
                )
            } else {
                deferred.complete(normalize(message.opt("result")))
            }
            return
        }

        if (hasId) {
            val id = message.optLong("id", -1L)
            scope.launch {
                val result = try {
                    handler.onRequest(method, message.optJSONObject("params"))
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    log(LspLogLevel.WARN, "Request handler failed for $method: ${error.message}")
                    ServerRequestResult.Unhandled
                }
                when (result) {
                    is ServerRequestResult.Success -> respond(id, result.result)
                    ServerRequestResult.Unhandled -> respondError(id, -32601, "Method not found: $method")
                }
            }
        } else {
            handler.onNotification(method, message.optJSONObject("params"))
        }
    }

    private fun respond(id: Long, result: Any?) {
        val message = JSONObject().put("jsonrpc", "2.0").put("id", id)
        message.put("result", result ?: JSONObject.NULL)
        runCatching { send(message) }
    }

    private fun respondError(id: Long, code: Int, text: String) {
        val message = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject().put("code", code).put("message", text))
        runCatching { send(message) }
    }

    private fun cancel(id: Long, method: String) {
        if (closed.get()) return
        runCatching {
            send(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "$/cancelRequest")
                    .put("params", JSONObject().put("id", id))
            )
            log(LspLogLevel.INFO, "Cancelled request $id ($method)")
        }
    }

    private fun send(message: JSONObject) {
        if (closed.get()) throw LspConnectionClosedException()
        val bytes = LspMessageCodec.encode(message)
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
    }

    private fun failPending(cause: Throwable) {
        val waiting = synchronized(pending) {
            val values = pending.values.toList()
            pending.clear()
            values
        }
        waiting.forEach { it.completeExceptionally(cause) }
    }

    private fun normalize(value: Any?): Any? =
        if (value == null || value == JSONObject.NULL) null else value
}
