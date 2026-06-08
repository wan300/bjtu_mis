package cn.edu.bjtu.mis.data.thirdparty

import kotlinx.serialization.Serializable

const val THIRD_PARTY_SERVICE_SCHEMA_VERSION = 1

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
