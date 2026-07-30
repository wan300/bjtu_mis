package cn.edu.bjtu.mis.data.thirdparty

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import cn.edu.bjtu.mis.data.AppJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val MAX_BRIDGE_JSON_BYTES = 512 * 1024
private const val BINARY_CHUNK_BYTES = 256 * 1024
private const val MAX_PENDING_BINARY_REQUESTS = 4

class BridgeTransport(
    private val service: ThirdPartyService,
    private val apiRegistry: ThirdPartyServiceApiRegistry,
    private val confirmer: ThirdPartySensitiveActionConfirmer,
    private val scope: CoroutineScope,
    private val navigationController: PluginNavigationController,
    private val closePlugin: () -> Unit,
    private val diagnostics: PluginDiagnostics,
    private val binaryDirectory: File,
) {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val binaryRequests = ConcurrentHashMap<String, PendingBinaryRequest>()
    private val eventAcknowledgements =
        ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    @Volatile private var awaitingChunk: PendingChunkHeader? = null
    @Volatile private var eventReplyProxy: JavaScriptReplyProxy? = null

    fun onMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        val localOrigin = ThirdPartyServiceSandbox.originFor(
            service.serviceId,
            service.publisherSubjectId,
        )
        if (
            !ThirdPartyWebViewAccessPolicy.isTrustedBridgeMessage(
                isMainFrame,
                sourceOrigin.toString(),
                localOrigin,
            )
        ) {
            diagnostics.record("warning", "bridge_rejected", code = "origin_denied")
            return
        }
        eventReplyProxy = replyProxy
        when (message.type) {
            WebMessageCompat.TYPE_STRING ->
                handleString(message.data.orEmpty(), sourceOrigin.toString(), replyProxy)
            WebMessageCompat.TYPE_ARRAY_BUFFER ->
                handleArrayBuffer(message.arrayBuffer ?: ByteArray(0))
            else -> diagnostics.record("warning", "bridge_rejected", code = "invalid_request")
        }
    }

    fun sendEvent(event: PluginRuntimeEvent) {
        postEvent(event, requiresAcknowledgement = false)
    }

    suspend fun sendEventAwaitingAcknowledgement(
        event: PluginRuntimeEvent,
        timeoutMs: Long = 150L,
    ): Boolean {
        val requestId = UUID.randomUUID().toString()
        val acknowledgement = CompletableDeferred<Boolean>()
        eventAcknowledgements[requestId] = acknowledgement
        if (!postEvent(event.copy(requestId = requestId), requiresAcknowledgement = true)) {
            eventAcknowledgements.remove(requestId)
            return false
        }
        return try {
            withTimeoutOrNull(timeoutMs) { acknowledgement.await() } ?: false
        } finally {
            eventAcknowledgements.remove(requestId, acknowledgement)
        }
    }

    private fun postEvent(
        event: PluginRuntimeEvent,
        requiresAcknowledgement: Boolean,
    ): Boolean {
        val proxy = eventReplyProxy ?: return false
        val validationErrors = ThirdPartyCapabilityRegistry.validateEvent(
            event.capability,
            event.event,
            event.data,
        )
        if (validationErrors.isNotEmpty()) {
            diagnostics.record(
                "warning",
                "event_rejected",
                capability = event.capability,
                code = "invalid_request",
            )
            return false
        }
        val envelope = buildJsonObject {
            put("protocolVersion", THIRD_PARTY_BRIDGE_PROTOCOL_VERSION)
            put("eventId", UUID.randomUUID().toString())
            put("capability", event.capability)
            put("event", event.event)
            event.requestId?.let { put("requestId", it) }
            put("data", event.data)
            if (requiresAcknowledgement) put("requiresAcknowledgement", true)
        }
        scope.launch(Dispatchers.Main.immediate) {
            proxy.postMessage(envelope.toString())
        }
        return true
    }

    fun close() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        binaryRequests.values.forEach(PendingBinaryRequest::discard)
        binaryRequests.clear()
        eventAcknowledgements.values.forEach { it.complete(false) }
        eventAcknowledgements.clear()
        awaitingChunk = null
        eventReplyProxy = null
        scope.cancel()
    }

    private fun handleString(
        json: String,
        callerUrl: String,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BRIDGE_JSON_BYTES) {
            postError(replyProxy, "", "invalid_request", "Bridge request exceeds 512 KiB")
            return
        }
        val envelope = runCatching { AppJson.parseToJsonElement(json).jsonObject }
            .getOrElse {
                postError(replyProxy, "", "invalid_request", "Bridge request is not valid JSON")
                return
            }
        val protocolVersion = envelope["protocolVersion"]?.jsonPrimitive?.intOrNull
        val kind = envelope["kind"]?.jsonPrimitive?.contentOrNull
        val requestId = envelope["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (protocolVersion != THIRD_PARTY_BRIDGE_PROTOCOL_VERSION || !validRequestId(requestId)) {
            postError(replyProxy, requestId, "invalid_request", "Invalid bridge envelope")
            return
        }
        when (kind) {
            "cancel" -> {
                cancelRequest(requestId)
                return
            }
            "eventAck" -> {
                val handled = envelope["handled"]?.jsonPrimitive?.booleanOrNull
                val acknowledgement = eventAcknowledgements[requestId]
                if (handled == null || acknowledgement == null) {
                    diagnostics.record(
                        "warning",
                        "event_ack_rejected",
                        requestId = requestId,
                        code = "invalid_request",
                    )
                    return
                }
                if (eventAcknowledgements.remove(requestId, acknowledgement)) {
                    acknowledgement.complete(handled)
                }
                return
            }
            "binaryChunk" -> {
                val index = envelope["index"]?.jsonPrimitive?.intOrNull
                val last = envelope["last"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                if (index == null || last == null || awaitingChunk != null) {
                    postError(replyProxy, requestId, "invalid_request", "Invalid binary chunk header")
                    return
                }
                awaitingChunk = PendingChunkHeader(requestId, index, last)
                return
            }
        }
        val capability = envelope["capability"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val method = envelope["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val params = envelope["params"] as? JsonObject
        if (
            capability.isBlank() ||
            method.isBlank() ||
            capability.length > 128 ||
            method.length > 128 ||
            params == null
        ) {
            postError(replyProxy, requestId, "invalid_request", "Invalid capability request")
            return
        }
        if (!PluginWebViewPolicy.isCapabilityAvailable(capability)) {
            postError(
                replyProxy,
                requestId,
                "capability_unavailable",
                "Required WebView feature is unavailable",
            )
            return
        }
        val request = PluginBridgeRequest(
            requestId,
            capability,
            method,
            params,
            callerUrl,
            replyProxy,
        )
        val binary = envelope["binary"] as? JsonObject
        if (binary == null) {
            execute(request, null)
            return
        }
        if (!PluginWebViewPolicy.supportsArrayBuffer()) {
            postError(
                replyProxy,
                requestId,
                "capability_unavailable",
                "ArrayBuffer transport is unavailable",
            )
            return
        }
        val size = binary["size"]?.jsonPrimitive?.intOrNull
        val chunks = binary["chunks"]?.jsonPrimitive?.intOrNull
        val maxBytes = when (capability) {
            "storage.blob@1" -> THIRD_PARTY_BLOB_ITEM_BYTES.toInt()
            "cache.resource@1" -> THIRD_PARTY_CACHE_ITEM_BYTES.toInt()
            else -> 0
        }
        if (
            size == null ||
            chunks == null ||
            size < 0 ||
            size > maxBytes ||
            chunks != (size + BINARY_CHUNK_BYTES - 1) / BINARY_CHUNK_BYTES
        ) {
            postError(replyProxy, requestId, "invalid_request", "Invalid binary declaration")
            return
        }
        if (binaryRequests.size >= MAX_PENDING_BINARY_REQUESTS) {
            postError(replyProxy, requestId, "quota_exceeded", "Too many pending binary requests")
            return
        }
        if (!binaryDirectory.isDirectory && !binaryDirectory.mkdirs()) {
            postError(
                replyProxy,
                requestId,
                "capability_unavailable",
                "Unable to create binary staging directory",
            )
            return
        }
        val reservedBytes = binaryRequests.values.sumOf { pending ->
            (pending.expectedSize - pending.writtenBytes).toLong()
        }
        if (
            binaryDirectory.usableSpace - reservedBytes - size <
            THIRD_PARTY_RESOURCE_SAFETY_BYTES
        ) {
            postError(
                replyProxy,
                requestId,
                "quota_exceeded",
                "Binary staging would consume the device safety reserve",
            )
            return
        }
        val pending = runCatching {
            PendingBinaryRequest.create(
                request,
                size,
                chunks,
                binaryDirectory,
            )
        }.getOrElse {
            postError(
                replyProxy,
                requestId,
                "capability_unavailable",
                "Unable to create binary staging file",
            )
            return
        }
        if (binaryRequests.putIfAbsent(requestId, pending) != null) {
            pending.discard()
            postError(replyProxy, requestId, "invalid_request", "Duplicate binary request")
            return
        }
        if (size == 0) {
            binaryRequests.remove(requestId)
            val payload = runCatching { pending.finish() }
                .getOrElse {
                    pending.discard()
                    postError(
                        pending.request.replyProxy,
                        requestId,
                        "capability_unavailable",
                        "Unable to finalize empty binary payload",
                    )
                    return
                }
            execute(pending.request, payload)
        }
    }

    private fun handleArrayBuffer(bytes: ByteArray) {
        val header = awaitingChunk.also { awaitingChunk = null } ?: run {
            diagnostics.record("warning", "binary_rejected", code = "invalid_request")
            return
        }
        val pending = binaryRequests[header.requestId] ?: return
        if (
            header.index != pending.nextChunk ||
            bytes.isEmpty() ||
            bytes.size > BINARY_CHUNK_BYTES ||
            pending.writtenBytes + bytes.size > pending.expectedSize
        ) {
            binaryRequests.remove(header.requestId)?.discard()
            postError(
                pending.request.replyProxy,
                header.requestId,
                "invalid_request",
                "Invalid binary chunk sequence",
            )
            return
        }
        runCatching { pending.write(bytes) }.onFailure {
            binaryRequests.remove(header.requestId)?.discard()
            postError(
                pending.request.replyProxy,
                header.requestId,
                "capability_unavailable",
                "Unable to stage binary payload",
            )
            return
        }
        pending.nextChunk += 1
        val complete = header.last || pending.nextChunk == pending.expectedChunks
        if (complete) {
            binaryRequests.remove(header.requestId)
            if (
                !header.last ||
                pending.writtenBytes != pending.expectedSize ||
                pending.nextChunk != pending.expectedChunks
            ) {
                pending.discard()
                postError(
                    pending.request.replyProxy,
                    header.requestId,
                    "invalid_request",
                    "Incomplete binary payload",
                )
            } else {
                val payload = runCatching { pending.finish() }
                    .getOrElse {
                        pending.discard()
                        postError(
                            pending.request.replyProxy,
                            header.requestId,
                            "capability_unavailable",
                            "Unable to finalize binary payload",
                        )
                        return
                    }
                execute(pending.request, payload)
            }
        }
    }

    private fun execute(request: PluginBridgeRequest, binary: PluginBinaryPayload?) {
        val startedAt = System.currentTimeMillis()
        val job = scope.launch {
            try {
                val response = apiRegistry.invoke(
                    service = service,
                    capability = request.capability,
                    method = request.method,
                    params = request.params,
                    binary = binary,
                    confirmer = confirmer,
                    currentPageUrl = request.callerUrl,
                    openExternal = navigationController::openFromCapability,
                    closePlugin = closePlugin,
                    eventSink = ::sendEvent,
                    requestId = request.requestId,
                )
                val ok = response["ok"]?.jsonPrimitive?.contentOrNull == "true"
                diagnostics.record(
                    if (ok) "info" else "warning",
                    "capability_invoke",
                    requestId = request.requestId,
                    capability = request.capability,
                    method = request.method,
                    code = (response["error"] as? JsonObject)
                        ?.get("code")
                        ?.jsonPrimitive
                        ?.contentOrNull,
                    durationMs = System.currentTimeMillis() - startedAt,
                )
                withContext(Dispatchers.Main.immediate) {
                    request.replyProxy.postMessage(
                        normalizeResponse(request.requestId, response).toString(),
                    )
                }
            } finally {
                binary?.close()
            }
        }
        jobs[request.requestId] = job
        job.invokeOnCompletion {
            jobs.remove(request.requestId, job)
            binary?.close()
        }
    }

    private fun cancelRequest(requestId: String) {
        binaryRequests.remove(requestId)?.discard()
        jobs.remove(requestId)?.cancel()
        apiRegistry.cancel(service, requestId)
        diagnostics.record(
            "info",
            "request_cancelled",
            requestId = requestId,
            code = "user_cancelled",
        )
    }

    private fun postError(
        replyProxy: JavaScriptReplyProxy,
        requestId: String,
        code: String,
        message: String,
    ) {
        diagnostics.record("warning", "bridge_error", requestId = requestId, code = code)
        scope.launch(Dispatchers.Main.immediate) {
            replyProxy.postMessage(
                buildJsonObject {
                    put("protocolVersion", THIRD_PARTY_BRIDGE_PROTOCOL_VERSION)
                    put("requestId", requestId)
                    put("ok", false)
                    put("error", buildJsonObject {
                        put("code", code)
                        put("message", message)
                        put("retryable", false)
                    })
                }.toString(),
            )
        }
    }

    private fun normalizeResponse(requestId: String, response: JsonObject): JsonObject =
        buildJsonObject {
            put("protocolVersion", THIRD_PARTY_BRIDGE_PROTOCOL_VERSION)
            put("requestId", requestId)
            val ok = response["ok"]?.jsonPrimitive?.contentOrNull == "true"
            put("ok", ok)
            if (ok) {
                put("result", response["result"] ?: JsonObject(emptyMap()))
            } else {
                put(
                    "error",
                    response["error"] ?: buildJsonObject {
                        put("code", "capability_unavailable")
                        put("message", "Capability invocation failed")
                        put("retryable", false)
                    },
                )
            }
        }

    private fun validRequestId(value: String): Boolean =
        value.isNotBlank() && value.length <= 128 && value.none { it.code < 0x20 }

    companion object {
        const val OBJECT_NAME = "BjtuPluginNativeV2"

        fun documentStartScript(binarySupported: Boolean): String = """
            (function () {
              if (window.__BJTU_MANAGED_STORAGE_ONLY__ !== true) return;
              if (window.__BJTU_PLUGIN_BRIDGE_V2__) return;
              var nativeBridge = window.$OBJECT_NAME;
              var listeners = new Set();
              var parse = function (value) {
                if (typeof value !== 'string') return value;
                try { return JSON.parse(value); } catch (_) { return null; }
              };
              var bridge = Object.freeze({
                binarySupported: ${if (binarySupported) "true" else "false"},
                postMessage: function (message) {
                  if (!nativeBridge || typeof nativeBridge.postMessage !== 'function') {
                    throw new Error('BJTU plugin transport unavailable');
                  }
                  if (
                    message &&
                    message.kind === 'binaryChunk' &&
                    message.payload instanceof ArrayBuffer
                  ) {
                    var metadata = {
                      protocolVersion: message.protocolVersion,
                      kind: message.kind,
                      requestId: message.requestId,
                      index: message.index,
                      last: message.last
                    };
                    nativeBridge.postMessage(JSON.stringify(metadata));
                    nativeBridge.postMessage(message.payload);
                    return;
                  }
                  nativeBridge.postMessage(JSON.stringify(message));
                },
                addEventListener: function (listener) {
                  listeners.add(listener);
                  return function () { listeners.delete(listener); };
                }
              });
              Object.defineProperty(window, '__BJTU_PLUGIN_BRIDGE_V2__', {
                value: bridge,
                configurable: false,
                enumerable: false,
                writable: false
              });
              if (nativeBridge) {
                nativeBridge.onmessage = function (event) {
                  var message = parse(event.data);
                  if (!message) return;
                  listeners.forEach(function (listener) {
                    try { listener(message); } catch (_) {}
                  });
                };
              }
            })();
        """.trimIndent()
    }

    private data class PluginBridgeRequest(
        val requestId: String,
        val capability: String,
        val method: String,
        val params: JsonObject,
        val callerUrl: String,
        val replyProxy: JavaScriptReplyProxy,
    )

    private class PendingBinaryRequest private constructor(
        val request: PluginBridgeRequest,
        val expectedSize: Int,
        val expectedChunks: Int,
        private val file: File,
        private val output: BufferedOutputStream,
        var nextChunk: Int = 0,
        var writtenBytes: Int = 0,
    ) {
        fun write(bytes: ByteArray) {
            output.write(bytes)
            writtenBytes += bytes.size
        }

        fun finish(): PluginBinaryPayload {
            output.flush()
            output.close()
            return PluginBinaryPayload(writtenBytes.toLong(), file)
        }

        fun discard() {
            runCatching(output::close)
            file.delete()
        }

        companion object {
            fun create(
                request: PluginBridgeRequest,
                expectedSize: Int,
                expectedChunks: Int,
                directory: File,
            ): PendingBinaryRequest {
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw java.io.IOException("Cannot create binary staging directory")
                }
                val file = File.createTempFile("bridge-", ".part", directory)
                return try {
                    PendingBinaryRequest(
                        request,
                        expectedSize,
                        expectedChunks,
                        file,
                        BufferedOutputStream(FileOutputStream(file), BINARY_CHUNK_BYTES),
                    )
                } catch (error: Exception) {
                    file.delete()
                    throw error
                }
            }
        }
    }

    private data class PendingChunkHeader(
        val requestId: String,
        val index: Int,
        val last: Boolean,
    )
}
