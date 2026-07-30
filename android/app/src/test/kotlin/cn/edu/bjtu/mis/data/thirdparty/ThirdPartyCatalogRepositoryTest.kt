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
            assertEquals("bjtu.demo", online.items.single().id)
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

    @Test
    fun excludesLegacyOrUnboundPluginsFromTheV3Snapshot() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "apiVersion": 3,
                          "contractProfile": "contract_v1",
                          "items": [
                            {"id":"bjtu.legacy","schemaVersion":3,"contractProfile":"legacy_v3_p0a","runtimeFloor":1,"compatibilityState":"compatible","publisherSubjectId":"github-owner:12345"},
                            {"id":"bjtu.unbound","schemaVersion":3,"contractProfile":"contract_v1","runtimeFloor":2,"compatibilityState":"compatible"}
                          ],
                          "nextCursor": null
                        }
                        """.trimIndent(),
                    ),
            )
            val repository = ThirdPartyCatalogRepository(
                client = BjtuHttpClient(AppCookieJar()),
                baseUrl = server.url("/").toString().trimEnd('/'),
                cacheRoot = temp.newFolder("catalog-v3-filter"),
            )

            assertTrue(repository.listPlugins().items.isEmpty())
        }
    }

    private val catalogJson = """
        {"apiVersion":3,"contractProfile":"contract_v1","items":[{"id":"bjtu.demo","name":"Demo","description":"Demo plugin","version":"1.0.0","author":"Alice","category":"other","tags":[],"repositoryUrl":"https://github.com/alice/demo","repository":"alice/demo","commitSha":"abcdef1234567","archiveSha256":"${"a".repeat(64)}","packageDigestSha256":"${"b".repeat(64)}","packageBytes":12,"packageFileCount":1,"schemaVersion":3,"contractProfile":"contract_v1","runtimeFloor":2,"capabilities":{"required":["runtime.lifecycle@1"],"optional":[]},"origins":{},"dataSchemaVersion":null,"publisherSubjectId":"github-owner:12345","publisherIdentity":{"subjectId":"github-owner:12345","ownerId":"12345","login":"alice","type":"User","transferStatus":"none"},"compatibilityState":"compatible","configuration":[],"validationWarnings":[],"verificationLevel":"automated","publishedAt":"2026-07-17T00:00:00Z","iconUrl":"https://bjtu.cc/icon","artifactUrl":"https://bjtu.cc/artifact"}],"nextCursor":null}
    """.trimIndent()
}
