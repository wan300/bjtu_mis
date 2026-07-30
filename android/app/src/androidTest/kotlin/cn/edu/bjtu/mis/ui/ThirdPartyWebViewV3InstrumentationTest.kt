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
import cn.edu.bjtu.mis.data.thirdparty.BridgeTransport
import cn.edu.bjtu.mis.data.thirdparty.FileThirdPartyKvStore
import cn.edu.bjtu.mis.data.thirdparty.PluginDiagnostics
import cn.edu.bjtu.mis.data.thirdparty.PluginNavigationController
import cn.edu.bjtu.mis.data.thirdparty.PluginRuntimeEvent
import cn.edu.bjtu.mis.data.thirdparty.PluginWebViewPolicy
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyCapabilityDeclaration
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyKvCipher
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyKvNamespace
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyMarketplaceMetadata
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyOriginDeclaration
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyRuntimeProfile
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyService
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceApiRegistry
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceManifest
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceSandbox
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartySensitiveActionConfirmer
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyWebViewAccessPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
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
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ThirdPartyWebViewV3InstrumentationTest {
    @Test
    fun runtimeAppliesStrictWebViewSettingsAndFeatureGate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val webView = WebView(instrumentation.targetContext)
            try {
                PluginWebViewPolicy.configure(webView)

                assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, webView.settings.mixedContentMode)
                assertFalse(webView.settings.allowFileAccess)
                assertFalse(webView.settings.allowContentAccess)
                assertFalse(webView.settings.domStorageEnabled)
                assertFalse(webView.settings.javaScriptCanOpenWindowsAutomatically)
                assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(webView))
                assertEquals(
                    WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER),
                    PluginWebViewPolicy.supportsSecureTransport(),
                )
            } finally {
                webView.destroy()
            }
        }
    }

    @Test
    fun documentStartRunsBeforePageCodeAndRemoteFramesReceiveNoBridge() {
        assumeTrue(PluginWebViewPolicy.supportsSecureTransport())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val origin = "https://v3-runtime.test"
        val remoteOrigin = "https://remote-frame.test"
        val received = CopyOnWriteArrayList<BridgeObservation>()
        val messages = CountDownLatch(3)
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            PluginWebViewPolicy.configure(webView)
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
    fun contractRuntimeBlocksBrowserManagedPersistenceBeforePluginCode() {
        assumeTrue(PluginWebViewPolicy.supportsSecureTransport())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val origin = "https://managed-storage.test"
        val result = AtomicReference<String>()
        val message = CountDownLatch(1)
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            PluginWebViewPolicy.configure(webView)
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                PluginWebViewPolicy.managedStorageGuardScript(),
                setOf(origin),
            )
            WebViewCompat.addWebMessageListener(
                webView,
                "storageProbe",
                setOf(origin),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        payload: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) {
                        result.set(payload.data.orEmpty())
                        message.countDown()
                    }
                },
            )
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (request.url.host != "managed-storage.test") return null
                    val html = """
                        <!doctype html>
                        <script>
                          var probe = function (name, read) {
                            try {
                              read();
                              return name + ':allowed';
                            } catch (error) {
                              return name + ':' + String(error && error.name);
                            }
                          };
                          storageProbe.postMessage([
                            'guard:' + String(window.__BJTU_MANAGED_STORAGE_ONLY__ === true),
                            probe('localStorage', function () { return window.localStorage; }),
                            probe('sessionStorage', function () { return window.sessionStorage; }),
                            probe('indexedDB', function () { return window.indexedDB; }),
                            probe('caches', function () { return window.caches; }),
                            probe('cookie', function () { return document.cookie; }),
                            probe('worker', function () { return window.Worker; }),
                            probe('serviceWorker', function () { return navigator.serviceWorker; }),
                            probe('storage', function () { return navigator.storage; })
                          ].join('|'));
                        </script>
                    """.trimIndent()
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
            assertTrue(
                "Timed out waiting for storage policy probe",
                message.await(15, TimeUnit.SECONDS),
            )
            assertEquals(
                "guard:true|" +
                    "localStorage:SecurityError|" +
                    "sessionStorage:SecurityError|" +
                    "indexedDB:SecurityError|" +
                    "caches:SecurityError|" +
                    "cookie:SecurityError|" +
                    "worker:SecurityError|" +
                    "serviceWorker:SecurityError|" +
                    "storage:SecurityError",
                result.get(),
            )
        } finally {
            instrumentation.runOnMainSync {
                runCatching {
                    WebViewCompat.removeWebMessageListener(webView, "storageProbe")
                }
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    @Test
    fun backAcknowledgementHandledTrueCompletesImmediately() {
        assumeTrue(PluginWebViewPolicy.supportsSecureTransport())

        val observation = runBackAcknowledgementProbe(
            handled = true,
            acknowledgementTimeoutMs = 5_000L,
        )

        assertTrue(observation.handled)
        assertTrue(
            "Handled acknowledgement waited ${observation.elapsedMs}ms",
            observation.elapsedMs < ACK_COMPLETION_DEADLINE_MS,
        )
    }

    @Test
    fun backAcknowledgementHandledFalseCompletesImmediately() {
        assumeTrue(PluginWebViewPolicy.supportsSecureTransport())

        val observation = runBackAcknowledgementProbe(
            handled = false,
            acknowledgementTimeoutMs = 5_000L,
        )

        assertFalse(observation.handled)
        assertTrue(
            "Unhandled acknowledgement waited ${observation.elapsedMs}ms",
            observation.elapsedMs < ACK_COMPLETION_DEADLINE_MS,
        )
    }

    @Test
    fun backAcknowledgementTimesOutWhenSdkDoesNotReply() {
        assumeTrue(PluginWebViewPolicy.supportsSecureTransport())

        val observation = runBackAcknowledgementProbe(
            handled = null,
            acknowledgementTimeoutMs = ACK_TIMEOUT_PROBE_MS,
        )

        assertFalse(observation.handled)
        assertTrue(
            "Missing acknowledgement returned before its timeout: ${observation.elapsedMs}ms",
            observation.elapsedMs >= ACK_TIMEOUT_MINIMUM_MS,
        )
    }

    @Test
    fun cspBindsConnectMediaAndFrameOriginsSeparately() {
        val headers = PluginWebViewPolicy.securityHeaders(
            ThirdPartyServiceManifest(
                schemaVersion = 3,
                id = "bjtu.security",
                name = "Security",
                version = "1.0.0",
                entrypoint = "index.html",
                icon = "icon.svg",
                capabilities = ThirdPartyCapabilityDeclaration(
                    required = listOf("runtime.lifecycle@1", "remote.frame@1"),
                ),
                origins = ThirdPartyOriginDeclaration(
                    connect = listOf("https://api.example.com"),
                    media = listOf("https://media.example.com"),
                    frame = listOf("https://frame.example.com"),
                    navigation = listOf("https://navigation.example.com"),
                ),
                description = "Security headers",
                author = "BJTU",
                marketplace = ThirdPartyMarketplaceMetadata(category = "other"),
            ),
            setOf("runtime.lifecycle@1", "remote.frame@1"),
        )
        val csp = headers.getValue("Content-Security-Policy")

        assertTrue(csp.contains("connect-src 'self' https://api.example.com"))
        assertTrue(csp.contains("media-src 'self' https://media.example.com"))
        assertTrue(csp.contains("frame-src 'self' https://frame.example.com"))
        assertTrue(csp.contains("worker-src 'none'"))
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

    private fun runBackAcknowledgementProbe(
        handled: Boolean?,
        acknowledgementTimeoutMs: Long,
    ): BackAcknowledgementObservation {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val service = backAcknowledgementService(context.cacheDir)
        val origin = ThirdPartyServiceSandbox.originFor(
            service.serviceId,
            service.publisherSubjectId,
        )
        val host = Uri.parse(origin).host
        val bridgeReady = CountDownLatch(1)
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val binaryDirectory = File(context.cacheDir, "plugin-bridge-${UUID.randomUUID()}")
        val transport = BridgeTransport(
            service = service,
            apiRegistry = ThirdPartyServiceApiRegistry(
                moduleRepository = null,
                mailRepository = null,
            ),
            confirmer = ThirdPartySensitiveActionConfirmer { _, _ -> true },
            scope = bridgeScope,
            navigationController = PluginNavigationController(service) {},
            closePlugin = {},
            diagnostics = PluginDiagnostics(service.serviceId),
            binaryDirectory = binaryDirectory,
        )
        val acknowledgementStatement = handled?.let { value ->
            """
                bridge.postMessage({
                  protocolVersion: 2,
                  kind: 'eventAck',
                  requestId: message.requestId,
                  handled: $value
                });
            """.trimIndent()
        }.orEmpty()
        val html = """
            <!doctype html>
            <script>
              var bridge = window.__BJTU_PLUGIN_BRIDGE_V2__;
              bridge.addEventListener(function (message) {
                if (
                  message.capability === 'runtime.lifecycle@1' &&
                  message.event === 'back' &&
                  message.requiresAcknowledgement === true
                ) {
                  $acknowledgementStatement
                }
              });
              bridge.postMessage({
                protocolVersion: 2,
                kind: 'cancel',
                requestId: 'bootstrap'
              });
            </script>
        """.trimIndent()
        lateinit var webView: WebView

        instrumentation.runOnMainSync {
            webView = WebView(context)
            PluginWebViewPolicy.configure(webView)
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                PluginWebViewPolicy.managedStorageGuardScript() +
                    "\n" +
                    BridgeTransport.documentStartScript(PluginWebViewPolicy.supportsArrayBuffer()),
                setOf(origin),
            )
            WebViewCompat.addWebMessageListener(
                webView,
                BridgeTransport.OBJECT_NAME,
                setOf(origin),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) {
                        transport.onMessage(
                            view,
                            message,
                            sourceOrigin,
                            isMainFrame,
                            replyProxy,
                        )
                        if (message.data.orEmpty().contains("\"requestId\":\"bootstrap\"")) {
                            bridgeReady.countDown()
                        }
                    }
                },
            )
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (request.url.host != host) return null
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
            assertTrue(
                "Timed out waiting for the bridge bootstrap message",
                bridgeReady.await(15, TimeUnit.SECONDS),
            )
            val startedAt = System.nanoTime()
            val acknowledged = runBlocking {
                withTimeout(ACK_COMPLETION_DEADLINE_MS) {
                    transport.sendEventAwaitingAcknowledgement(
                        PluginRuntimeEvent(
                            capability = "runtime.lifecycle@1",
                            event = "back",
                        ),
                        timeoutMs = acknowledgementTimeoutMs,
                    )
                }
            }
            return BackAcknowledgementObservation(
                handled = acknowledged,
                elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
            )
        } finally {
            transport.close()
            instrumentation.runOnMainSync {
                runCatching {
                    WebViewCompat.removeWebMessageListener(webView, BridgeTransport.OBJECT_NAME)
                }
                webView.stopLoading()
                webView.destroy()
            }
            binaryDirectory.deleteRecursively()
        }
    }

    private fun backAcknowledgementService(cacheDir: File): ThirdPartyService {
        val manifest = ThirdPartyServiceManifest(
            schemaVersion = 3,
            id = "io.example.back-ack",
            name = "Back acknowledgement test",
            version = "1.0.0",
            entrypoint = "index.html",
            icon = "icon.svg",
            capabilities = ThirdPartyCapabilityDeclaration(
                required = listOf("runtime.lifecycle@1"),
            ),
        )
        return ThirdPartyService(
            serviceId = manifest.id,
            manifest = manifest,
            sourceUrl = "https://example.invalid/plugin.zip",
            githubOwner = "example",
            githubRepo = "back-ack",
            defaultBranch = "main",
            commitSha = "a".repeat(40),
            packageDigestSha256 = "b".repeat(64),
            installDir = File(cacheDir, "plugin-back-ack").absolutePath,
            grantedCapabilities = setOf("runtime.lifecycle@1"),
            allowedOrigins = emptyList(),
            enabled = true,
            needsReview = false,
            installedAt = "2026-07-30T00:00:00Z",
            updatedAt = "2026-07-30T00:00:00Z",
            publisherSubjectId = "test-publisher",
            runtimeProfile = ThirdPartyRuntimeProfile.ContractV1.value,
            runtimeFloor = 2,
            compatibilityState = ThirdPartyRuntimeProfile.ContractV1.value,
            verificationLevel = "test",
        )
    }

    private data class BridgeObservation(
        val data: String,
        val mainFrame: Boolean,
        val trusted: Boolean,
    )

    private data class BackAcknowledgementObservation(
        val handled: Boolean,
        val elapsedMs: Long,
    )

    private companion object {
        const val ACK_COMPLETION_DEADLINE_MS = 2_000L
        const val ACK_TIMEOUT_PROBE_MS = 200L
        const val ACK_TIMEOUT_MINIMUM_MS = 100L
    }
}
