package cn.edu.bjtu.mis.data.update

import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun parsesStableVersionsAndOrdersNumerically() {
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("v1.2.3"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("1.2.3"))
        assertTrue(SemanticVersion.parse("1.0.0")!! < SemanticVersion.parse("1.0.1")!!)
        assertTrue(SemanticVersion.parse("1.2.0")!! < SemanticVersion.parse("1.10.0")!!)
        assertTrue(SemanticVersion.parse("2.0.0")!! > SemanticVersion.parse("1.9.9")!!)
        assertNull(SemanticVersion.parse("v1.2.3-beta.1"))
        assertNull(SemanticVersion.parse("1.2"))
        assertNull(SemanticVersion.parse("latest"))
    }

    @Test
    fun checkForUpdateReturnsInfoWhenLatestReleaseIsNewer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(releaseJson("v1.0.1", body = "Fixes and improvements"))

            val update = checker(server, currentVersion = "1.0.0").checkForUpdate()

            assertEquals("1.0.0", update?.currentVersion)
            assertEquals("1.0.1", update?.latestVersion)
            assertEquals("https://github.com/wan300/bjtu_web/releases/tag/v1.0.1", update?.releaseUrl)
            assertEquals("Fixes and improvements", update?.releaseNotes)
        }
    }

    @Test
    fun checkForUpdateIgnoresSameAndOlderReleases() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(releaseJson("v1.0.0"))
            server.enqueue(releaseJson("v0.9.9"))

            assertNull(checker(server, currentVersion = "1.0.0").checkForUpdate())
            assertNull(checker(server, currentVersion = "1.0.0").checkForUpdate())
        }
    }

    @Test
    fun checkForUpdateIgnoresInvalidAndPrereleaseTags() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(releaseJson("v1.1.0-beta.1"))
            server.enqueue(releaseJson("latest"))

            assertNull(checker(server, currentVersion = "1.0.0").checkForUpdate())
            assertNull(checker(server, currentVersion = "1.0.0").checkForUpdate())
        }
    }

    @Test
    fun checkForUpdateReturnsNullOnHttpAndMalformedResponses() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(json("not json"))

            assertNull(checker(server, currentVersion = "1.0.0").checkForUpdate())
            assertNull(checker(server, currentVersion = "1.0.0").checkForUpdate())
        }
    }

    @Test
    fun checkForUpdateReturnsNullWhenCurrentVersionIsInvalid() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(releaseJson("v1.0.1"))

            assertNull(checker(server, currentVersion = "1.0").checkForUpdate())
            assertEquals(0, server.requestCount)
        }
    }

    private fun checker(server: MockWebServer, currentVersion: String?): AppUpdateChecker =
        AppUpdateChecker(
            client = BjtuHttpClient(AppCookieJar()),
            currentVersionProvider = { currentVersion },
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
        )

    private fun releaseJson(tagName: String, body: String? = null): MockResponse =
        json(
            """
            {
              "tag_name": "$tagName",
              "html_url": "https://github.com/wan300/bjtu_web/releases/tag/$tagName",
              "body": ${body?.let { "\"$it\"" } ?: "null"},
              "published_at": "2026-05-27T00:00:00Z"
            }
            """.trimIndent(),
        )

    private fun json(body: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}
