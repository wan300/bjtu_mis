package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.thirdparty.generated.GeneratedCapabilityContracts
import cn.edu.bjtu.mis.data.thirdparty.generated.GeneratedCapabilityDescriptor
import cn.edu.bjtu.mis.data.thirdparty.generated.GeneratedCapabilityRoute
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Runtime facade over the generated contract registry. Author-provided
 * manifests select capability IDs; permissions, limits and routing remain
 * host-owned metadata.
 */
object ThirdPartyCapabilityRegistry {
    private val byId = GeneratedCapabilityContracts.capabilities.associateBy { it.id }

    val capabilities: List<GeneratedCapabilityDescriptor>
        get() = GeneratedCapabilityContracts.capabilities

    fun get(id: String): GeneratedCapabilityDescriptor? = byId[id]

    fun requireKnown(id: String): GeneratedCapabilityDescriptor =
        get(id) ?: throw ThirdPartyServiceException("未知插件 capability：$id")

    fun permissionsFor(capabilityIds: Iterable<String>): Set<String> =
        capabilityIds.mapNotNullTo(linkedSetOf()) { byId[it]?.permission }

    fun capabilitiesForPermission(
        permissionId: String,
        declaredCapabilities: Iterable<String>,
    ): Set<String> = declaredCapabilities
        .filterTo(linkedSetOf()) { byId[it]?.permission == permissionId }

    fun runtimeFloor(capabilityIds: Iterable<String>): Int =
        capabilityIds.maxOfOrNull { requireKnown(it).runtimeFloor }
            ?: GeneratedCapabilityContracts.RUNTIME_FLOOR

    fun route(capability: String, method: String): GeneratedCapabilityRoute? =
        GeneratedCapabilityContracts.routes["$capability#$method"]

    fun validateRequest(
        capability: String,
        method: String,
        payload: JsonObject,
    ): List<String> = GeneratedCapabilityContracts.validateRequest(capability, method, payload)

    fun validateResponse(
        capability: String,
        method: String,
        payload: JsonElement,
    ): List<String> = GeneratedCapabilityContracts.validateResponse(capability, method, payload)

    fun validateEvent(
        capability: String,
        event: String,
        payload: JsonElement,
    ): List<String> = GeneratedCapabilityContracts.validateEvent(capability, event, payload)

    fun eventRequiresAcknowledgement(capability: String, event: String): Boolean =
        GeneratedCapabilityContracts.eventDescriptor(
            capability,
            event,
        )?.requiresAcknowledgement == true

    fun requiresPerCallConfirmation(capability: String): Boolean =
        requireKnown(capability).confirmation == "eachCall"

    fun requiresIdempotencyKey(capability: String): Boolean =
        requireKnown(capability).idempotencyRequired

    fun requiredWebViewFeatures(capability: String): Set<String> =
        requireKnown(capability).webViewFeatures

    fun isStorageCapability(capability: String): Boolean =
        capability == "storage.kv@2" || capability == "storage.blob@1"
}
