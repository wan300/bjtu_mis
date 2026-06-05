package cn.edu.bjtu.mis.data.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {
    @Test
    fun sameKeyRunsOnlyOnce() = runBlocking {
        val singleFlight = SingleFlight()
        val calls = AtomicInteger(0)

        val results = (1..5).map {
            async {
                singleFlight.run("same") {
                    calls.incrementAndGet()
                    delay(50)
                    "value"
                }
            }
        }.awaitAll()

        assertEquals(List(5) { "value" }, results)
        assertEquals(1, calls.get())
    }

    @Test
    fun differentKeysRunIndependently() = runBlocking {
        val singleFlight = SingleFlight()
        val calls = AtomicInteger(0)

        val results = listOf("a", "b").map { key ->
            async {
                singleFlight.run(key) {
                    calls.incrementAndGet()
                    key
                }
            }
        }.awaitAll()

        assertEquals(listOf("a", "b"), results)
        assertEquals(2, calls.get())
    }

    @Test
    fun failureIsCleanedUpForNextAttempt() = runBlocking {
        val singleFlight = SingleFlight()
        val calls = AtomicInteger(0)

        runCatching {
            singleFlight.run("failing") {
                calls.incrementAndGet()
                throw IllegalStateException("boom")
            }
        }
        val value = singleFlight.run("failing") {
            calls.incrementAndGet()
            "ok"
        }

        assertEquals("ok", value)
        assertEquals(2, calls.get())
    }
}
