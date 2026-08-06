package cn.edu.bjtu.mis.data.thirdparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginWebViewRuntimeEnvironmentTest {
    @Test
    fun modernProviderOffersBothTransportsAndPrefersArrayBuffer() {
        val environment = environment(arrayBuffer = true)

        assertTrue(environment.secureRuntimeAvailable)
        assertEquals(
            listOf(
                PluginBinaryTransport.ArrayBuffer,
                PluginBinaryTransport.Base64UrlChunksV1,
            ),
            environment.binaryTransports,
        )
        assertEquals(PluginBinaryTransport.ArrayBuffer, environment.preferredBinaryTransport)
        assertEquals("com.android.webview 130.0.0.0", environment.providerDisplay)
        assertEquals("ArrayBuffer", environment.binaryTransportDisplay)
    }

    @Test
    fun compatibilityProviderOffersOnlyBase64UrlChunks() {
        val environment = environment(arrayBuffer = false)

        assertTrue(environment.secureRuntimeAvailable)
        assertEquals(
            listOf(PluginBinaryTransport.Base64UrlChunksV1),
            environment.binaryTransports,
        )
        assertEquals(
            PluginBinaryTransport.Base64UrlChunksV1,
            environment.preferredBinaryTransport,
        )
        assertEquals("Base64URL compatibility mode", environment.binaryTransportDisplay)
    }

    @Test
    fun missingEitherCoreFeatureFailsClosedWithoutATransportOffer() {
        for (environment in listOf(
            environment(arrayBuffer = true, documentStart = false),
            environment(arrayBuffer = true, listener = false),
        )) {
            assertFalse(environment.secureRuntimeAvailable)
            assertTrue(environment.binaryTransports.isEmpty())
            assertEquals(null, environment.preferredBinaryTransport)
            assertEquals("Unavailable", environment.binaryTransportDisplay)
        }
    }

    private fun environment(
        arrayBuffer: Boolean,
        documentStart: Boolean = true,
        listener: Boolean = true,
    ) = PluginWebViewRuntimeEnvironment(
        providerPackageName = "com.android.webview",
        providerVersionName = "130.0.0.0",
        documentStartScriptSupported = documentStart,
        webMessageListenerSupported = listener,
        webMessageArrayBufferSupported = arrayBuffer,
    )
}
