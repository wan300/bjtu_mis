package cn.edu.bjtu.mis.ui.screens

import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyWebViewSecurityHeadersTest {
    @Test
    fun cspBindsConnectMediaAndFrameOriginsSeparately() {
        val headers = thirdPartySecurityHeaders(
            ThirdPartyServiceManifest(
                connectOrigins = listOf("https://api.example.com"),
                mediaOrigins = listOf("https://media.example.com"),
                frameOrigins = listOf("https://frame.example.com"),
                navigationOrigins = listOf("https://navigate.example.com"),
            ),
        )
        val csp = headers.getValue("Content-Security-Policy")

        assertTrue(csp.contains("connect-src 'self' https://api.example.com"))
        assertTrue(csp.contains("media-src 'self' https://media.example.com"))
        assertTrue(csp.contains("frame-src 'self' https://frame.example.com"))
        assertFalse(csp.contains("https://navigate.example.com"))
        assertEquals("nosniff", headers["X-Content-Type-Options"])
        assertEquals("no-referrer", headers["Referrer-Policy"])
        assertTrue(headers.getValue("Permissions-Policy").contains("camera=()"))
    }
}
