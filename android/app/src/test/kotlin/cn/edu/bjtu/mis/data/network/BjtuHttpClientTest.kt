package cn.edu.bjtu.mis.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class BjtuHttpClientTest {
    @Test
    fun redirectsCanBeDisabledWithoutChangingDefaultBehavior() = runBlocking {
        MockWebServer().use { server ->
            val client = BjtuHttpClient(AppCookieJar())
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/target"))

            expectIOException {
                client.getBytes(server.url("/blocked").toString(), followRedirects = false)
            }
            assertEquals(1, server.requestCount)

            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/target"))
            server.enqueue(MockResponse().setBody("image"))
            val response = client.getBytes(server.url("/allowed").toString())
            assertEquals(server.url("/target").toString(), response.url)
            assertEquals(3, server.requestCount)
            assertArrayEquals("image".toByteArray(), response.body)
        }
    }

    @Test
    fun knownOversizedBodyIsRejectedBeforeReading() = runBlocking {
        MockWebServer().use { server ->
            var bytesRead = 0L
            server.enqueue(MockResponse().setBody("12345"))

            expectIOException {
                BjtuHttpClient(AppCookieJar()).getBytes(
                    server.url("/").toString(), maxBytes = 4, onBytesRead = { bytesRead += it },
                )
            }

            assertEquals(0L, bytesRead)
        }
    }

    @Test
    fun chunkedBodyAtLimitIsAcceptedAndCounted() = runBlocking {
        MockWebServer().use { server ->
            var bytesRead = 0L
            server.enqueue(MockResponse().setChunkedBody("1234", 2))

            val response = BjtuHttpClient(AppCookieJar()).getBytes(
                server.url("/").toString(), maxBytes = 4, onBytesRead = { bytesRead += it },
            )

            assertArrayEquals("1234".toByteArray(), response.body)
            assertEquals(4L, bytesRead)
        }
    }

    @Test
    fun oversizedChunkedBodyReadsOnlyOneProbeByteBeyondLimit() = runBlocking {
        MockWebServer().use { server ->
            var bytesRead = 0L
            server.enqueue(MockResponse().setChunkedBody("123456789", 2))

            expectIOException {
                BjtuHttpClient(AppCookieJar()).getBytes(
                    server.url("/").toString(), maxBytes = 4, onBytesRead = { bytesRead += it },
                )
            }

            assertEquals(5L, bytesRead)
        }
    }

    @Test
    fun bytesReadBeforeDisconnectAreStillReported() = runBlocking {
        MockWebServer().use { server ->
            var bytesRead = 0L
            val bodySize = 64 * 1024
            server.enqueue(
                MockResponse().setBody(okio.Buffer().write(ByteArray(bodySize)))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )

            expectIOException {
                BjtuHttpClient(AppCookieJar()).getBytes(
                    server.url("/").toString(), maxBytes = bodySize.toLong(),
                    onBytesRead = { bytesRead += it },
                )
            }

            assertTrue(bytesRead in 1 until bodySize.toLong())
        }
    }

    @Test
    fun byteRequestsRespectCallTimeoutWhileReadingBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("image").setBodyDelay(500, TimeUnit.MILLISECONDS))

            expectIOException {
                BjtuHttpClient(AppCookieJar()).getBytes(
                    server.url("/").toString(), timeoutMillis = 100, maxBytes = 10,
                )
            }
        }
    }

    @Test
    fun emptyBodySupportsZeroByteLimit() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(""))

            val response = BjtuHttpClient(AppCookieJar()).getBytes(server.url("/").toString(), maxBytes = 0)

            assertTrue(response.body.isEmpty())
        }
    }

    private suspend fun expectIOException(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IOException")
        } catch (_: IOException) {
            // Expected transport rejection or bounded read failure.
        }
    }
}
