package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient

const val THIRD_PARTY_SERVICE_SCHEMA_VERSION_V1 = 1
const val THIRD_PARTY_SERVICE_SCHEMA_VERSION = 2

@Serializable
data class ThirdPartyServiceManifest(
    val schemaVersion: Int = 0,
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val entrypoint: String = "",
    val icon: String = "",
    val author: String = "",
    val permissions: ThirdPartyServicePermissionDeclaration = ThirdPartyServicePermissionDeclaration(),
    val allowedOrigins: List<String> = emptyList(),
    val marketplace: ThirdPartyMarketplaceMetadata? = null,
    val configuration: List<ThirdPartyConfigurationDefinition> = emptyList(),
)

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
) {
    val route: String = thirdPartyServiceRoute(serviceId)
}

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
    @SerialName("allowedOrigins") val allowedOrigins: List<String> = emptyList(),
    val configuration: List<ThirdPartyConfigurationDefinition> = emptyList(),
    @SerialName("validationWarnings") val validationWarnings: List<String> = emptyList(),
    @SerialName("verificationLevel") val verificationLevel: String = "automated",
    @SerialName("publishedAt") val publishedAt: String = "",
    @SerialName("iconUrl") val iconUrl: String = "",
    @SerialName("artifactUrl") val artifactUrl: String = "",
    @SerialName("updateAvailable") val updateAvailable: Boolean = false,
)

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
)

@Serializable
data class CatalogUpdateResponse(
    val items: List<CatalogPlugin> = emptyList(),
)

data class GitHubRepositoryRef(
    val owner: String,
    val repo: String,
    val canonicalUrl: String,
)

class ThirdPartyServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun thirdPartyServiceRoute(serviceId: String): String = "$THIRD_PARTY_SERVICE_ROUTE_PREFIX$serviceId"

fun thirdPartyServiceIdFromRoute(route: String): String? =
    route.takeIf { it.startsWith(THIRD_PARTY_SERVICE_ROUTE_PREFIX) }
        ?.removePrefix(THIRD_PARTY_SERVICE_ROUTE_PREFIX)
        ?.takeIf { it.isNotBlank() }

const val THIRD_PARTY_SERVICES_ROUTE = "third_party_services"
const val THIRD_PARTY_SERVICE_ROUTE_PREFIX = "third_party_service/"
