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
    private val configurationStore: ThirdPartyConfigurationStore = InMemoryThirdPartyConfigurationStore(),
) {
    private val bundledInstallMutex = Mutex()
    private var bundledInstallChecked = false

    suspend fun ensureBundledServicesInstalled() {
        val provider = bundledProvider ?: return
        bundledInstallMutex.withLock {
            if (!bundledInstallChecked) {
                val existingServices = dao.listServices()
                val obsoleteBundledIds = existingServices
                    .filter { service ->
                        service.sourceUrl.startsWith(BundledThirdPartySourceUrlPrefix) &&
                            service.serviceId !in provider.bundledServiceIds
                    }
                    .map { it.serviceId }
                    .toSet()
                obsoleteBundledIds.forEach { serviceId ->
                    installer.deleteInstalledService(serviceId)
                    dao.deleteService(serviceId)
                }
                val existingById = existingServices
                    .filterNot { it.serviceId in obsoleteBundledIds }
                    .associateBy { it.serviceId }
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
        return runCatching {
            val existing = dao.getService(prepared.manifest.id)
            validateReplacement(prepared, existing)
            prepared.toPreview(existing != null)
        }.getOrElse { error ->
            installer.discardPreparedImport(prepared.token)
            throw error
        }
    }

    suspend fun prepareInstallFromCatalog(plugin: CatalogPlugin): ThirdPartyServiceImportPreview {
        val prepared = installer.prepareFromCatalog(plugin)
        return runCatching {
            val existing = dao.getService(prepared.manifest.id)
            validateReplacement(prepared, existing)
            prepared.toPreview(existing != null)
        }.getOrElse { error ->
            installer.discardPreparedImport(prepared.token)
            throw error
        }
    }

    suspend fun commitPreparedImport(token: String): ThirdPartyServiceInstallResult {
        val prepared = installer.preparedPackage(token)
            ?: throw ThirdPartyServiceException("插件预检包已失效，请重新导入")
        val existing = dao.getService(prepared.manifest.id)
        validateReplacement(prepared, existing)
        val previousManifest = existing?.let { runCatching { AppJson.decodeFromString<ThirdPartyServiceManifest>(it.manifestJson) }.getOrNull() }
        val previousConfiguration = configurationStore.load(prepared.manifest.id)
        val installed = installer.commitPreparedImport(token)
        val now = nowIso()
        val entity = installed.toEntity(
            existing = existing,
            now = now,
        )
        val mergedConfiguration = mergeThirdPartyConfiguration(
            previousManifest?.configuration.orEmpty(),
            installed.manifest.configuration,
            previousConfiguration,
        )
        configurationStore.save(installed.manifest.id, mergedConfiguration)
        try {
            dao.saveService(entity)
        } catch (error: Exception) {
            configurationStore.save(installed.manifest.id, previousConfiguration)
            throw error
        }
        installer.pruneInstalledVersions(installed.manifest.id, installed.commitSha)
        return ThirdPartyServiceInstallResult(
            service = entity.toModel(),
            updatedExisting = existing != null,
        )
    }

    suspend fun importFromGitHub(sourceUrl: String): ThirdPartyServiceInstallResult {
        val preview = prepareImportFromGitHub(sourceUrl)
        return commitPreparedImport(preview.token)
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
        val missingConfiguration = missingConfigurationKeys(service.manifest, configurationStore.load(serviceId))
        if (missingConfiguration.isNotEmpty()) {
            throw ThirdPartyServiceException("请先填写必填插件配置：${missingConfiguration.joinToString()}")
        }
        dao.updateGrantState(
            serviceId = serviceId,
            grantedPermissionsJson = AppJson.encodeToString(normalized.sorted()),
            enabled = true,
            needsReview = false,
            updatedAt = nowIso(),
        )
        return getService(serviceId) ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
    }

    suspend fun getConfiguration(serviceId: String): Map<String, String> {
        val service = getService(serviceId) ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
        val stored = configurationStore.load(serviceId)
        return service.manifest.configuration.associate { definition ->
            definition.key to (stored[definition.key] ?: definition.default.orEmpty())
        }
    }

    suspend fun saveConfiguration(serviceId: String, values: Map<String, String>): ThirdPartyService {
        val service = getService(serviceId) ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
        val definitions = service.manifest.configuration.associateBy { it.key }
        val unknown = values.keys - definitions.keys
        if (unknown.isNotEmpty()) throw ThirdPartyServiceException("包含插件未声明的配置键：${unknown.joinToString()}")
        val normalized = values.mapValues { (key, value) -> normalizeConfigurationValue(definitions.getValue(key), value) }
            .filterValues(String::isNotBlank)
        configurationStore.save(serviceId, normalized)
        val complete = missingConfigurationKeys(service.manifest, normalized).isEmpty()
        val requiredPermissions = service.manifest.permissions.required.toSet()
        dao.updateGrantState(
            serviceId = serviceId,
            grantedPermissionsJson = AppJson.encodeToString(service.grantedPermissions.sorted()),
            enabled = !service.needsReview && complete && service.grantedPermissions.containsAll(requiredPermissions),
            needsReview = service.needsReview,
            updatedAt = nowIso(),
        )
        return getService(serviceId) ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
    }

    suspend fun requireReview(serviceId: String) {
        val service = getService(serviceId) ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
        dao.updateGrantState(
            serviceId = serviceId,
            grantedPermissionsJson = AppJson.encodeToString(service.grantedPermissions.sorted()),
            enabled = false,
            needsReview = true,
            updatedAt = nowIso(),
        )
    }

    fun configurationValue(service: ThirdPartyService, key: String): String? {
        val definition = service.manifest.configuration.firstOrNull { it.key == key } ?: return null
        return configurationStore.load(service.serviceId)[key] ?: definition.default
    }

    fun missingConfigurationKeys(service: ThirdPartyService): List<String> =
        missingConfigurationKeys(service.manifest, configurationStore.load(service.serviceId))

    suspend fun deleteService(serviceId: String) {
        if (dao.getService(serviceId) == null) return
        val previousConfiguration = runCatching { configurationStore.load(serviceId) }.getOrNull()
        val stagedDeletion = installer.stageInstalledServiceDeletion(serviceId)
        try {
            configurationStore.remove(serviceId)
            dao.deleteService(serviceId)
        } catch (error: Exception) {
            previousConfiguration?.let { values ->
                runCatching { configurationStore.save(serviceId, values) }
                    .onFailure { error.addSuppressed(it) }
            }
            stagedDeletion?.let { deletion ->
                runCatching { installer.restoreStagedServiceDeletion(deletion) }
                    .onFailure { error.addSuppressed(it) }
            }
            throw error
        }
        stagedDeletion?.let(installer::commitStagedServiceDeletion)
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
            archiveSha256 = archiveSha256,
            platformVerified = platformVerified,
        )

    private fun validateReplacement(
        prepared: PreparedThirdPartyServicePackage,
        existing: ThirdPartyServiceEntity?,
    ) {
        if (prepared.manifest.id in bundledProvider?.bundledServiceIds.orEmpty()) {
            throw ThirdPartyServiceException("内置插件不能被大厅或开发者导入覆盖")
        }
        if (existing != null && !existing.sourceUrl.equals(prepared.source.canonicalUrl, ignoreCase = true)) {
            throw ThirdPartyServiceException("同一插件 ID 已由其他 GitHub 仓库提供，拒绝覆盖")
        }
    }

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
            enabled = enabled && missingConfigurationKeys(manifest, configurationStore.load(manifest.id)).isEmpty(),
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

private fun missingConfigurationKeys(
    manifest: ThirdPartyServiceManifest,
    storedValues: Map<String, String>,
): List<String> = manifest.configuration.filter { definition ->
    definition.required && (storedValues[definition.key] ?: definition.default).isNullOrBlank()
}.map { it.key }

private fun normalizeConfigurationValue(
    definition: ThirdPartyConfigurationDefinition,
    rawValue: String,
): String {
    val value = rawValue.trim()
    if (value.isBlank()) return ""
    return when (definition.type) {
        "boolean" -> value.lowercase().takeIf { it == "true" || it == "false" }
            ?: throw ThirdPartyServiceException("${definition.label} 必须是 true 或 false")
        "number" -> value.takeIf { it.toDoubleOrNull()?.isFinite() == true }
            ?: throw ThirdPartyServiceException("${definition.label} 必须是数字")
        "url" -> runCatching { java.net.URI(value) }.getOrNull()
            ?.takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() }
            ?.toString()
            ?: throw ThirdPartyServiceException("${definition.label} 必须是有效的 HTTP/HTTPS URL")
        "select" -> value.takeIf { it in definition.options }
            ?: throw ThirdPartyServiceException("${definition.label} 不在允许选项中")
        else -> value
    }
}

private const val BundledThirdPartySourceUrlPrefix = "asset://third-party-services/"

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
