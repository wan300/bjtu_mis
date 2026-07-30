package cn.edu.bjtu.mis.ui.screens

import cn.edu.bjtu.mis.data.thirdparty.BridgeTransport
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceManifest
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyOriginDeclaration
import cn.edu.bjtu.mis.data.thirdparty.PluginWebViewPolicy
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyCapabilityDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyWebViewSecurityHeadersTest {
    @Test
    fun managedStorageGuardCoversPersistenceAndGatesBridgeInjection() {
        val guard = PluginWebViewPolicy.managedStorageGuardScript()

        listOf(
            "localStorage",
            "sessionStorage",
            "indexedDB",
            "caches",
            "cookie",
            "serviceWorker",
            "storage",
        ).forEach { api ->
            assertTrue("Missing managed-storage guard for $api", guard.contains("'$api'"))
        }
        assertTrue(guard.contains("__BJTU_MANAGED_STORAGE_ONLY__"))
        assertTrue(
            BridgeTransport.documentStartScript(binarySupported = true)
                .contains("window.__BJTU_MANAGED_STORAGE_ONLY__ !== true"),
        )
    }

    @Test
    fun cspBindsConnectMediaAndFrameOriginsSeparately() {
        val manifest = ThirdPartyServiceManifest(
            capabilities = ThirdPartyCapabilityDeclaration(
                required = listOf("runtime.lifecycle@1", "remote.frame@1"),
            ),
            origins = ThirdPartyOriginDeclaration(
                connect = listOf("https://api.example.com"),
                media = listOf("https://media.example.com"),
                frame = listOf("https://frame.example.com"),
                navigation = listOf("https://navigate.example.com"),
            ),
        )
        val headers = PluginWebViewPolicy.securityHeaders(
            manifest,
            setOf("runtime.lifecycle@1", "remote.frame@1"),
        )
        val withoutOptionalFrameGrant = PluginWebViewPolicy.securityHeaders(
            manifest.copy(
                capabilities = ThirdPartyCapabilityDeclaration(
                    required = listOf("runtime.lifecycle@1"),
                    optional = listOf("remote.frame@1"),
                ),
            ),
            setOf("runtime.lifecycle@1"),
        )
        val csp = headers.getValue("Content-Security-Policy")

        assertTrue(csp.contains("connect-src 'self' https://api.example.com"))
        assertTrue(csp.contains("img-src 'self' data: https://media.example.com"))
        assertTrue(csp.contains("media-src 'self' https://media.example.com"))
        assertTrue(csp.contains("frame-src 'self' https://frame.example.com"))
        assertTrue(csp.contains("worker-src 'none'"))
        assertFalse(
            withoutOptionalFrameGrant.getValue("Content-Security-Policy")
                .contains("https://frame.example.com"),
        )
        assertFalse(csp.contains("https://navigate.example.com"))
        assertEquals("nosniff", headers["X-Content-Type-Options"])
        assertEquals("no-referrer", headers["Referrer-Policy"])
        assertTrue(headers.getValue("Permissions-Policy").contains("camera=()"))
    }
}
