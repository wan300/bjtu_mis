package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThirdPartyServiceInstallerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun parsesOnlyGitHubRepositoryRootUrls() {
        val ref = ThirdPartyServiceInstaller.parseGitHubRepositoryUrl("https://github.com/owner/repo")

        assertEquals("owner", ref.owner)
        assertEquals("repo", ref.repo)
        assertEquals("https://github.com/owner/repo", ref.canonicalUrl)

        listOf(
            "http://github.com/owner/repo",
            "https://example.com/owner/repo",
            "https://github.com/owner/repo/tree/main",
            "https://github.com/owner/repo?tab=readme",
            "https://github.com/owner/repo.git",
        ).forEach { url ->
            assertThrows(ThirdPartyServiceException::class.java) {
                ThirdPartyServiceInstaller.parseGitHubRepositoryUrl(url)
            }
        }
    }

    @Test
    fun installsValidGithubZipPackage() = runBlocking {
        val root = temp.newFolder("services")
        val zip = serviceZip(
            "repo-main/$THIRD_PARTY_MANIFEST_FILE_NAME" to validManifest(),
            "repo-main/dist/index.html" to "<html></html>",
            "repo-main/dist/icon.svg" to "<svg></svg>",
        )

        val installed = installer(root).installPackageFromZip(
            source = GitHubRepositoryRef("alice", "demo", "https://github.com/alice/demo"),
            defaultBranch = "main",
            commitSha = "abcdef1234567890",
            zipFile = zip,
        )

        assertEquals("bjtu.demo", installed.manifest.id)
        assertTrue(installed.packageDigestSha256.matches(Regex("^[a-f0-9]{64}$")))
        assertEquals(2, installed.packageFileCount)
        assertTrue(File(installed.installDir, "index.html").isFile)
        assertTrue(File(installed.installDir, "icon.svg").isFile)
    }

    @Test
    fun installsValidDirectoryPackage() = runBlocking {
        val root = temp.newFolder("services")
        val packageRoot = temp.newFolder("package-root")
        File(packageRoot, THIRD_PARTY_MANIFEST_FILE_NAME).writeText(validManifest())
        File(packageRoot, "dist").mkdirs()
        File(packageRoot, "dist/index.html").writeText("<html></html>")
        File(packageRoot, "dist/icon.svg").writeText("<svg></svg>")

        val installed = installer(root).installPackageFromDirectory(
            source = GitHubRepositoryRef("bundled", "demo", "asset://third-party-services/bjtu.demo"),
            defaultBranch = "bundled",
            commitSha = "abcdef1234567890",
            packageRoot = packageRoot,
        )

        assertEquals("bjtu.demo", installed.manifest.id)
        assertTrue(installed.packageDigestSha256.matches(Regex("^[a-f0-9]{64}$")))
        assertEquals(2, installed.packageFileCount)
        assertTrue(File(installed.installDir, "index.html").isFile)
        assertTrue(File(installed.installDir, "icon.svg").isFile)
    }

    @Test
    fun preparesStagingPackageBeforeCommit() = runBlocking {
        val root = temp.newFolder("services")
        val zip = serviceZip(
            "repo-main/$THIRD_PARTY_MANIFEST_FILE_NAME" to validManifest(),
            "repo-main/dist/index.html" to "<html></html>",
            "repo-main/dist/icon.svg" to "<svg></svg>",
        )
        val installer = installer(root)

        val prepared = installer.preparePackageFromZip(
            source = GitHubRepositoryRef("alice", "demo", "https://github.com/alice/demo"),
            defaultBranch = "main",
            commitSha = "abcdef1234567890",
            zipFile = zip,
        )

        assertTrue(prepared.stagingDir.path.contains("${File.separator}staging${File.separator}"))
        assertTrue(File(prepared.stagingDir, "index.html").isFile)
        assertFalse(File(root, "installed/bjtu.demo/abcdef1234567890/index.html").isFile)

        val installed = installer.commitPreparedImport(prepared.token)

        assertTrue(File(installed.installDir, "index.html").isFile)
        assertFalse(prepared.stagingDir.exists())
    }

    @Test
    fun rejectsZipPathTraversal() {
        val root = temp.newFolder("services")
        val zip = serviceZip(
            "repo-main/$THIRD_PARTY_MANIFEST_FILE_NAME" to validManifest(),
            "repo-main/dist/index.html" to "<html></html>",
            "repo-main/dist/icon.svg" to "<svg></svg>",
            "../escape.txt" to "nope",
        )

        assertThrows(ThirdPartyServiceException::class.java) {
            runBlocking {
                installer(root).installPackageFromZip(
                    source = GitHubRepositoryRef("alice", "demo", "https://github.com/alice/demo"),
                    defaultBranch = "main",
                    commitSha = "abcdef1",
                    zipFile = zip,
                )
            }
        }
    }

    @Test
    fun rejectsZipWithTooManyDirectoryEntries() {
        val root = temp.newFolder("services")
        val zip = directoryFloodZip(1001)

        assertThrows(ThirdPartyServiceException::class.java) {
            runBlocking {
                installer(root).installPackageFromZip(
                    source = GitHubRepositoryRef("alice", "demo", "https://github.com/alice/demo"),
                    defaultBranch = "main",
                    commitSha = "abcdef1",
                    zipFile = zip,
                )
            }
        }
    }

    @Test
    fun rejectsMissingDistEntrypoint() {
        val root = temp.newFolder("services")
        val zip = serviceZip(
            "repo-main/$THIRD_PARTY_MANIFEST_FILE_NAME" to validManifest(),
            "repo-main/dist/icon.svg" to "<svg></svg>",
        )

        assertThrows(ThirdPartyServiceException::class.java) {
            runBlocking {
                installer(root).installPackageFromZip(
                    source = GitHubRepositoryRef("alice", "demo", "https://github.com/alice/demo"),
                    defaultBranch = "main",
                    commitSha = "abcdef1",
                    zipFile = zip,
                )
            }
        }
    }

    @Test
    fun importsFromMockGithubApiAndPinsCommitSha() = runBlocking {
        MockWebServer().use { server ->
            val zip = serviceZip(
                "repo-main/$THIRD_PARTY_MANIFEST_FILE_NAME" to validManifest(),
                "repo-main/dist/index.html" to "<html></html>",
                "repo-main/dist/icon.svg" to "<svg></svg>",
            )
            server.enqueue(json("""{"default_branch":"main","owner":{"id":12345}}"""))
            server.enqueue(json("""{"object":{"sha":"abc1234def5678"}}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(zip.readBytes())))

            val installed = ThirdPartyServiceInstaller(
                client = BjtuHttpClient(AppCookieJar()),
                servicesRoot = temp.newFolder("services"),
                apiBaseUrl = server.url("/").toString().trimEnd('/'),
            ).installFromGitHub("https://github.com/alice/demo")

            assertEquals("main", installed.defaultBranch)
            assertEquals("abc1234def5678", installed.commitSha)
            assertEquals("https://github.com/alice/demo", installed.source.canonicalUrl)
            assertEquals(3, server.requestCount)
            server.takeRequest()
            server.takeRequest()
            assertTrue(server.takeRequest().path!!.contains("/zipball/abc1234def5678"))
        }
    }

    @Test
    fun deletesOnlyInstalledServiceChildren() {
        runBlocking {
            val root = temp.newFolder("services")
            val zip = serviceZip(
                "repo-main/$THIRD_PARTY_MANIFEST_FILE_NAME" to validManifest(),
                "repo-main/dist/index.html" to "<html></html>",
                "repo-main/dist/icon.svg" to "<svg></svg>",
            )
            val installer = installer(root)
            installer.installPackageFromZip(
                source = GitHubRepositoryRef("alice", "demo", "https://github.com/alice/demo"),
                defaultBranch = "main",
                commitSha = "abcdef1234567890",
                zipFile = zip,
            )
            val unrelated = File(root, "installed/other.keep").apply {
                parentFile?.mkdirs()
                writeText("keep")
            }

            installer.deleteInstalledService("bjtu.demo")

            assertTrue(unrelated.isFile)
            assertFalse(File(root, "installed/bjtu.demo").exists())
            assertThrows(ThirdPartyServiceException::class.java) {
                installer.deleteInstalledService("../escape")
            }
        }
    }

    private fun installer(root: File): ThirdPartyServiceInstaller =
        ThirdPartyServiceInstaller(BjtuHttpClient(AppCookieJar()), root)

    private fun validManifest(): String =
        """
        {
          "schema_version": 3,
          "id": "bjtu.demo",
          "name": "Demo",
          "version": "1.0.0",
          "entrypoint": "index.html",
          "icon": "icon.svg",
          "capabilities": {
            "required": ["runtime.lifecycle@1", "network.request@1"],
            "optional": ["identity.profile@1"]
          },
          "origins": {
            "connect": ["https://api.example.com"]
          }
        }
        """.trimIndent()

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

    private fun directoryFloodZip(entryCount: Int): File {
        val file = temp.newFile("directory-flood-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(entryCount) { index ->
                zip.putNextEntry(ZipEntry("directory-$index/"))
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
