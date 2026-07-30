package cn.edu.bjtu.mis.data.thirdparty

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cn.edu.bjtu.mis.data.AppJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest

class WebViewThirdPartyDataMigrationRunner(
    context: Context,
) : ThirdPartyDataMigrationRunner {
    private val appContext = context.applicationContext

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun migrate(
        prepared: PreparedThirdPartyServicePackage,
        namespace: ThirdPartyKvNamespace,
        kvStore: ThirdPartyKvStore,
    ): Boolean {
        val entrypoint = prepared.manifest.migrationEntrypoint
            ?: throw ThirdPartyServiceException("缺少 migration_entrypoint")
        if (
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            throw ThirdPartyServiceException("系统 WebView 不支持安全数据迁移所需特性")
        }
        val origin = migrationOrigin(namespace, prepared.commitSha)
        val committed = CompletableDeferred<Boolean>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val requestMutex = Mutex()
        val webView = withContext(Dispatchers.Main.immediate) {
            WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                setDownloadListener { _, _, _, _, _ -> }
                webViewClient = MigrationWebViewClient(origin, prepared.stagingDir)
                WebViewCompat.addDocumentStartJavaScript(this, MigrationBridgeScript, setOf(origin))
                WebViewCompat.addWebMessageListener(
                    this,
                    MigrationBridgeObjectName,
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
                                !ThirdPartyWebViewAccessPolicy.isTrustedBridgeMessage(
                                    isMainFrame,
                                    sourceOrigin.toString(),
                                    origin,
                                ) ||
                                message.type != WebMessageCompat.TYPE_STRING
                            ) {
                                return
                            }
                            scope.launch {
                                val response = requestMutex.withLock {
                                    handleMigrationRequest(
                                        message.data.orEmpty(),
                                        namespace,
                                        kvStore,
                                        committed,
                                    )
                                }
                                replyProxy.postMessage(response)
                            }
                        }
                    },
                )
                loadUrl(origin + encodedPath(entrypoint))
            }
        }
        return try {
            withTimeoutOrNull(MIGRATION_TIMEOUT_MILLIS) { committed.await() } == true
        } finally {
            scope.cancel()
            withContext(Dispatchers.Main.immediate) {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    private suspend fun handleMigrationRequest(
        raw: String,
        namespace: ThirdPartyKvNamespace,
        kvStore: ThirdPartyKvStore,
        committed: CompletableDeferred<Boolean>,
    ): String {
        val parsed = runCatching {
            if (raw.toByteArray(Charsets.UTF_8).size > 512 * 1024) {
                throw IllegalArgumentException("迁移桥接请求超过 512 KiB")
            }
            AppJson.parseToJsonElement(raw).jsonObject
        }.getOrNull()
        val requestId = parsed?.get("requestId")?.jsonPrimitive?.contentOrNull.orEmpty()
        val protocol = parsed?.get("protocolVersion")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val capability = parsed?.get("capability")?.jsonPrimitive?.contentOrNull.orEmpty()
        val method = parsed?.get("method")?.jsonPrimitive?.contentOrNull.orEmpty()
        val params = parsed?.get("params") as? JsonObject
        val unknownFields = parsed?.keys.orEmpty() -
            setOf("protocolVersion", "requestId", "capability", "method", "params")
        val payload = if (
            parsed == null ||
            protocol != THIRD_PARTY_BRIDGE_PROTOCOL_VERSION ||
            requestId.isBlank() ||
            requestId.length > 128 ||
            capability !in setOf("storage.kv@2", "runtime.migration@1") ||
            method.isBlank() ||
            method.length > 128 ||
            params == null ||
            unknownFields.isNotEmpty()
        ) {
            migrationError(requestId, "invalid_request", "迁移桥接请求格式错误")
        } else if (committed.isCompleted) {
            migrationError(requestId, "migration_committed", "迁移已经提交，不能继续修改影子数据")
        } else {
            runCatching {
                when ("$capability#$method") {
                    "storage.kv@2#get" -> migrationSuccess(
                        requestId,
                        kvStore.get(
                            namespace,
                            params.requiredString("key"),
                            ThirdPartyKvSpace.Shadow,
                        ) ?: JsonNull,
                    )
                    "storage.kv@2#set" -> {
                        val value = params["value"] ?: error("缺少 value")
                        val usage = kvStore.set(
                            namespace,
                            params.requiredString("key"),
                            value,
                            ThirdPartyKvSpace.Shadow,
                        )
                        migrationSuccess(requestId, usageJson(usage))
                    }
                    "storage.kv@2#remove" -> migrationSuccess(
                        requestId,
                        JsonPrimitive(
                            kvStore.remove(
                                namespace,
                                params.requiredString("key"),
                                ThirdPartyKvSpace.Shadow,
                            ),
                        ),
                    )
                    "storage.kv@2#keys" -> migrationSuccess(requestId, buildJsonArray {
                        kvStore.keys(namespace, ThirdPartyKvSpace.Shadow)
                            .forEach { add(JsonPrimitive(it)) }
                    })
                    "storage.kv@2#usage" -> migrationSuccess(
                        requestId,
                        usageJson(kvStore.usage(namespace, ThirdPartyKvSpace.Shadow)),
                    )
                    "storage.kv@2#clear" -> {
                        kvStore.clear(namespace, ThirdPartyKvSpace.Shadow)
                        migrationSuccess(requestId, buildJsonObject { put("cleared", true) })
                    }
                    "runtime.migration@1#commit" -> {
                        committed.complete(true)
                        migrationSuccess(requestId, buildJsonObject { put("committed", true) })
                    }
                    else -> migrationError(requestId, "unknown_method", "迁移 runtime 不支持该方法")
                }
            }.getOrElse { migrationError(requestId, "migration_failed", it.message ?: "迁移操作失败") }
        }
        return payload.toString()
    }

    private class MigrationWebViewClient(
        private val origin: String,
        root: File,
    ) : WebViewClient() {
        private val root = root.canonicalFile

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            ThirdPartyWebViewAccessPolicy.origin(request.url.toString()) != origin

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse {
            if (ThirdPartyWebViewAccessPolicy.origin(request.url.toString()) != origin) {
                return blocked()
            }
            val relative = request.url.path.orEmpty().trimStart('/')
            if (relative.isBlank() || relative.split('/').any { it == "." || it == ".." }) return blocked()
            val file = runCatching { File(root, relative).canonicalFile }.getOrNull()
                ?.takeIf { it.path.startsWith(root.path + File.separator) && it.isFile }
                ?: return notFound()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
            return WebResourceResponse(
                mime,
                if (mime.startsWith("text/") || mime.contains("javascript")) "UTF-8" else null,
                200,
                "OK",
                MIGRATION_HEADERS,
                file.inputStream(),
            )
        }

        private fun blocked() = WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            MIGRATION_HEADERS,
            ByteArrayInputStream("Blocked migration request".toByteArray()),
        )

        private fun notFound() = WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            MIGRATION_HEADERS,
            ByteArrayInputStream("Migration resource not found".toByteArray()),
        )
    }

    private companion object {
        const val MIGRATION_TIMEOUT_MILLIS = 30_000L
        const val MigrationBridgeObjectName = "BjtuMigrationNative"
        val MIGRATION_HEADERS = mapOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to "default-src 'self'; connect-src 'none'; img-src 'self' data:; media-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'self'; form-action 'none'",
            "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
            "Referrer-Policy" to "no-referrer",
            "X-Content-Type-Options" to "nosniff",
        )
        val MigrationBridgeScript = """
            (function () {
              if (window.__BJTU_PLUGIN_MIGRATION_V2__) return;
              var pending = Object.create(null);
              window.__BJTU_PLUGIN_MIGRATION_V2__ = Object.freeze({
                invoke: function (capability, method, params) {
                  return new Promise(function (resolve) {
                    var requestId = String(Date.now()) + '-' + Math.random().toString(16).slice(2);
                    pending[requestId] = resolve;
                    window.BjtuMigrationNative.postMessage(JSON.stringify({
                      protocolVersion: 2,
                      requestId: requestId,
                      capability: capability,
                      method: method,
                      params: params || {}
                    }));
                  });
                }
              });
              window.BjtuMigrationNative.onmessage = function (event) {
                try {
                  var response = JSON.parse(event.data);
                  var callback = pending[response.requestId];
                  if (!callback) return;
                  delete pending[response.requestId];
                  callback(response);
                } catch (_) {}
              };
            })();
        """.trimIndent()
    }
}

private fun migrationOrigin(namespace: ThirdPartyKvNamespace, commitSha: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("${namespace.identity}\u0000$commitSha".toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(20)
    return "https://migration-$digest.third-party.bjtu-mis.local"
}

private fun encodedPath(relativePath: String): String =
    URI(null, null, "/${relativePath.trimStart('/')}", null).rawPath

private fun migrationSuccess(requestId: String, data: kotlinx.serialization.json.JsonElement): JsonObject =
    buildJsonObject {
        put("protocolVersion", THIRD_PARTY_BRIDGE_PROTOCOL_VERSION)
        put("requestId", requestId)
        put("ok", true)
        put("result", data)
    }

private fun migrationError(requestId: String, code: String, message: String): JsonObject =
    buildJsonObject {
        put("protocolVersion", THIRD_PARTY_BRIDGE_PROTOCOL_VERSION)
        put("requestId", requestId)
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
            put("retryable", false)
        })
    }

private fun usageJson(usage: ThirdPartyKvUsage): JsonObject = buildJsonObject {
    put("bytesUsed", usage.bytesUsed)
    put("byteLimit", usage.byteLimit)
    put("keyCount", usage.keyCount)
    put("keyLimit", usage.keyLimit)
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("缺少参数 $name")
