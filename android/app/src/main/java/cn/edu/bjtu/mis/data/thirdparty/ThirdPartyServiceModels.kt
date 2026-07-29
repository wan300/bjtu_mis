package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient

const val THIRD_PARTY_SERVICE_SCHEMA_VERSION = 3
const val THIRD_PARTY_RUNTIME_VERSION = 1
const val THIRD_PARTY_BRIDGE_PROTOCOL_VERSION = 1

val THIRD_PARTY_RUNTIME_CAPABILITIES: Set<String> = setOf(
    "runtime.lifecycle.v1",
    "storage.kv.v1",
    "campus.request.v1",
    "remote.frame.v1",
)

@Serializable
data class ThirdPartyServiceManifest(
    val schemaVersion: Int = 0,
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val runtimeVersion: Int = 0,
    val minRuntimeVersion: Int = 0,
    val requiredCapabilities: List<String> = emptyList(),
    val optionalCapabilities: List<String> = emptyList(),
    val dataSchemaVersion: Int = 0,
    val migrationEntrypoint: String? = null,
    val entrypoint: String = "",
    val icon: String = "",
    val author: String = "",
    val permissions: ThirdPartyServicePermissionDeclaration = ThirdPartyServicePermissionDeclaration(),
    val connectOrigins: List<String> = emptyList(),
    val mediaOrigins: List<String> = emptyList(),
    val frameOrigins: List<String> = emptyList(),
    val navigationOrigins: List<String> = emptyList(),
    val bridgeOrigins: List<String> = emptyList(),
    val marketplace: ThirdPartyMarketplaceMetadata? = null,
    val configuration: List<ThirdPartyConfigurationDefinition> = emptyList(),
) {
    val remoteOrigins: List<String>
        get() = (connectOrigins + mediaOrigins + frameOrigins + navigationOrigins).distinct()
}

@Serializable
data class ThirdPartyMarketplaceMetadata(
    val category: String = "",
    val tags: List<String> = emptyList(),
    val license: String? = null,
)

@Serializable
data class ThirdPartyConfigurationDefinition(
    val key: String = "",
    val label: String = "",
    val description: String = "",
    val type: String = "text",
    val required: Boolean = false,
    val default: String? = null,
    val options: List<String> = emptyList(),
)

@Serializable
data class ThirdPartyServicePermissionDeclaration(
    val required: List<String> = emptyList(),
    val optional: List<String> = emptyList(),
)

data class ThirdPartyService(
    val serviceId: String,
    val manifest: ThirdPartyServiceManifest,
    val sourceUrl: String,
    val githubOwner: String,
    val githubRepo: String,
    val defaultBranch: String,
    val commitSha: String,
    val packageDigestSha256: String,
    val installDir: String,
    val grantedPermissions: Set<String>,
    val allowedOrigins: List<String>,
    val enabled: Boolean,
    val needsReview: Boolean,
    val installedAt: String,
    val updatedAt: String,
    val publisherSubjectId: String = "",
    val dataSchemaVersion: Int = 0,
    val compatibilityState: String = ThirdPartyCompatibilityState.LegacyDisabled.value,
    val verificationLevel: String = "legacy",
    val previousVersion: ThirdPartyInstalledVersionSnapshot? = null,
) {
    val route: String = thirdPartyServiceRoute(serviceId)
    val iconSource: ThirdPartyIconSource?
        get() = resolveLocalThirdPartyIconSource(java.io.File(installDir), manifest.icon)
}

enum class ThirdPartyCompatibilityState(val value: String) {
    Compatible("compatible"),
    LegacyDisabled("legacy_disabled"),
    RuntimeUnsupported("runtime_unsupported"),
}

@Serializable
data class ThirdPartyInstalledVersionSnapshot(
    val version: String,
    val commitSha: String,
    val packageDigestSha256: String,
    val installDir: String,
    val manifest: ThirdPartyServiceManifest,
    val dataSchemaVersion: Int,
    val sourceUrl: String,
    val githubOwner: String,
    val githubRepo: String,
    val defaultBranch: String,
    val publisherSubjectId: String,
    val grantedPermissions: Set<String>,
    val enabled: Boolean,
    val needsReview: Boolean,
    val verificationLevel: String,
)

data class ThirdPartyServiceInstallResult(
    val service: ThirdPartyService,
    val updatedExisting: Boolean,
)

data class ThirdPartyServiceImportPreview(
    val token: String,
    val manifest: ThirdPartyServiceManifest,
    val sourceUrl: String,
    val githubOwner: String,
    val githubRepo: String,
    val defaultBranch: String,
    val commitSha: String,
    val packageDigestSha256: String,
    val packageBytes: Long,
    val packageFileCount: Int,
    val updatedExisting: Boolean,
    val archiveSha256: String? = null,
    val platformVerified: Boolean = false,
    val iconSource: ThirdPartyIconSource? = null,
    val publisherSubjectId: String = "",
    val verificationLevel: String = "unverified",
    val previousService: ThirdPartyService? = null,
    val addedRequiredPermissions: List<String> = emptyList(),
    val addedOptionalPermissions: List<String> = emptyList(),
    val removedPermissions: List<String> = emptyList(),
    val addedOrigins: List<String> = emptyList(),
    val removedOrigins: List<String> = emptyList(),
)

@Serializable
data class CatalogPublisherIdentity(
    @SerialName("subjectId") val subjectId: String? = null,
    @SerialName("ownerId") val ownerId: String? = null,
    val login: String? = null,
    val type: String? = null,
    @SerialName("transferStatus") val transferStatus: String = "none",
)

@Serializable
data class CatalogPlugin(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val category: String = "other",
    val tags: List<String> = emptyList(),
    val license: String? = null,
    @SerialName("repositoryUrl") val repositoryUrl: String = "",
    val repository: String = "",
    @SerialName("commitSha") val commitSha: String = "",
    @SerialName("archiveSha256") val archiveSha256: String = "",
    @SerialName("packageDigestSha256") val packageDigestSha256: String = "",
    @SerialName("packageBytes") val packageBytes: Long = 0,
    @SerialName("packageFileCount") val packageFileCount: Int = 0,
    val permissions: ThirdPartyServicePermissionDeclaration = ThirdPartyServicePermissionDeclaration(),
    @SerialName("schemaVersion") val schemaVersion: Int = 0,
    @SerialName("allowedOrigins") val allowedOrigins: List<String> = emptyList(),
    @SerialName("connectOrigins") val connectOrigins: List<String> = emptyList(),
    @SerialName("mediaOrigins") val mediaOrigins: List<String> = emptyList(),
    @SerialName("frameOrigins") val frameOrigins: List<String> = emptyList(),
    @SerialName("navigationOrigins") val navigationOrigins: List<String> = emptyList(),
    @SerialName("bridgeOrigins") val bridgeOrigins: List<String> = emptyList(),
    @SerialName("runtimeVersion") val runtimeVersion: Int = 0,
    @SerialName("minRuntimeVersion") val minRuntimeVersion: Int = 0,
    @SerialName("requiredCapabilities") val requiredCapabilities: List<String> = emptyList(),
    @SerialName("optionalCapabilities") val optionalCapabilities: List<String> = emptyList(),
    @SerialName("dataSchemaVersion") val dataSchemaVersion: Int = 0,
    @SerialName("migrationEntrypoint") val migrationEntrypoint: String? = null,
    @SerialName("publisherSubjectId") val publisherSubjectId: String = "",
    @SerialName("publisherIdentity") val publisherIdentity: CatalogPublisherIdentity = CatalogPublisherIdentity(),
    @SerialName("compatibilityState") val compatibilityState: String = ThirdPartyCompatibilityState.LegacyDisabled.value,
    val configuration: List<ThirdPartyConfigurationDefinition> = emptyList(),
    @SerialName("validationWarnings") val validationWarnings: List<String> = emptyList(),
    @SerialName("verificationLevel") val verificationLevel: String = "automated",
    @SerialName("publishedAt") val publishedAt: String = "",
    @SerialName("iconUrl") val iconUrl: String = "",
    @SerialName("artifactUrl") val artifactUrl: String = "",
    @SerialName("updateAvailable") val updateAvailable: Boolean = false,
    @SerialName("publisherMismatch") val publisherMismatch: Boolean = false,
) {
    val publisherOwnerLogin: String
        get() = publisherIdentity.login.orEmpty()

    val iconSource: ThirdPartyIconSource?
        get() = resolveRemoteThirdPartyIconSource(iconUrl)
}

@Serializable
data class CatalogPluginPage(
    val items: List<CatalogPlugin> = emptyList(),
    @SerialName("nextCursor") val nextCursor: String? = null,
    @Transient val fromCache: Boolean = false,
)

@Serializable
data class CatalogUpdateRequest(
    val installed: List<CatalogInstalledVersion>,
)

@Serializable
data class CatalogInstalledVersion(
    val id: String,
    @SerialName("commitSha") val commitSha: String,
    @SerialName("publisherSubjectId") val publisherSubjectId: String,
)

@Serializable
data class CatalogUpdateResponse(
    val items: List<CatalogPlugin> = emptyList(),
)

data class GitHubRepositoryRef(
    val owner: String,
    val repo: String,
    val canonicalUrl: String,
    val ownerId: String? = null,
)

class ThirdPartyServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun thirdPartyServiceRoute(serviceId: String): String = "$THIRD_PARTY_SERVICE_ROUTE_PREFIX$serviceId"

fun thirdPartyServiceIdFromRoute(route: String): String? =
    route.takeIf { it.startsWith(THIRD_PARTY_SERVICE_ROUTE_PREFIX) }
        ?.removePrefix(THIRD_PARTY_SERVICE_ROUTE_PREFIX)
        ?.takeIf { it.isNotBlank() }

const val THIRD_PARTY_SERVICES_ROUTE = "third_party_services"
const val THIRD_PARTY_SERVICE_ROUTE_PREFIX = "third_party_service/"
