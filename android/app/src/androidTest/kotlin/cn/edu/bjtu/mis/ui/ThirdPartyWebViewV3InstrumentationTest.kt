package cn.edu.bjtu.mis.ui

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cn.edu.bjtu.mis.data.thirdparty.FileThirdPartyKvStore
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyKvCipher
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyKvNamespace
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyMarketplaceMetadata
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceManifest
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyWebViewAccessPolicy
import cn.edu.bjtu.mis.ui.screens.configureThirdPartyPluginWebView
import cn.edu.bjtu.mis.ui.screens.dispatchRuntimeEvent
import cn.edu.bjtu.mis.ui.screens.supportsThirdPartyV3Runtime
import cn.edu.bjtu.mis.ui.screens.thirdPartySecurityHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ThirdPartyWebViewV3InstrumentationTest {
    @Test
    fun runtimeAppliesStrictWebViewSettingsAndFeatureGate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val webView = WebView(instrumentation.targetContext)
            try {
                configureThirdPartyPluginWebView(webView)

                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, webView.settings.mixedContentMode)
                assertFalse(webView.settings.allowFileAccess)
                assertFalse(webView.settings.allowContentAccess)
                assertFalse(webView.settings.javaScriptCanOpenWindowsAutomatically)
                assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(webView))
                assertEquals(
                    WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
                    supportsThirdPartyV3Runtime(),
                )
            } finally {
                webView.destroy()
            }
        }
    }

    @Test
    fun documentStartRunsBeforePageCodeAndRemoteFramesReceiveNoBridge() {
        assumeTrue(supportsThirdPartyV3Runtime())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val origin = "https://v3-runtime.test"
        val remoteOrigin = "https://remote-frame.test"
        val received = CopyOnWriteArrayList<BridgeObservation>()
        val messages = CountDownLatch(3)
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            configureThirdPartyPluginWebView(webView)
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                "window.__bjtuDocumentStartSeen = true;",
                setOf(origin),
            )
            WebViewCompat.addWebMessageListener(
                webView,
                "testBridge",
                setOf(origin),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) {
                        received += BridgeObservation(
                            data = message.data.orEmpty(),
                            mainFrame = isMainFrame,
                            trusted = ThirdPartyWebViewAccessPolicy.isTrustedBridgeMessage(
                                isMainFrame,
                                sourceOrigin.toString(),
                                origin,
                            ),
                        )
                        messages.countDown()
                    }
                },
            )
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (request.url.host !in setOf("v3-runtime.test", "remote-frame.test")) return null
                    val html = if (request.url.host == "remote-frame.test") {
                        """
                            <script>
                              parent.postMessage(
                                'remote-bridge:' + String(typeof testBridge !== 'undefined'),
                                '*'
                              );
                            </script>
                        """.trimIndent()
                    } else if (request.url.path == "/frame.html") {
                        "<script>testBridge.postMessage('frame:' + String(window.__bjtuDocumentStartSeen === true));</script>"
                    } else {
                        """
                            <!doctype html>
                            <html>
                              <body>
                                <script>
                                  window.addEventListener('message', function (event) {
                                    testBridge.postMessage(event.data);
                                  });
                                  testBridge.postMessage('main:' + String(window.__bjtuDocumentStartSeen === true));
                                </script>
                                <iframe src="$origin/frame.html"></iframe>
                                <iframe src="$remoteOrigin/frame.html"></iframe>
                              </body>
                            </html>
                        """.trimIndent()
                    }
                    return WebResourceResponse(
                        "text/html",
                        "UTF-8",
                        ByteArrayInputStream(html.toByteArray()),
                    )
                }
            }
            webView.loadUrl("$origin/index.html")
        }

        try {
            assertTrue("Timed out waiting for main-frame and iframe messages", messages.await(15, TimeUnit.SECONDS))
            val main = received.single { it.data.startsWith("main:") }
            val frame = received.single { it.data.startsWith("frame:") }
            val remote = received.single { it.data.startsWith("remote-bridge:") }
            assertEquals("main:true", main.data)
            assertTrue(main.mainFrame)
            assertTrue(main.trusted)
            assertFalse(frame.mainFrame)
            assertFalse(frame.trusted)
            assertEquals("remote-bridge:false", remote.data)
            assertTrue(remote.mainFrame)
            assertTrue(remote.trusted)
        } finally {
            instrumentation.runOnMainSync {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    @Test
    fun lifecycleEventsReachBothPlainAndNamespacedContracts() {
        assumeTrue(supportsThirdPartyV3Runtime())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val origin = "https://v3-lifecycle.test"
        val received = CopyOnWriteArrayList<String>()
        val messages = CountDownLatch(2)
        val dispatched = AtomicBoolean(false)
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            configureThirdPartyPluginWebView(webView)
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                """
                    window.addEventListener('theme', function () {
                      lifecycleBridge.postMessage('theme');
                    });
                    window.addEventListener('bjtu:theme', function () {
                      lifecycleBridge.postMessage('bjtu:theme');
                    });
                """.trimIndent(),
                setOf(origin),
            )
            WebViewCompat.addWebMessageListener(
                webView,
                "lifecycleBridge",
                setOf(origin),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) {
                        if (
                            ThirdPartyWebViewAccessPolicy.isTrustedBridgeMessage(
                                isMainFrame,
                                sourceOrigin.toString(),
                                origin,
                            )
                        ) {
                            received += message.data.orEmpty()
                            messages.countDown()
                        }
                    }
                },
            )
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? =
                    if (request.url.host == "v3-lifecycle.test") {
                        WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            ByteArrayInputStream("<html><body>ready</body></html>".toByteArray()),
                        )
                    } else {
                        null
                    }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (dispatched.compareAndSet(false, true)) {
                        dispatchRuntimeEvent(view, "theme", buildJsonObject {
                            put("color_scheme", "dark")
                        })
                    }
                }
            }
            webView.loadUrl("$origin/index.html")
        }

        try {
            assertTrue("Timed out waiting for lifecycle events", messages.await(15, TimeUnit.SECONDS))
            assertTrue("theme" in received)
            assertTrue("bjtu:theme" in received)
        } finally {
            instrumentation.runOnMainSync {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    @Test
    fun cspBindsConnectMediaAndFrameOriginsSeparately() {
        val headers = thirdPartySecurityHeaders(
            ThirdPartyServiceManifest(
                schemaVersion = 3,
                id = "bjtu.security",
                name = "Security",
                description = "Security headers",
                version = "1.0.0",
                runtimeVersion = 1,
                minRuntimeVersion = 1,
                requiredCapabilities = listOf("runtime.lifecycle.v1", "remote.frame.v1"),
                dataSchemaVersion = 1,
                entrypoint = "index.html",
                icon = "icon.svg",
                author = "BJTU",
                connectOrigins = listOf("https://api.example.com"),
                mediaOrigins = listOf("https://media.example.com"),
                frameOrigins = listOf("https://frame.example.com"),
                navigationOrigins = listOf("https://navigation.example.com"),
                bridgeOrigins = listOf("self"),
                marketplace = ThirdPartyMarketplaceMetadata(category = "other"),
            )
        )
        val csp = headers.getValue("Content-Security-Policy")

        assertTrue(csp.contains("connect-src 'self' https://api.example.com"))
        assertTrue(csp.contains("media-src 'self' https://media.example.com"))
        assertTrue(csp.contains("frame-src 'self' https://frame.example.com"))
        assertFalse(csp.contains("navigation.example.com"))
        assertEquals("nosniff", headers["X-Content-Type-Options"])
        assertEquals("no-referrer", headers["Referrer-Policy"])
    }

    @Test
    fun storageNamespaceSurvivesPluginCommitChangesAndStaysPublisherIsolated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "plugin-kv-${UUID.randomUUID()}")
        val cipher = object : ThirdPartyKvCipher {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = plaintext
            override fun decrypt(payload: ByteArray, associatedData: ByteArray): ByteArray = payload
        }
        try {
            val namespace = ThirdPartyKvNamespace("github-owner:12345", "bjtu.storage")
            FileThirdPartyKvStore(root, cipher).set(namespace, "commit-independent", JsonPrimitive("kept"))

            val afterUpdate = FileThirdPartyKvStore(root, cipher)
            assertEquals(
                "kept",
                (afterUpdate.get(namespace, "commit-independent") as JsonPrimitive).content,
            )
            assertNull(
                afterUpdate.get(
                    ThirdPartyKvNamespace("github-owner:67890", "bjtu.storage"),
                    "commit-independent",
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private data class BridgeObservation(
        val data: String,
        val mainFrame: Boolean,
        val trusted: Boolean,
    )
}
