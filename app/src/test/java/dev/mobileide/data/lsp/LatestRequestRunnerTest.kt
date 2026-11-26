package dev.mobileide.data.lsp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestRequestRunnerTest {

    private val debounceMs = 150L
    private val timeoutMs = 5_000L

    @Test
    fun `a burst of triggers becomes one request for the last position`() = runTest {
        val runner = runner()
        val delivered = mutableListOf<Int>()
        var requests = 0

        repeat(5) { index ->
            runner.launch(request = { requests++; index }, onResult = { delivered += it })
        }
        advanceTimeBy(debounceMs)
        runCurrent()

        assertEquals(1, requests)
        assertEquals(listOf(4), delivered)
    }

    @Test
    fun `a newer trigger cancels the request in flight and only its answer is delivered`() =
        runTest {
            val runner = runner()
            val delivered = mutableListOf<Int>()
            val gates = Array(2) { CompletableDeferred<Unit>() }
            var requests = 0
            val request: suspend () -> Int = {
                val index = requests++
                gates[index].await()
                index
            }

            runner.launch(request = request, onResult = { delivered += it })
            advanceTimeBy(debounceMs)
            runCurrent()
            // The first request is at the server now, still unanswered.
            runner.launch(request = request, onResult = { delivered += it })
            advanceTimeBy(debounceMs)
            runCurrent()

            // The old answer arrives late; it belongs to a position already left.
            gates[0].complete(Unit)
            runCurrent()
            assertEquals(emptyList<Int>(), delivered)

            gates[1].complete(Unit)
            runCurrent()
            assertEquals(listOf(1), delivered)
            assertEquals(2, requests)
        }

    @Test
    fun `a request that never answers delivers nothing`() = runTest {
        val runner = runner(timeout = 1_000L)
        var delivered = 0

        runner.launch(
            request = { CompletableDeferred<Unit>().await(); 1 },
            onResult = { delivered++ }
        )
        advanceTimeBy(debounceMs + 1_000L)
        runCurrent()

        assertEquals(0, delivered)
    }

    @Test
    fun `separate triggers are each delivered`() = runTest {
        val runner = runner()
        val delivered = mutableListOf<Int>()

        for (value in 1..2) {
            runner.launch(request = { value }, onResult = { delivered += it })
            advanceTimeBy(debounceMs)
            runCurrent()
        }

        assertEquals(listOf(1, 2), delivered)
    }

    private fun TestScope.runner(timeout: Long = timeoutMs) = LatestRequestRunner<Int>(
        scope = backgroundScope,
        debounceMs = debounceMs,
        timeoutMs = timeout,
        ioDispatcher = StandardTestDispatcher(testScheduler)
    )
}
