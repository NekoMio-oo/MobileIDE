package dev.mobileide.data.runtime

import dev.mobileide.domain.runtime.ExecutionEvent
import dev.mobileide.domain.runtime.ExecutionRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExecutorTest {

    @Test
    fun `stdout stderr and exit code are reported`() = runBlocking {
        val events = withTimeout(10_000) {
            ProcessExecutor().execute(
                ExecutionRequest(
                    command = listOf("/bin/sh", "-c", "echo out; echo err >&2; exit 3")
                )
            ).toList()
        }

        val output = events.filterIsInstance<ExecutionEvent.Output>()
        assertEquals("out", output.first { !it.stderr }.line)
        assertEquals("err", output.first { it.stderr }.line)
        val exit = events.filterIsInstance<ExecutionEvent.Exit>().single()
        assertEquals(3, exit.code)
        assertTrue(!exit.timedOut)
    }

    @Test
    fun `timeout kills the process`() = runBlocking {
        val started = System.currentTimeMillis()
        val events = withTimeout(10_000) {
            ProcessExecutor().execute(
                ExecutionRequest(
                    command = listOf("/bin/sh", "-c", "sleep 5"),
                    timeoutMs = 500
                )
            ).toList()
        }
        val elapsed = System.currentTimeMillis() - started

        val exit = events.filterIsInstance<ExecutionEvent.Exit>().single()
        assertTrue(exit.timedOut)
        assertEquals(-1, exit.code)
        assertTrue("timeout took ${elapsed}ms", elapsed < 4_000)
    }

    @Test
    fun `missing executable is reported instead of crashing`() = runBlocking {
        val events = withTimeout(10_000) {
            ProcessExecutor().execute(
                ExecutionRequest(command = listOf("/definitely/not/here"))
            ).toList()
        }

        assertTrue(events.filterIsInstance<ExecutionEvent.Output>().any { it.stderr })
        assertEquals(-1, events.filterIsInstance<ExecutionEvent.Exit>().single().code)
    }
}
