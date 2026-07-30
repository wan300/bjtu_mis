package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PluginLifecycleDispatcher(
    private val service: ThirdPartyService,
    private val transport: BridgeTransport,
    private val kvStore: ThirdPartyKvStore?,
    private val scope: CoroutineScope,
) {
    private var kvWatchJob: Job? = null

    fun start() {
        if ("storage.kv@2" !in service.grantedCapabilities || kvStore == null) return
        val namespace = ThirdPartyKvNamespace(service.publisherSubjectId, service.serviceId)
        kvWatchJob = scope.launch {
            kvStore.watch(namespace).collect { change ->
                transport.sendEvent(
                    PluginRuntimeEvent(
                        capability = "storage.kv@2",
                        event = "changed",
                        data = buildJsonObject {
                            put("revision", change.revision)
                            put("keys", buildJsonArray {
                                change.changedKeys.sorted().forEach { add(JsonPrimitive(it)) }
                            })
                            put("cleared", change.cleared)
                        },
                    ),
                )
            }
        }
    }

    fun resume() = emit("resume")
    fun pause() = emit("pause")

    fun theme(
        colorScheme: String,
        reducedMotion: Boolean,
        highContrast: Boolean,
    ) = emit(
        "theme",
        buildJsonObject {
            put("colorScheme", colorScheme)
            put("reducedMotion", reducedMotion)
            put("highContrast", highContrast)
        },
    )

    fun resize(
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        density: Float,
        fontScale: Float,
        orientation: String,
        safeAreaTopPx: Int,
        safeAreaRightPx: Int,
        safeAreaBottomPx: Int,
        safeAreaLeftPx: Int,
        imeHeightPx: Int,
    ) = emit(
        "resize",
        buildJsonObject {
            put("viewportWidthPx", viewportWidthPx)
            put("viewportHeightPx", viewportHeightPx)
            put("density", density)
            put("fontScale", fontScale)
            put("orientation", orientation)
            put("safeAreaTopPx", safeAreaTopPx)
            put("safeAreaRightPx", safeAreaRightPx)
            put("safeAreaBottomPx", safeAreaBottomPx)
            put("safeAreaLeftPx", safeAreaLeftPx)
            put("imeHeightPx", imeHeightPx)
        },
    )

    fun network(
        online: Boolean,
        validated: Boolean,
        metered: Boolean,
        transportName: String,
    ) = emit(
        "network",
        buildJsonObject {
            put("online", online)
            put("validated", validated)
            put("metered", metered)
            put("transport", transportName)
        },
    )

    suspend fun back(): Boolean =
        transport.sendEventAwaitingAcknowledgement(
            PluginRuntimeEvent(
                capability = "runtime.lifecycle@1",
                event = "back",
                data = buildJsonObject { },
            ),
        )

    fun close() {
        kvWatchJob?.cancel()
        kvWatchJob = null
    }

    private fun emit(event: String, data: JsonElement = buildJsonObject { }) {
        transport.sendEvent(
            PluginRuntimeEvent(
                capability = "runtime.lifecycle@1",
                event = event,
                data = data,
            ),
        )
    }
}
