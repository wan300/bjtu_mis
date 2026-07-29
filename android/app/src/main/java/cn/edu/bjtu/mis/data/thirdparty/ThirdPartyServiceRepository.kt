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
    private val kvStore: ThirdPartyKvStore? = null,
    private val migrationRunner: ThirdPartyDataMigrationRunner? = null,
    private val webStorageCleaner: ThirdPartyWebStorageCleaner = NoOpThirdPartyWebStorageCleaner,
) {
    private val bundledInstallMutex = Mutex()
    private val cleanupMutex = Mutex()
    private var bundledInstallChecked = false

    suspend fun ensureBundledServicesInstalled() {
        retryPendingCleanup()
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
                    deleteService(serviceId)
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
            prepared.toPreview(existing)
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
            prepared.toPreview(existing)
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
        val previousService = existing?.toModel()
        val previousConfiguration = configurationStore.load(prepared.manifest.id)
        val namespace = ThirdPartyKvNamespace(
            publisherSubjectId = prepared.publisherSubjectId,
            pluginId = prepared.manifest.id,
        )
        var kvSnapshotCreated = false
        if (existing != null) {
            val requiresMigration = prepared.manifest.dataSchemaVersion > existing.dataSchemaVersion
            val store = kvStore
            if (requiresMigration && store == null) {
                throw ThirdPartyServiceException("当前客户端未配置安全 app.storage，不能执行数据 schema 迁移")
            }
            if (store != null) {
                store.snapshot(namespace)
                kvSnapshotCreated = true
                if (requiresMigration) {
                    val runner = migrationRunner
                        ?: throw ThirdPartyServiceException("当前客户端未配置安全数据迁移 runtime")
                    try {
                        store.beginShadow(namespace)
                        val committed = runner.migrate(prepared, namespace, store)
                        if (!committed) {
                            throw ThirdPartyServiceException("插件数据迁移未在 30 秒内显式提交")
                        }
                        store.commitShadow(namespace)
                    } catch (error: Exception) {
                        runCatching { store.discardShadow(namespace) }
                            .onFailure(error::addSuppressed)
                        runCatching { store.restoreSnapshot(namespace) }
                            .onFailure(error::addSuppressed)
                        throw ThirdPartyServiceException("插件数据迁移失败，已恢复旧 KV", error)
                    }
                }
            }
        }
        val installed = try {
            installer.commitPreparedImport(token)
        } catch (error: Exception) {
            if (kvSnapshotCreated) {
                runCatching { kvStore?.restoreSnapshot(namespace) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        }
        val now = nowIso()
        val entity = installed.toEntity(
            existing = existing,
            now = now,
            grantedPermissions = if (previousService == null) {
                emptySet()
            } else {
                val declared = (
                    installed.manifest.permissions.required +
                        installed.manifest.permissions.optional
                    ).toSet()
                (previousService.grantedPermissions intersect declared) +
                    (installed.manifest.permissions.required - previousManifest?.permissions?.required.orEmpty())
            },
            enabled = previousService?.enabled == true,
            needsReview = previousService?.needsReview ?: true,
        )
        val mergedConfiguration = mergeThirdPartyConfiguration(
            previousManifest?.configuration.orEmpty(),
            installed.manifest.configuration,
            previousConfiguration,
        )
        try {
            configurationStore.save(installed.manifest.id, mergedConfiguration)
            dao.saveService(entity)
        } catch (error: Exception) {
            runCatching {
                configurationStore.save(installed.manifest.id, previousConfiguration)
            }.onFailure(error::addSuppressed)
            if (kvSnapshotCreated) {
                runCatching { kvStore?.restoreSnapshot(namespace) }
                    .onFailure(error::addSuppressed)
            }
            throw error
        }
        installer.pruneInstalledVersions(
            installed.manifest.id,
            setOfNotNull(installed.commitSha, existing?.commitSha),
        )
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
        val existing = dao.getService(serviceId) ?: return
        val webStorageSubject = existing.publisherSubjectId
            .takeIf(String::isNotBlank)
            ?: existing.commitSha
        val tombstone = ThirdPartyCleanupTombstoneEntity(
            serviceId = serviceId,
            publisherSubjectId = existing.publisherSubjectId,
            webStorageOrigin = ThirdPartyServiceSandbox.originFor(serviceId, webStorageSubject),
            createdAt = nowIso(),
        )
        dao.deleteServiceAndScheduleCleanup(tombstone)
        cleanupTombstone(tombstone)
    }

    private suspend fun retryPendingCleanup() {
        cleanupMutex.withLock {
            dao.listCleanupTombstones().forEach { cleanupTombstone(it) }
        }
    }

    private suspend fun cleanupTombstone(tombstone: ThirdPartyCleanupTombstoneEntity) {
        var failure: Throwable? = null
        fun recordFailure(error: Throwable) {
            if (failure == null) {
                failure = error
            } else {
                failure?.addSuppressed(error)
            }
        }

        runCatching { configurationStore.remove(tombstone.serviceId) }
            .onFailure(::recordFailure)
        tombstone.publisherSubjectId.takeIf(String::isNotBlank)?.let { publisher ->
            runCatching {
                kvStore?.deleteNamespace(
                    ThirdPartyKvNamespace(publisher, tombstone.serviceId),
                )
            }.onFailure(::recordFailure)
        }
        runCatching { webStorageCleaner.deleteOrigin(tombstone.webStorageOrigin) }
            .onFailure(::recordFailure)
        runCatching { installer.cleanupDeletedServiceArtifacts(tombstone.serviceId) }
            .onFailure(::recordFailure)

        if (failure == null) {
            dao.deleteCleanupTombstone(tombstone.serviceId)
        }
    }

    suspend fun rollbackService(serviceId: String): ThirdPartyService {
        val current = dao.getService(serviceId)
            ?: throw ThirdPartyServiceException("第三方服务不存在：$serviceId")
        val snapshot = current.previousVersionJson?.let {
            runCatching { AppJson.decodeFromString<ThirdPartyInstalledVersionSnapshot>(it) }.getOrNull()
        } ?: throw ThirdPartyServiceException("插件没有可回滚的上一版本")
        if (!java.io.File(snapshot.installDir).isDirectory) {
            throw ThirdPartyServiceException("上一版本插件包已丢失，无法回滚")
        }
        if (snapshot.publisherSubjectId != current.publisherSubjectId) {
            throw ThirdPartyServiceException("上一版本 publisher subject 不匹配")
        }
        val namespace = ThirdPartyKvNamespace(current.publisherSubjectId, serviceId)
        val store = kvStore
        var currentKvShadowed = false
        if (store != null) {
            try {
                store.beginShadow(namespace)
                currentKvShadowed = true
                if (!store.restoreSnapshot(namespace)) {
                    throw ThirdPartyServiceException("上一版本 KV 快照不存在，无法原子回滚")
                }
            } catch (error: Exception) {
                if (currentKvShadowed) {
                    runCatching { store.discardShadow(namespace) }
                        .onFailure(error::addSuppressed)
                }
                throw error
            }
        }
        val now = nowIso()
        val restored = ThirdPartyServiceEntity(
            serviceId = serviceId,
            name = snapshot.manifest.name,
            description = snapshot.manifest.description,
            version = snapshot.version,
            author = snapshot.manifest.author,
            sourceUrl = snapshot.sourceUrl,
            githubOwner = snapshot.githubOwner,
            githubRepo = snapshot.githubRepo,
            defaultBranch = snapshot.defaultBranch,
            commitSha = snapshot.commitSha,
            packageDigestSha256 = snapshot.packageDigestSha256,
            manifestJson = AppJson.encodeToString(snapshot.manifest),
            grantedPermissionsJson = AppJson.encodeToString(snapshot.grantedPermissions.sorted()),
            allowedOriginsJson = AppJson.encodeToString(snapshot.manifest.remoteOrigins),
            publisherSubjectId = snapshot.publisherSubjectId,
            dataSchemaVersion = snapshot.dataSchemaVersion,
            compatibilityState = ThirdPartyCompatibilityState.Compatible.value,
            verificationLevel = snapshot.verificationLevel,
            previousVersionJson = null,
            installDir = snapshot.installDir,
            entrypoint = snapshot.manifest.entrypoint,
            icon = snapshot.manifest.icon,
            enabled = snapshot.enabled,
            needsReview = snapshot.needsReview,
            installedAt = current.installedAt,
            updatedAt = now,
        )
        try {
            dao.saveService(restored)
        } catch (error: Exception) {
            if (currentKvShadowed && store != null) {
                runCatching { store.commitShadow(namespace) }
                    .onFailure(error::addSuppressed)
            }
            throw ThirdPartyServiceException("无法原子回滚插件版本与数据", error)
        }
        if (currentKvShadowed && store != null) {
            store.discardShadow(namespace)
        }
        installer.pruneInstalledVersions(serviceId, setOf(snapshot.commitSha, current.commitSha))
        return restored.toModel()
    }

    private fun PreparedThirdPartyServicePackage.toPreview(
        existing: ThirdPartyServiceEntity?,
    ): ThirdPartyServiceImportPreview {
        val previous = existing?.toModel()
        val previousRequired = previous?.manifest?.permissions?.required.orEmpty().toSet()
        val previousOptional = previous?.manifest?.permissions?.optional.orEmpty().toSet()
        val previousOrigins = previous?.manifest?.remoteOrigins.orEmpty().toSet()
        val nextOrigins = manifest.remoteOrigins.toSet()
        return ThirdPartyServiceImportPreview(
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
            updatedExisting = existing != null,
            archiveSha256 = archiveSha256,
            platformVerified = platformVerified,
            iconSource = resolveLocalThirdPartyIconSource(stagingDir, manifest.icon),
            publisherSubjectId = publisherSubjectId,
            verificationLevel = verificationLevel,
            previousService = previous,
            addedRequiredPermissions = (manifest.permissions.required.toSet() - previousRequired).sorted(),
            addedOptionalPermissions = (manifest.permissions.optional.toSet() - previousOptional).sorted(),
            removedPermissions = (
                (previousRequired + previousOptional) -
                    (manifest.permissions.required + manifest.permissions.optional).toSet()
                ).sorted(),
            addedOrigins = (nextOrigins - previousOrigins).sorted(),
            removedOrigins = (previousOrigins - nextOrigins).sorted(),
        )
    }

    private fun validateReplacement(
        prepared: PreparedThirdPartyServicePackage,
        existing: ThirdPartyServiceEntity?,
    ) {
        if (prepared.manifest.id in bundledProvider?.bundledServiceIds.orEmpty()) {
            throw ThirdPartyServiceException("内置插件不能被大厅或开发者导入覆盖")
        }
        if (existing == null) return
        if (existing.compatibilityState != ThirdPartyCompatibilityState.Compatible.value) {
            throw ThirdPartyServiceException("Manifest v1/v2 legacy 插件不能原位更新；请使用只读救援入口处理旧数据")
        }
        if (existing.publisherSubjectId != prepared.publisherSubjectId) {
            throw ThirdPartyServiceException("检测到 publisher subject 变化，拒绝原位更新")
        }
        if (prepared.manifest.dataSchemaVersion < existing.dataSchemaVersion) {
            throw ThirdPartyServiceException("data_schema_version 不得降低")
        }
        if (
            prepared.manifest.dataSchemaVersion > existing.dataSchemaVersion &&
            prepared.manifest.migrationEntrypoint.isNullOrBlank()
        ) {
            throw ThirdPartyServiceException("提升 data_schema_version 必须提供 migration_entrypoint")
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
            allowedOriginsJson = AppJson.encodeToString(manifest.remoteOrigins),
            publisherSubjectId = publisherSubjectId,
            dataSchemaVersion = manifest.dataSchemaVersion,
            compatibilityState = ThirdPartyCompatibilityState.Compatible.value,
            verificationLevel = verificationLevel,
            previousVersionJson = existing?.toModel()?.toVersionSnapshot()?.let {
                AppJson.encodeToString(it)
            },
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
        }.getOrDefault(manifest.remoteOrigins)
        val previousVersion = previousVersionJson?.let { json ->
            runCatching { AppJson.decodeFromString<ThirdPartyInstalledVersionSnapshot>(json) }.getOrNull()
        }
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
            publisherSubjectId = publisherSubjectId,
            dataSchemaVersion = dataSchemaVersion,
            compatibilityState = compatibilityState,
            verificationLevel = verificationLevel,
            previousVersion = previousVersion,
        )
    }

    private fun ThirdPartyService.toVersionSnapshot(): ThirdPartyInstalledVersionSnapshot =
        ThirdPartyInstalledVersionSnapshot(
            version = manifest.version,
            commitSha = commitSha,
            packageDigestSha256 = packageDigestSha256,
            installDir = installDir,
            manifest = manifest,
            dataSchemaVersion = dataSchemaVersion,
            sourceUrl = sourceUrl,
            githubOwner = githubOwner,
            githubRepo = githubRepo,
            defaultBranch = defaultBranch,
            publisherSubjectId = publisherSubjectId,
            grantedPermissions = grantedPermissions,
            enabled = enabled,
            needsReview = needsReview,
            verificationLevel = verificationLevel,
        )
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
