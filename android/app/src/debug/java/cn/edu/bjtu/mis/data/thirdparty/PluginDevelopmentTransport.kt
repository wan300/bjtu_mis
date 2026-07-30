package cn.edu.bjtu.mis.data.thirdparty

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.util.concurrent.TimeUnit

class PluginDevelopmentTransport(
    context: Context,
    private val service: ThirdPartyService,
) : PluginDevelopmentRuntime {
    private val preferences = context.getSharedPreferences(
        PluginDevelopmentReceiver.PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val port = preferences.getInt(
        "${service.serviceId}.port",
        PluginDevelopmentReceiver.DEFAULT_PORT,
    )
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var stableOrigin: String = ""
    private var replyProxy: JavaScriptReplyProxy? = null
    private var webSocket: WebSocket? = null

    init {
        require(preferences.getBoolean("${service.serviceId}.enabled", false)) {
            "Plugin development transport is not explicitly enabled"
        }
        require(port in 1024..65535) { "Invalid plugin development port" }
    }

    override fun attach(webView: WebView, stableOrigin: String) {
        this.stableOrigin = stableOrigin
        WebViewCompat.addDocumentStartJavaScript(
            webView,
            DEVELOPMENT_WEBSOCKET_SCRIPT,
            setOf(stableOrigin),
        )
        WebViewCompat.addWebMessageListener(
            webView,
            OBJECT_NAME,
            setOf(stableOrigin),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy,
                ) {
                    if (
                        message.type != WebMessageCompat.TYPE_STRING ||
                        !ThirdPartyWebViewAccessPolicy.isTrustedBridgeMessage(
                            isMainFrame,
                            sourceOrigin.toString(),
                            stableOrigin,
                        )
                    ) {
                        return
                    }
                    this@PluginDevelopmentTransport.replyProxy = replyProxy
                    handleWebSocketMessage(message.data.orEmpty())
                }
            },
        )
    }

    override fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.method.uppercase() !in setOf("GET", "HEAD")) {
            return errorResponse(405, "Method Not Allowed")
        }
        val target = request.url.buildUpon()
            .scheme("http")
            .encodedAuthority("127.0.0.1:$port")
            .build()
            .toString()
        val outbound = Request.Builder()
            .url(target)
            .method(request.method.uppercase(), null)
            .apply {
                listOf("Accept", "If-None-Match", "If-Modified-Since").forEach { name ->
                    request.requestHeaders.entries
                        .firstOrNull { (candidate, _) -> candidate.equals(name, ignoreCase = true) }
                        ?.value
                        ?.let { header(name, it) }
                }
            }
            .build()
        return try {
            val response = client.newCall(outbound).execute()
            val mediaType = response.body?.contentType()?.toString()
                ?: response.header("Content-Type")
                ?: "application/octet-stream"
            val headers = PluginWebViewPolicy.securityHeaders(
                service.manifest,
                service.grantedCapabilities,
            ).toMutableMap().apply {
                response.header("ETag")?.let { put("ETag", it) }
                response.header("Last-Modified")?.let { put("Last-Modified", it) }
                response.body?.contentLength()?.takeIf { it >= 0 }?.let {
                    put("Content-Length", it.toString())
                }
                put("X-BJTU-Plugin-Development", "vite")
            }
            val body = if (request.method.equals("HEAD", ignoreCase = true) || response.body == null) {
                response.close()
                ByteArrayInputStream(ByteArray(0))
            } else {
                ResponseClosingInputStream(response)
            }
            WebResourceResponse(
                mediaType.substringBefore(';'),
                encodingFor(mediaType),
                response.code,
                response.message.ifBlank { "Vite" },
                headers,
                body,
            )
        } catch (_: Exception) {
            errorResponse(502, "Vite Development Server Unavailable")
        }
    }

    override fun close(webView: WebView) {
        webSocket?.close(1000, "runtime closed")
        webSocket = null
        replyProxy = null
        runCatching { WebViewCompat.removeWebMessageListener(webView, OBJECT_NAME) }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun handleWebSocketMessage(raw: String) {
        val message = runCatching {
            cn.edu.bjtu.mis.data.AppJson.parseToJsonElement(raw).jsonObject
        }.getOrNull() ?: return
        when (message["kind"]?.jsonPrimitive?.contentOrNull) {
            "connect" -> connectWebSocket()
            "send" -> message["data"]?.jsonPrimitive?.contentOrNull?.let { webSocket?.send(it) }
            "close" -> webSocket?.close(1000, "client closed")
        }
    }

    private fun connectWebSocket() {
        webSocket?.cancel()
        val request = Request.Builder()
            .url("ws://127.0.0.1:$port$HMR_PATH")
            .build()
        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    emit("""{"kind":"open"}""")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    emit(
                        cn.edu.bjtu.mis.data.AppJson.encodeToString(
                            buildJsonObject {
                                put("kind", "message")
                                put("data", text)
                            },
                        ),
                    )
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    emit("""{"kind":"error"}""")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    emit("""{"kind":"close","code":$code}""")
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    throwable: Throwable,
                    response: Response?,
                ) {
                    emit("""{"kind":"error"}""")
                    emit("""{"kind":"close","code":1006}""")
                }
            },
        )
    }

    private fun emit(payload: String) {
        replyProxy?.postMessage(payload)
    }

    private fun errorResponse(status: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            status,
            reason,
            PluginWebViewPolicy.securityHeaders(
                service.manifest,
                service.grantedCapabilities,
            ) +
                ("X-BJTU-Plugin-Development" to "vite"),
            ByteArrayInputStream(reason.toByteArray()),
        )

    private fun encodingFor(mediaType: String): String? =
        if (
            mediaType.startsWith("text/") ||
            mediaType.startsWith("application/json") ||
            mediaType.startsWith("application/javascript")
        ) {
            "UTF-8"
        } else {
            null
        }

    private class ResponseClosingInputStream(
        private val response: Response,
    ) : FilterInputStream(requireNotNull(response.body).byteStream()) {
        override fun close() {
            try {
                super.close()
            } finally {
                response.close()
            }
        }
    }

    private companion object {
        const val OBJECT_NAME = "BjtuPluginDevNative"
        const val HMR_PATH = "/__bjtu/dev-hmr"
        val DEVELOPMENT_WEBSOCKET_SCRIPT = """
            (function () {
              if (window.__BJTU_PLUGIN_DEV_WEBSOCKET__) return;
              var NativeWebSocket = window.WebSocket;
              var bridge = window.$OBJECT_NAME;
              function DevWebSocket(url) {
                var parsed = new URL(url, window.location.href);
                if (parsed.origin !== window.location.origin || parsed.pathname !== '$HMR_PATH') {
                  return new NativeWebSocket(url);
                }
                this.url = parsed.href;
                this.readyState = 0;
                this.protocol = '';
                this.extensions = '';
                this.bufferedAmount = 0;
                this.binaryType = 'blob';
                this._listeners = Object.create(null);
                var self = this;
                bridge.onmessage = function (event) {
                  var message;
                  try { message = JSON.parse(event.data); } catch (_) { return; }
                  if (message.kind === 'open') {
                    self.readyState = 1;
                    self._emit('open', new Event('open'));
                  } else if (message.kind === 'message') {
                    self._emit('message', new MessageEvent('message', { data: message.data }));
                  } else if (message.kind === 'error') {
                    self._emit('error', new Event('error'));
                  } else if (message.kind === 'close') {
                    self.readyState = 3;
                    self._emit('close', new CloseEvent('close', { code: message.code || 1000 }));
                  }
                };
                bridge.postMessage(JSON.stringify({ kind: 'connect' }));
              }
              DevWebSocket.CONNECTING = 0;
              DevWebSocket.OPEN = 1;
              DevWebSocket.CLOSING = 2;
              DevWebSocket.CLOSED = 3;
              DevWebSocket.prototype.CONNECTING = 0;
              DevWebSocket.prototype.OPEN = 1;
              DevWebSocket.prototype.CLOSING = 2;
              DevWebSocket.prototype.CLOSED = 3;
              DevWebSocket.prototype.addEventListener = function (name, listener) {
                (this._listeners[name] || (this._listeners[name] = new Set())).add(listener);
              };
              DevWebSocket.prototype.removeEventListener = function (name, listener) {
                if (this._listeners[name]) this._listeners[name].delete(listener);
              };
              DevWebSocket.prototype._emit = function (name, event) {
                var handler = this['on' + name];
                if (typeof handler === 'function') handler.call(this, event);
                var listeners = this._listeners[name];
                if (listeners) listeners.forEach(function (listener) { listener.call(this, event); }, this);
              };
              DevWebSocket.prototype.send = function (data) {
                if (this.readyState !== 1 || typeof data !== 'string') {
                  throw new DOMException('Development WebSocket accepts text while open.', 'InvalidStateError');
                }
                bridge.postMessage(JSON.stringify({ kind: 'send', data: data }));
              };
              DevWebSocket.prototype.close = function () {
                if (this.readyState >= 2) return;
                this.readyState = 2;
                bridge.postMessage(JSON.stringify({ kind: 'close' }));
              };
              Object.defineProperty(window, '__BJTU_PLUGIN_DEV_WEBSOCKET__', {
                value: true, enumerable: false, configurable: false, writable: false
              });
              window.WebSocket = DevWebSocket;
            })();
        """.trimIndent()
    }
}
