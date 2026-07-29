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
    fun runtimePolicyTrustsOnlyStableLocalSandboxOrigin() {
        val install = temp.newFolder("runtime")
        val index = File(install, "index.html").apply { writeText("<html></html>") }
        val serviceId = "bjtu.demo"
        val publisherSubjectId = "github-owner:12345"
        val sandboxUrl = "${ThirdPartyServiceSandbox.originFor(serviceId, publisherSubjectId)}/index.html"

        assertTrue(
            ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                sandboxUrl,
                serviceId,
                publisherSubjectId,
            )
        )
        assertFalse(
            ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                "https://api.example.com/plugin.html",
                serviceId,
                publisherSubjectId,
            )
        )
        assertFalse(
            ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                "http://47.95.238.140:8080/api/services",
                serviceId,
                publisherSubjectId,
            )
        )
        assertFalse(
            ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                index.toURI().toString(),
                serviceId,
                publisherSubjectId,
            )
        )
        assertFalse(
            ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                "https://evil.example.com/plugin.html",
                serviceId,
                publisherSubjectId,
            )
        )
    }

    @Test
    fun bridgeRequiresMainFrameAndExactStableOrigin() {
        val origin = ThirdPartyServiceSandbox.originFor("bjtu.demo", "github-owner:12345")

        assertTrue(
            ThirdPartyWebViewAccessPolicy.isTrustedBridgeMessage(
                isMainFrame = true,
                sourceOrigin = origin,
                expectedOrigin = origin,
            ),
        )
        assertFalse(
            ThirdPartyWebViewAccessPolicy.isTrustedBridgeMessage(
                isMainFrame = false,
                sourceOrigin = origin,
                expectedOrigin = origin,
            ),
        )
        assertFalse(
            ThirdPartyWebViewAccessPolicy.isTrustedBridgeMessage(
                isMainFrame = true,
                sourceOrigin = "https://example.com",
                expectedOrigin = origin,
            ),
        )
    }

    @Test
    fun sandboxHostsAreIsolatedAndValidHostLabels() {
        val first = ThirdPartyServiceSandbox.hostFor("com.example.campus-service", "github-owner:123")
        val sameServiceNewCommit = ThirdPartyServiceSandbox.hostFor("com.example.campus-service", "github-owner:123")
        val changedPublisher = ThirdPartyServiceSandbox.hostFor("com.example.campus-service", "github-owner:456")
        val second = ThirdPartyServiceSandbox.hostFor("bjtu_other.service", "github-owner:123")
        val firstLabel = first.substringBefore('.')

        assertTrue(first.endsWith(".third-party.bjtu-mis.local"))
        assertTrue(firstLabel.length <= 63)
        assertTrue(firstLabel.matches(Regex("^[a-z0-9-]+$")))
        assertEquals(first, sameServiceNewCommit)
        assertNotEquals(first, changedPublisher)
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
        val publisherSubjectId = "github-owner:123"
        val origin = ThirdPartyServiceSandbox.originFor(serviceId, publisherSubjectId)

        val asset = ThirdPartyServiceSandbox.resolveLocalResource(
            "$origin/assets/app.js",
            serviceId,
            publisherSubjectId,
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
            publisherSubjectId,
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
            publisherSubjectId,
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
                publisherSubjectId,
                install,
                "index.html",
            ),
        )
        assertEquals(
            ThirdPartySandboxResourceResolution.Blocked,
            ThirdPartyServiceSandbox.resolveLocalResource(
                "$origin/%2e%2e/secret.html",
                serviceId,
                publisherSubjectId,
                install,
                "index.html",
            ),
        )
        assertEquals(
            ThirdPartySandboxResourceResolution.NotSandboxUrl,
            ThirdPartyServiceSandbox.resolveLocalResource(
                "${ThirdPartyServiceSandbox.originFor("bjtu.other", publisherSubjectId)}/assets/app.js",
                serviceId,
                publisherSubjectId,
                install,
                "index.html",
            ),
        )
    }
}
