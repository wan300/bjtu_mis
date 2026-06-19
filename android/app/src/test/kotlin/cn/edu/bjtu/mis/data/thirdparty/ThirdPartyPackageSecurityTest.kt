package cn.edu.bjtu.mis.data.thirdparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThirdPartyPackageSecurityTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun digestIsStableAndSensitiveToPathAndContent() {
        val dist = temp.newFolder("dist")
        File(dist, "index.html").writeText("<html>one</html>")
        File(dist, "assets/app.js").apply {
            parentFile?.mkdirs()
            writeText("console.log('demo')")
        }

        val first = ThirdPartyPackageDigests.computeDistDigest(dist)
        val second = ThirdPartyPackageDigests.computeDistDigest(dist)
        assertEquals(first.sha256, second.sha256)
        assertEquals(2, first.fileCount)

        File(dist, "assets/app.js").writeText("console.log('changed')")
        val changedContent = ThirdPartyPackageDigests.computeDistDigest(dist)
        assertNotEquals(first.sha256, changedContent.sha256)

        val renamed = temp.newFolder("renamed")
        File(renamed, "index.html").writeText("<html>one</html>")
        File(renamed, "assets/renamed.js").apply {
            parentFile?.mkdirs()
            writeText("console.log('demo')")
        }
        val changedPath = ThirdPartyPackageDigests.computeDistDigest(renamed)
        assertNotEquals(first.sha256, changedPath.sha256)
    }

    @Test
    fun webViewPolicyTrustsInstallDirAndAllowedOriginsOnly() {
        val install = temp.newFolder("installed")
        val index = File(install, "index.html").apply { writeText("<html></html>") }

        assertTrue(
            ThirdPartyWebViewAccessPolicy.isTrustedUrl(
                index.toURI().toString(),
                install,
                listOf("https://api.example.com"),
            )
        )
        assertTrue(
            ThirdPartyWebViewAccessPolicy.isTrustedUrl(
                "https://api.example.com/plugin.html",
                install,
                listOf("https://api.example.com"),
            )
        )
        assertTrue(
            ThirdPartyWebViewAccessPolicy.isTrustedUrl(
                "http://47.95.238.140:8080/api/services",
                install,
                listOf("http://47.95.238.140:8080"),
            )
        )
        assertFalse(
            ThirdPartyWebViewAccessPolicy.isTrustedUrl(
                "https://evil.example.com/plugin.html",
                install,
                listOf("https://api.example.com"),
            )
        )
        assertFalse(
            ThirdPartyWebViewAccessPolicy.isTrustedUrl(
                "http://47.95.238.140:8080/api/services",
                install,
                listOf("https://api.example.com"),
            )
        )
    }

    @Test
    fun runtimePolicyTrustsSandboxOriginAndAllowedOriginsOnly() {
        val install = temp.newFolder("runtime")
        val index = File(install, "index.html").apply { writeText("<html></html>") }
        val serviceId = "bjtu.demo"
        val commitSha = "abcdef1234567890"
        val sandboxUrl = "${ThirdPartyServiceSandbox.originFor(serviceId, commitSha)}/index.html"

        assertTrue(
            ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                sandboxUrl,
                serviceId,
                commitSha,
                listOf("https://api.example.com"),
            )
        )
        assertTrue(
            ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                "https://api.example.com/plugin.html",
                serviceId,
                commitSha,
                listOf("https://api.example.com"),
            )
        )
        assertTrue(
            ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                "http://47.95.238.140:8080/api/services",
                serviceId,
                commitSha,
                listOf("http://47.95.238.140:8080"),
            )
        )
        assertFalse(
            ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                index.toURI().toString(),
                serviceId,
                commitSha,
                listOf("https://api.example.com"),
            )
        )
        assertFalse(
            ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                "https://evil.example.com/plugin.html",
                serviceId,
                commitSha,
                listOf("https://api.example.com"),
            )
        )
        assertFalse(
            ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                "http://47.95.238.140:8080/api/services",
                serviceId,
                commitSha,
                listOf("https://api.example.com"),
            )
        )
    }

    @Test
    fun sandboxHostsAreIsolatedAndValidHostLabels() {
        val first = ThirdPartyServiceSandbox.hostFor("com.example.campus-service", "abcdef1234567890")
        val sameServiceNewCommit = ThirdPartyServiceSandbox.hostFor("com.example.campus-service", "1234567890abcdef")
        val second = ThirdPartyServiceSandbox.hostFor("bjtu_other.service", "abcdef1234567890")
        val firstLabel = first.substringBefore('.')

        assertTrue(first.endsWith(".third-party.bjtu-mis.local"))
        assertTrue(firstLabel.length <= 63)
        assertTrue(firstLabel.matches(Regex("^[a-z0-9-]+$")))
        assertNotEquals(first, sameServiceNewCommit)
        assertNotEquals(first, second)
    }

    @Test
    fun sandboxResolvesAbsoluteAssetsAndSpaFallbackWithinInstallDir() {
        val install = temp.newFolder("sandbox")
        val index = File(install, "index.html").apply { writeText("<html></html>") }
        val appJs = File(install, "assets/app.js").apply {
            parentFile?.mkdirs()
            writeText("console.log('ok')")
        }
        val serviceId = "bjtu.demo"
        val commitSha = "abcdef1234567890"
        val origin = ThirdPartyServiceSandbox.originFor(serviceId, commitSha)

        val asset = ThirdPartyServiceSandbox.resolveLocalResource(
            "$origin/assets/app.js",
            serviceId,
            commitSha,
            install,
            "index.html",
        )
        assertTrue(asset is ThirdPartySandboxResourceResolution.Found)
        asset as ThirdPartySandboxResourceResolution.Found
        assertEquals(appJs.canonicalFile, asset.resource.file)
        assertFalse(asset.resource.fallbackToEntrypoint)

        val root = ThirdPartyServiceSandbox.resolveLocalResource(
            "$origin/",
            serviceId,
            commitSha,
            install,
            "index.html",
        )
        assertTrue(root is ThirdPartySandboxResourceResolution.Found)
        root as ThirdPartySandboxResourceResolution.Found
        assertEquals(index.canonicalFile, root.resource.file)
        assertFalse(root.resource.fallbackToEntrypoint)

        val spaRoute = ThirdPartyServiceSandbox.resolveLocalResource(
            "$origin/orders/123",
            serviceId,
            commitSha,
            install,
            "index.html",
        )
        assertTrue(spaRoute is ThirdPartySandboxResourceResolution.Found)
        spaRoute as ThirdPartySandboxResourceResolution.Found
        assertEquals(index.canonicalFile, spaRoute.resource.file)
        assertTrue(spaRoute.resource.fallbackToEntrypoint)

        assertEquals(
            ThirdPartySandboxResourceResolution.NotFound,
            ThirdPartyServiceSandbox.resolveLocalResource(
                "$origin/assets/missing.js",
                serviceId,
                commitSha,
                install,
                "index.html",
            ),
        )
        assertEquals(
            ThirdPartySandboxResourceResolution.Blocked,
            ThirdPartyServiceSandbox.resolveLocalResource(
                "$origin/%2e%2e/secret.html",
                serviceId,
                commitSha,
                install,
                "index.html",
            ),
        )
        assertEquals(
            ThirdPartySandboxResourceResolution.NotSandboxUrl,
            ThirdPartyServiceSandbox.resolveLocalResource(
                "${ThirdPartyServiceSandbox.originFor("bjtu.other", commitSha)}/assets/app.js",
                serviceId,
                commitSha,
                install,
                "index.html",
            ),
        )
    }
}
