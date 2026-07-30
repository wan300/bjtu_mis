package cn.edu.bjtu.mis.data.thirdparty

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewFeature

object PluginWebViewPolicy {
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView) {
        with(webView.settings) {
            javaScriptEnabled = true
            // contract_v1 persistence is host-managed (KV/Blob/Cache).
            domStorageEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            useWideViewPort = true
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            run {
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            }
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.setDownloadListener { _, _, _, _, _ ->
            Log.w("BjtuPluginRuntime", "Blocked plugin download request")
        }
    }

    fun supportsSecureTransport(): Boolean =
        runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        }.getOrDefault(false)

    fun supportsArrayBuffer(): Boolean =
        runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        }.getOrDefault(false)

    fun unavailableRequiredCapabilities(service: ThirdPartyService): Set<String> =
        service.manifest.requiredCapabilities.filterTo(linkedSetOf()) { capability ->
            ThirdPartyCapabilityRegistry.requiredWebViewFeatures(capability).any { feature ->
                !supportsFeature(feature)
            }
        }

    fun isCapabilityAvailable(capability: String): Boolean =
        ThirdPartyCapabilityRegistry.requiredWebViewFeatures(capability).all(::supportsFeature)

    fun securityHeaders(
        manifest: ThirdPartyServiceManifest,
        grantedCapabilities: Set<String>,
    ): Map<String, String> {
        val frameOrigins = manifest.frameOrigins.takeIf {
            "remote.frame@1" in grantedCapabilities
        }.orEmpty()
        return mapOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to buildString {
                append("default-src 'self'; ")
                append("script-src 'self'; style-src 'self' 'unsafe-inline'; ")
                append("img-src 'self' data:")
                manifest.mediaOrigins.forEach { append(" ").append(it) }
                append("; font-src 'self' data:; ")
                append("connect-src 'self'")
                manifest.connectOrigins.forEach { append(" ").append(it) }
                append("; media-src 'self'")
                manifest.mediaOrigins.forEach { append(" ").append(it) }
                append("; frame-src 'self'")
                frameOrigins.forEach { append(" ").append(it) }
                append("; object-src 'none'; base-uri 'self'; form-action 'self'")
                append("; worker-src 'none'; manifest-src 'none'")
            },
            "Permissions-Policy" to
                "camera=(), microphone=(), geolocation=(), payment=(), usb=(), bluetooth=(), serial=()",
            "Referrer-Policy" to "no-referrer",
            "X-Content-Type-Options" to "nosniff",
        )
    }

    /**
     * WebSettings only exposes a DOM-storage switch; contract_v1 additionally
     * blocks browser-managed databases, caches, cookies, workers and file
     * systems before any plugin script runs. The marker keeps bridge injection
     * fail-closed if a future WebView makes one of the required descriptors
     * impossible to replace.
     */
    fun managedStorageGuardScript(): String = """
        (function () {
          'use strict';
          var deny = function (name) {
            throw new DOMException(
              name + ' is disabled; use BJTU plugin SDK managed storage.',
              'SecurityError'
            );
          };
          var block = function (target, name) {
            if (!target) return false;
            try {
              Object.defineProperty(target, name, {
                configurable: false,
                enumerable: false,
                get: function () { return deny(name); },
                set: function () { return deny(name); }
              });
              return true;
            } catch (_) {
              return false;
            }
          };
          var ok = true;
          [
            [window, 'localStorage'],
            [window, 'sessionStorage'],
            [window, 'indexedDB'],
            [window, 'caches'],
            [window, 'openDatabase'],
            [window, 'Worker'],
            [window, 'SharedWorker'],
            [window, 'requestFileSystem'],
            [window, 'webkitRequestFileSystem'],
            [window, 'showOpenFilePicker'],
            [window, 'showSaveFilePicker'],
            [window, 'showDirectoryPicker'],
            [Window.prototype, 'localStorage'],
            [Window.prototype, 'sessionStorage'],
            [Window.prototype, 'indexedDB'],
            [Window.prototype, 'caches'],
            [Document.prototype, 'cookie'],
            [Navigator.prototype, 'serviceWorker'],
            [Navigator.prototype, 'storage'],
            [Navigator.prototype, 'storageBuckets'],
            [Navigator.prototype, 'webkitPersistentStorage'],
            [Navigator.prototype, 'webkitTemporaryStorage']
          ].forEach(function (entry) {
            ok = block(entry[0], entry[1]) && ok;
          });
          Object.defineProperty(window, '__BJTU_MANAGED_STORAGE_ONLY__', {
            value: ok,
            configurable: false,
            enumerable: false,
            writable: false
          });
        })();
    """.trimIndent()

    private fun supportsFeature(feature: String): Boolean = when (feature) {
        "DOCUMENT_START_SCRIPT" ->
            runCatching {
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            }.getOrDefault(false)
        "WEB_MESSAGE_LISTENER" ->
            runCatching {
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            }.getOrDefault(false)
        "WEB_MESSAGE_ARRAY_BUFFER" -> supportsArrayBuffer()
        else -> false
    }
}
