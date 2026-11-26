package dev.mobileide.data.lsp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Paces a request that fires on every keystroke but is expensive to answer.
 *
 * Each trigger cancels the previous one, waits [debounceMs] so a burst of
 * typing collapses into one request, then runs [request] on [ioDispatcher]
 * under a hard [timeoutMs]. Only the newest trigger delivers: the job is
 * checked again when the answer arrives, so a result for a position the user
 * has already left is dropped instead of published.
 *
 * A superseded or timed-out request delivers nothing.
 */
class LatestRequestRunner<T>(
    private val scope: CoroutineScope,
    private val debounceMs: Long,
    private val timeoutMs: Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private var job: Job? = null

    /** Thread-safe: triggers may come from any thread, such as Sora's completion thread. */
    @Synchronized
    fun launch(request: suspend () -> T, onResult: (T) -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(debounceMs)
            val value = withTimeoutOrNull(timeoutMs) {
                withContext(ioDispatcher) { request() }
            } ?: return@launch
            currentCoroutineContext().ensureActive()
            onResult(value)
        }
    }
}
