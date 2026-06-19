package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThirdPartyServiceRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun previewDoesNotWriteRoomAndCommitPersistsService() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            val repository = repository(server, dao)
            enqueueGithubPackage(server)

            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")

            assertEquals("bjtu.demo", preview.manifest.id)
            assertNull(dao.saved["bjtu.demo"])

            val result = repository.commitPreparedImport(preview.token)

            assertEquals("bjtu.demo", result.service.serviceId)
            assertTrue(result.service.needsReview)
            assertFalse(result.service.enabled)
            assertTrue(result.service.grantedPermissions.isEmpty())
            assertTrue(result.service.packageDigestSha256.matches(Regex("^[a-f0-9]{64}$")))
            assertEquals(result.service.serviceId, dao.saved["bjtu.demo"]?.serviceId)
        }
    }

    @Test
    fun updateResetsExistingAuthorization() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            dao.saved["bjtu.demo"] = existingAuthorizedEntity()
            val repository = repository(server, dao)
            enqueueGithubPackage(server)

            val preview = repository.prepareImportFromGitHub("https://github.com/alice/demo")
            val result = repository.commitPreparedImport(preview.token)

            assertTrue(result.updatedExisting)
            assertTrue(result.service.needsReview)
            assertFalse(result.service.enabled)
            assertTrue(result.service.grantedPermissions.isEmpty())
            assertEquals("2026-06-01T00:00:00Z", result.service.installedAt)
        }
    }

    @Test
    fun listServicesReturnsEmptyWhenNoImportedOrBundledServices() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            val repository = repository(server, dao)

            val services = repository.listServices()

            assertTrue(services.isEmpty())
        }
    }

    @Test
    fun listServicesRemovesObsoleteBundledServicesThatAreNoLongerShipped() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            val servicesRoot = temp.newFolder("services-${System.nanoTime()}")
            val legacyInstallDir = File(servicesRoot, "installed/com.jijiang.campus-service/legacy").apply {
                mkdirs()
                resolve("index.html").writeText("<html></html>")
            }
            dao.saved["com.jijiang.campus-service"] = ThirdPartyServiceEntity(
                serviceId = "com.jijiang.campus-service",
                name = "Legacy bundled service",
                description = "Legacy bundled service",
                version = "1.0.0",
                author = "bundled",
                sourceUrl = "asset://third-party-services/com.jijiang.campus-service",
                githubOwner = "bundled",
                githubRepo = "legacy-bundled",
                defaultBranch = "bundled",
                commitSha = "legacy",
                packageDigestSha256 = "b".repeat(64),
                manifestJson = validManifest(),
                grantedPermissionsJson = AppJson.encodeToString(emptyList<String>()),
                allowedOriginsJson = AppJson.encodeToString(emptyList<String>()),
                installDir = legacyInstallDir.absolutePath,
                entrypoint = "index.html",
                icon = "icon.svg",
                enabled = false,
                needsReview = true,
                installedAt = "2026-06-01T00:00:00Z",
                updatedAt = "2026-06-01T00:00:00Z",
            )
            val repository = repository(
                server = server,
                dao = dao,
                servicesRoot = servicesRoot,
                bundledProvider = EmptyBundledProvider,
            )

            val services = repository.listServices()

            assertTrue(services.isEmpty())
            assertNull(dao.saved["com.jijiang.campus-service"])
            assertFalse(File(servicesRoot, "installed/com.jijiang.campus-service").exists())
        }
    }

    @Test
    fun listServicesInstallsBundledServiceFromProviderPendingUserGrant() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeThirdPartyServiceDao()
            val installDir = temp.newFolder("bundled-demo-install").apply {
                resolve("index.html").writeText("<html></html>")
            }
            val bundledProvider = FakeBundledProvider(installDir)
            val repository = ThirdPartyServiceRepository(
                dao = dao,
                installer = ThirdPartyServiceInstaller(
                    client = BjtuHttpClient(AppCookieJar()),
                    servicesRoot = temp.newFolder("services-${System.nanoTime()}"),
                    apiBaseUrl = server.url("/").toString().trimEnd('/'),
                ),
                bundledProvider = bundledProvider,
            )

            val services = repository.listServices()

            assertEquals(1, services.size)
            val service = services.single()
            assertEquals("bjtu.bundled.demo", service.serviceId)
            assertEquals("asset://third-party-services/bjtu.bundled.demo", service.sourceUrl)
            assertTrue(service.grantedPermissions.isEmpty())
            assertFalse(service.enabled)
            assertTrue(service.needsReview)
            assertEquals(1, bundledProvider.calls)

            repository.listServices()

            assertEquals(1, bundledProvider.calls)
        }
    }

    private fun repository(
        server: MockWebServer,
        dao: FakeThirdPartyServiceDao,
        servicesRoot: File = temp.newFolder("services-${System.nanoTime()}"),
        bundledProvider: ThirdPartyBundledServiceProvider? = null,
    ): ThirdPartyServiceRepository =
        ThirdPartyServiceRepository(
            dao = dao,
            installer = ThirdPartyServiceInstaller(
                client = BjtuHttpClient(AppCookieJar()),
                servicesRoot = servicesRoot,
                apiBaseUrl = server.url("/").toString().trimEnd('/'),
            ),
            bundledProvider = bundledProvider,
        )

    private fun enqueueGithubPackage(server: MockWebServer) {
        val zip = serviceZip(
            "repo-main/bjtu-service.json" to validManifest(),
            "repo-main/dist/index.html" to "<html></html>",
            "repo-main/dist/icon.svg" to "<svg></svg>",
        )
        server.enqueue(json("""{"default_branch":"main"}"""))
        server.enqueue(json("""{"object":{"sha":"abc1234def5678"}}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(zip.readBytes())))
    }

    private fun existingAuthorizedEntity(): ThirdPartyServiceEntity =
        ThirdPartyServiceEntity(
            serviceId = "bjtu.demo",
            name = "Demo",
            description = "Demo service",
            version = "1.0.0",
            author = "Alice",
            sourceUrl = "https://github.com/alice/demo",
            githubOwner = "alice",
            githubRepo = "demo",
            defaultBranch = "main",
            commitSha = "oldcommit",
            packageDigestSha256 = "old-digest",
            manifestJson = validManifest(),
            grantedPermissionsJson = AppJson.encodeToString(listOf("identity.profile.read")),
            allowedOriginsJson = AppJson.encodeToString(listOf("https://api.example.com")),
            installDir = temp.newFolder("old-install").absolutePath,
            entrypoint = "index.html",
            icon = "icon.svg",
            enabled = true,
            needsReview = false,
            installedAt = "2026-06-01T00:00:00Z",
            updatedAt = "2026-06-01T00:00:00Z",
        )

    private fun validManifest(): String =
        AppJson.encodeToString(
            ThirdPartyServiceManifest(
                schemaVersion = 1,
                id = "bjtu.demo",
                name = "Demo",
                description = "Demo service",
                version = "1.0.0",
                entrypoint = "index.html",
                icon = "icon.svg",
                author = "Alice",
                permissions = ThirdPartyServicePermissionDeclaration(
                    required = listOf("identity.profile.read"),
                    optional = listOf("academic.timetable.read"),
                ),
                allowedOrigins = listOf("https://api.example.com"),
            )
        )

    private fun serviceZip(vararg entries: Pair<String, String>): File {
        val file = temp.newFile("service-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun json(body: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}

private class FakeBundledProvider(
    private val installDir: File,
) : ThirdPartyBundledServiceProvider {
    var calls = 0
    override val bundledServiceIds: Set<String> = setOf("bjtu.bundled.demo")

    override suspend fun installMissingOrUpdated(
        existingServices: Map<String, ThirdPartyServiceEntity>,
    ): List<InstalledBundledThirdPartyService> {
        calls += 1
        if (existingServices.containsKey("bjtu.bundled.demo")) return emptyList()
        return listOf(
            InstalledBundledThirdPartyService(
                packageInfo = InstalledThirdPartyServicePackage(
                    manifest = ThirdPartyServiceManifest(
                        schemaVersion = 1,
                        id = "bjtu.bundled.demo",
                        name = "Bundled Demo",
                        description = "Bundled demo service",
                        version = "1.0.0",
                        entrypoint = "index.html",
                        icon = "icon.svg",
                        author = "bundled-demo",
                        permissions = ThirdPartyServicePermissionDeclaration(
                            required = listOf("identity.profile.read"),
                        ),
                        allowedOrigins = listOf("https://api.example.com"),
                    ),
                    source = GitHubRepositoryRef(
                        owner = "bundled",
                        repo = "bundled-demo",
                        canonicalUrl = "asset://third-party-services/bjtu.bundled.demo",
                    ),
                    defaultBranch = "bundled",
                    commitSha = "abcdef1234567890abcdef1234567890abcdef12",
                    packageDigestSha256 = "a".repeat(64),
                    packageBytes = 128,
                    packageFileCount = 1,
                    installDir = installDir,
                ),
                defaultGrantedPermissions = emptySet(),
            )
        )
    }
}

private object EmptyBundledProvider : ThirdPartyBundledServiceProvider {
    override val bundledServiceIds: Set<String> = emptySet()

    override suspend fun installMissingOrUpdated(
        existingServices: Map<String, ThirdPartyServiceEntity>,
    ): List<InstalledBundledThirdPartyService> = emptyList()
}

private class FakeThirdPartyServiceDao : ThirdPartyServiceDao {
    val saved = linkedMapOf<String, ThirdPartyServiceEntity>()

    override suspend fun saveService(service: ThirdPartyServiceEntity) {
        saved[service.serviceId] = service
    }

    override suspend fun listServices(): List<ThirdPartyServiceEntity> =
        saved.values.toList()

    override suspend fun getService(serviceId: String): ThirdPartyServiceEntity? =
        saved[serviceId]

    override suspend fun deleteService(serviceId: String) {
        saved.remove(serviceId)
    }

    override suspend fun updateGrantState(
        serviceId: String,
        grantedPermissionsJson: String,
        enabled: Boolean,
        needsReview: Boolean,
        updatedAt: String,
    ) {
        saved[serviceId] = saved.getValue(serviceId).copy(
            grantedPermissionsJson = grantedPermissionsJson,
            enabled = enabled,
            needsReview = needsReview,
            updatedAt = updatedAt,
        )
    }
}
