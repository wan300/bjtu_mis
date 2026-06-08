package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ThirdPartyServiceRepository(
    private val dao: ThirdPartyServiceDao,
    private val installer: ThirdPartyServiceInstaller,
    private val bundledProvider: ThirdPartyBundledServiceProvider? = null,
) {
    private val bundledInstallMutex = Mutex()
    private var bundledInstallChecked = false

    suspend fun ensureBundledServicesInstalled() {
        val provider = bundledProvider ?: return
        bundledInstallMutex.withLock {
            if (!bundledInstallChecked) {
                val existingById = dao.listServices().associateBy { it.serviceId }
                val installed = provider.installMissingOrUpdated(existingById)
                installed.forEach { bundled ->
                    val existing = existingById[bundled.packageInfo.manifest.id]
                    val defaultGranted = bundled.defaultGrantedPermissionsForInstalledManifest()
                    val required = bundled.packageInfo.manifest.permissions.required.toSet()
                    val defaultEnabled = defaultGranted.containsAll(required)
                    dao.saveService(
                        bundled.packageInfo.toEntity(
                            existing = existing,
                            now = nowIso(),
                            grantedPermissions = defaultGranted,
                            enabled = defaultEnabled,
                            needsReview = !defaultEnabled,
                        )
                    )
                }
                bundledInstallChecked = true
            }
        }
    }

    suspend fun listServices(): List<ThirdPartyService> {
        ensureBundledServicesInstalled()
        return dao.listServices().map { it.toModel() }
    }

    suspend fun getService(serviceId: String): ThirdPartyService? {
        ensureBundledServicesInstalled()
        return dao.getService(serviceId)?.toModel()
    }

    suspend fun prepareImportFromGitHub(sourceUrl: String): ThirdPartyServiceImportPreview {
        val prepared = installer.prepareFromGitHub(sourceUrl)
        val existing = dao.getService(prepared.manifest.id)
        return prepared.toPreview(existing != null)
    }

    suspend fun commitPreparedImport(token: String): ThirdPartyServiceInstallResult {
        val installed = installer.commitPreparedImport(token)
        val existing = dao.getService(installed.manifest.id)
        val now = nowIso()
        val entity = installed.toEntity(
            existing = existing,
            now = now,
        )
        dao.saveService(entity)
        return ThirdPartyServiceInstallResult(
            service = entity.toModel(),
            updatedExisting = existing != null,
        )
    }

    suspend fun importFromGitHub(sourceUrl: String): ThirdPartyServiceInstallResult {
        val installed = installer.installFromGitHub(sourceUrl)
        val existing = dao.getService(installed.manifest.id)
        val now = nowIso()
        val entity = installed.toEntity(
            existing = existing,
            now = now,
        )
        dao.saveService(entity)
        return ThirdPartyServiceInstallResult(
            service = entity.toModel(),
            updatedExisting = existing != null,
        )
    }

    fun discardPreparedImport(token: String) {
        installer.discardPreparedImport(token)
    }

    fun cleanupStalePreparedImports() {
        installer.cleanupStalePreparedImports()
    }

    suspend fun grantService(serviceId: String, grantedPermissions: Set<String>): ThirdPartyService {
        val service = getService(serviceId) ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
        val required = service.manifest.permissions.required.toSet()
        val declared = (service.manifest.permissions.required + service.manifest.permissions.optional).toSet()
        val normalized = grantedPermissions.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (!normalized.containsAll(required)) {
            throw ThirdPartyServiceException("必须授权的权限未全部同意")
        }
        val unknown = normalized - declared
        if (unknown.isNotEmpty()) {
            throw ThirdPartyServiceException("授权包含服务未声明的权限：${unknown.joinToString()}")
        }
        normalized.forEach { ThirdPartyPermissionRegistry.requireKnown(it) }
        dao.updateGrantState(
            serviceId = serviceId,
            grantedPermissionsJson = AppJson.encodeToString(normalized.sorted()),
            enabled = true,
            needsReview = false,
            updatedAt = nowIso(),
        )
        return getService(serviceId) ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
    }

    suspend fun deleteService(serviceId: String) {
        if (dao.getService(serviceId) == null) return
        installer.deleteInstalledService(serviceId)
        dao.deleteService(serviceId)
    }

    private fun PreparedThirdPartyServicePackage.toPreview(updatedExisting: Boolean): ThirdPartyServiceImportPreview =
        ThirdPartyServiceImportPreview(
            token = token,
            manifest = manifest,
            sourceUrl = source.canonicalUrl,
            githubOwner = source.owner,
            githubRepo = source.repo,
            defaultBranch = defaultBranch,
            commitSha = commitSha,
            packageDigestSha256 = packageDigestSha256,
            packageBytes = packageBytes,
            packageFileCount = packageFileCount,
            updatedExisting = updatedExisting,
        )

    private fun InstalledThirdPartyServicePackage.toEntity(
        existing: ThirdPartyServiceEntity?,
        now: String,
        grantedPermissions: Set<String> = emptySet(),
        enabled: Boolean = false,
        needsReview: Boolean = true,
    ): ThirdPartyServiceEntity =
        ThirdPartyServiceEntity(
            serviceId = manifest.id,
            name = manifest.name,
            description = manifest.description,
            version = manifest.version,
            author = manifest.author,
            sourceUrl = source.canonicalUrl,
            githubOwner = source.owner,
            githubRepo = source.repo,
            defaultBranch = defaultBranch,
            commitSha = commitSha,
            packageDigestSha256 = packageDigestSha256,
            manifestJson = AppJson.encodeToString(manifest),
            grantedPermissionsJson = AppJson.encodeToString(grantedPermissions.sorted()),
            allowedOriginsJson = AppJson.encodeToString(manifest.allowedOrigins),
            installDir = installDir.absolutePath,
            entrypoint = manifest.entrypoint,
            icon = manifest.icon,
            enabled = enabled,
            needsReview = needsReview,
            installedAt = existing?.installedAt ?: now,
            updatedAt = now,
        )

    private fun ThirdPartyServiceEntity.toModel(): ThirdPartyService {
        val manifest = AppJson.decodeFromString<ThirdPartyServiceManifest>(manifestJson)
        val granted = runCatching {
            AppJson.decodeFromString<List<String>>(grantedPermissionsJson).toSet()
        }.getOrDefault(emptySet())
        val origins = runCatching {
            AppJson.decodeFromString<List<String>>(allowedOriginsJson)
        }.getOrDefault(manifest.allowedOrigins)
        return ThirdPartyService(
            serviceId = serviceId,
            manifest = manifest,
            sourceUrl = sourceUrl,
            githubOwner = githubOwner,
            githubRepo = githubRepo,
            defaultBranch = defaultBranch,
            commitSha = commitSha,
            packageDigestSha256 = packageDigestSha256,
            installDir = installDir,
            grantedPermissions = granted,
            allowedOrigins = origins,
            enabled = enabled,
            needsReview = needsReview,
            installedAt = installedAt,
            updatedAt = updatedAt,
        )
    }
}

private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

private fun InstalledBundledThirdPartyService.defaultGrantedPermissionsForInstalledManifest(): Set<String> {
    val declared = (
        packageInfo.manifest.permissions.required +
            packageInfo.manifest.permissions.optional
        ).toSet()
    val requested = defaultGrantedPermissions
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
    return requested.filter { it in declared }.toSet()
}
