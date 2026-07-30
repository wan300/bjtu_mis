package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import java.io.Closeable
import java.io.File
import java.io.InputStream

class PluginBinaryPayload internal constructor(
    val size: Long,
    private val file: File,
) : Closeable {
    fun openInputStream(): InputStream = file.inputStream()

    override fun close() {
        file.delete()
    }
}

internal class PluginRuntimeException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    val httpStatus: Int? = null,
    val details: JsonElement? = null,
) : ThirdPartyServiceException(message)

data class PluginCapabilityCall(
    val service: ThirdPartyService,
    val capability: String,
    val method: String,
    val params: JsonObject,
    val binary: PluginBinaryPayload?,
    val confirmer: ThirdPartySensitiveActionConfirmer,
    val currentPageUrl: String,
    val openExternal: (String) -> Boolean,
    val closePlugin: () -> Unit,
    val eventSink: (PluginRuntimeEvent) -> Unit,
    val requestId: String,
)

interface PluginCapabilityProvider {
    val capabilityIds: Set<String>

    suspend fun invoke(call: PluginCapabilityCall): kotlinx.serialization.json.JsonElement
}

class PluginCapabilityProviderRegistry(
    providers: List<PluginCapabilityProvider>,
    overrides: List<PluginCapabilityProvider> = emptyList(),
) {
    private val byCapability: Map<String, PluginCapabilityProvider>

    init {
        for ((label, candidates) in listOf("providers" to providers, "overrides" to overrides)) {
            val duplicateIds = candidates
                .flatMap(PluginCapabilityProvider::capabilityIds)
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            require(duplicateIds.isEmpty()) {
                "Capability $label overlap: ${duplicateIds.sorted().joinToString()}"
            }
        }
        byCapability = (
            providers
            .flatMap { provider -> provider.capabilityIds.map { it to provider } }
            .toMap() +
                overrides.flatMap { provider ->
                    provider.capabilityIds.map { it to provider }
                }.toMap()
            )
    }

    val capabilityIds: Set<String>
        get() = byCapability.keys

    suspend fun invoke(call: PluginCapabilityCall): kotlinx.serialization.json.JsonElement =
        byCapability[call.capability]?.invoke(call)
            ?: throw ThirdPartyServiceException(
                "No Android provider is registered for ${call.capability}",
            )
}

internal class LambdaPluginCapabilityProvider(
    override val capabilityIds: Set<String>,
    private val handler: suspend (PluginCapabilityCall) -> kotlinx.serialization.json.JsonElement,
) : PluginCapabilityProvider {
    override suspend fun invoke(
        call: PluginCapabilityCall,
    ): kotlinx.serialization.json.JsonElement = handler(call)
}
