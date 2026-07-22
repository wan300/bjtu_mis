package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThirdPartyCatalogRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun cachesLastSuccessfulFirstPageAndUsesItOnServerFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(catalogJson))
            server.enqueue(MockResponse().setResponseCode(500).setBody("failed"))
            val repository = ThirdPartyCatalogRepository(
                client = BjtuHttpClient(AppCookieJar()),
                baseUrl = server.url("/").toString().trimEnd('/'),
                cacheRoot = temp.newFolder("catalog"),
            )

            val online = repository.listPlugins()
            val cached = repository.listPlugins()

            assertFalse(online.fromCache)
            assertTrue(cached.fromCache)
            assertEquals("bjtu.demo", cached.items.single().id)
        }
    }

    @Test
    fun filtersTheBaseSnapshotWhenAFilteredRequestFails() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(catalogJson))
            server.enqueue(MockResponse().setResponseCode(500).setBody("failed"))
            val repository = ThirdPartyCatalogRepository(
                client = BjtuHttpClient(AppCookieJar()),
                baseUrl = server.url("/").toString().trimEnd('/'),
                cacheRoot = temp.newFolder("catalog-filtered"),
            )

            repository.listPlugins()
            val cached = repository.listPlugins(query = "not-present")

            assertTrue(cached.fromCache)
            assertTrue(cached.items.isEmpty())
        }
    }

    private val catalogJson = """
        {"items":[{"id":"bjtu.demo","name":"Demo","description":"Demo plugin","version":"1.0.0","author":"Alice","category":"other","tags":[],"repositoryUrl":"https://github.com/alice/demo","repository":"alice/demo","commitSha":"abcdef1234567","archiveSha256":"${"a".repeat(64)}","packageDigestSha256":"${"b".repeat(64)}","packageBytes":12,"packageFileCount":1,"permissions":{"required":[],"optional":[]},"allowedOrigins":[],"configuration":[],"validationWarnings":[],"verificationLevel":"automated","publishedAt":"2026-07-17T00:00:00Z","iconUrl":"https://bjtu.cc/icon","artifactUrl":"https://bjtu.cc/artifact"}],"nextCursor":null}
    """.trimIndent()
}
