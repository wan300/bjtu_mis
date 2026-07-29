package cn.edu.bjtu.mis.data.thirdparty

import cn.edu.bjtu.mis.data.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.Locale

object ThirdPartyManifestValidator {
    private val ServiceIdPattern = Regex("^[a-z][a-z0-9_\\-.]{2,63}$")
    private val ConfigurationKeyPattern = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    private val SemVerPattern = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    private val MarketplaceCategories = setOf("academic", "campus", "information", "productivity", "assistant", "other")
    private val ConfigurationTypes = setOf("text", "secret", "url", "number", "boolean", "select")
    private val CampusHosts = setOf(
        "123.121.147.7",
        "cas.bjtu.edu.cn",
        "mis.bjtu.edu.cn",
        "aa.bjtu.edu.cn",
        "mail.bjtu.edu.cn",
        "zhixing.bjtu.edu.cn",
        "job.bjtu.edu.cn",
        "bksycenter.bjtu.edu.cn",
    )
    private val ManifestFields = setOf(
        "schema_version",
        "id",
        "name",
        "description",
        "version",
        "runtime_version",
        "min_runtime_version",
        "required_capabilities",
        "optional_capabilities",
        "data_schema_version",
        "migration_entrypoint",
        "entrypoint",
        "icon",
        "author",
        "permissions",
        "connect_origins",
        "media_origins",
        "frame_origins",
        "navigation_origins",
        "bridge_origins",
        "marketplace",
        "configuration",
    )
    private val RequiredManifestFields = ManifestFields - "migration_entrypoint"

    fun decodeAndValidate(
        rawJson: String,
        packageRoot: File? = null,
        allowDevelopmentOrigins: Boolean = false,
    ): ThirdPartyServiceManifest {
        val rawManifest = runCatching { AppJson.parseToJsonElement(rawJson).jsonObject }
            .getOrElse { throw ThirdPartyServiceException("bjtu-service.json 不是有效 JSON object", it) }
        val fields = rawManifest.keys
        val unknown = fields - ManifestFields
        if (unknown.isNotEmpty()) {
            throw ThirdPartyServiceException("Manifest v3 包含未知字段：${unknown.sorted().joinToString()}")
        }
        val missing = RequiredManifestFields - fields
        if (missing.isNotEmpty()) {
            throw ThirdPartyServiceException("Manifest v3 缺少字段：${missing.sorted().joinToString()}")
        }
        validateRawObjectShape(
            rawManifest["permissions"],
            "permissions",
            allowed = setOf("required", "optional"),
            required = setOf("required", "optional"),
        )
        validateRawObjectShape(
            rawManifest["marketplace"],
            "marketplace",
            allowed = setOf("category", "tags", "license"),
            required = setOf("category", "tags"),
        )
        (rawManifest["configuration"] as? JsonArray)?.forEachIndexed { index, value ->
            validateRawObjectShape(
                value,
                "configuration[$index]",
                allowed = setOf(
                    "key",
                    "label",
                    "description",
                    "type",
                    "required",
                    "default",
                    "options",
                ),
                required = setOf("key", "label", "description", "type", "required"),
            )
        }
        if ("migration_entrypoint" in rawManifest && rawManifest["migration_entrypoint"] is JsonNull) {
            throw ThirdPartyServiceException("migration_entrypoint must be omitted or contain a local asset path")
        }
        return validate(
            AppJson.decodeFromString<ThirdPartyServiceManifest>(rawJson),
            packageRoot,
            allowDevelopmentOrigins,
        )
    }

    fun validate(
        manifest: ThirdPartyServiceManifest,
        packageRoot: File? = null,
        allowDevelopmentOrigins: Boolean = false,
    ): ThirdPartyServiceManifest {
        if (manifest.schemaVersion != THIRD_PARTY_SERVICE_SCHEMA_VERSION) {
            throw ThirdPartyServiceException("Unsupported third-party service schema_version: ${manifest.schemaVersion}")
        }
        if (!ServiceIdPattern.matches(manifest.id)) {
            throw ThirdPartyServiceException("Third-party service id must be 3-64 chars and contain only lowercase letters, digits, dot, underscore, or hyphen")
        }
        requireText(manifest.name, "name", 80)
        requireText(manifest.description, "description", 400)
        requireText(manifest.version, "version", 40)
        requireText(manifest.author, "author", 120)
        if (!SemVerPattern.matches(manifest.version.trim())) {
            throw ThirdPartyServiceException("schema_version 3 version must use semantic versioning")
        }
        if (manifest.runtimeVersion < 1 || manifest.minRuntimeVersion < 1) {
            throw ThirdPartyServiceException("runtime_version and min_runtime_version must be positive integers")
        }
        if (manifest.minRuntimeVersion > manifest.runtimeVersion) {
            throw ThirdPartyServiceException("min_runtime_version cannot exceed runtime_version")
        }
        if (manifest.minRuntimeVersion > THIRD_PARTY_RUNTIME_VERSION) {
            throw ThirdPartyServiceException(
                "Plugin requires runtime ${manifest.minRuntimeVersion}, host provides $THIRD_PARTY_RUNTIME_VERSION",
            )
        }
        if (manifest.dataSchemaVersion < 1) {
            throw ThirdPartyServiceException("data_schema_version must be a positive integer")
        }
        val requiredCapabilities = normalizeCapabilities(manifest.requiredCapabilities, "required_capabilities")
        val optionalCapabilities = normalizeCapabilities(manifest.optionalCapabilities, "optional_capabilities")
        val duplicatedCapabilities = requiredCapabilities.toSet().intersect(optionalCapabilities.toSet())
        if (duplicatedCapabilities.isNotEmpty()) {
            throw ThirdPartyServiceException(
                "Capabilities cannot appear in both required and optional: ${duplicatedCapabilities.joinToString()}",
            )
        }
        val missingRequiredCapabilities = requiredCapabilities - THIRD_PARTY_RUNTIME_CAPABILITIES
        if (missingRequiredCapabilities.isNotEmpty()) {
            throw ThirdPartyServiceException(
                "Host does not support required capabilities: ${missingRequiredCapabilities.joinToString()}",
            )
        }
        val normalizedEntrypoint = validateAssetPath(manifest.entrypoint, "entrypoint")
        val normalizedIcon = validateAssetPath(manifest.icon, "icon")
        val normalizedMigrationEntrypoint = manifest.migrationEntrypoint
            ?.takeIf { it.isNotBlank() }
            ?.let { validateAssetPath(it, "migration_entrypoint") }
        if (!isSupportedThirdPartyIconPath(normalizedIcon)) {
            throw ThirdPartyServiceException(
                "Third-party service icon must be SVG, PNG, WebP, JPG, or JPEG",
            )
        }

        val required = normalizePermissions(manifest.permissions.required, "permissions.required")
        val optional = normalizePermissions(manifest.permissions.optional, "permissions.optional")
        val duplicates = required.toSet().intersect(optional.toSet())
        if (duplicates.isNotEmpty()) {
            throw ThirdPartyServiceException("Permissions cannot appear in both required and optional: ${duplicates.joinToString()}")
        }
        (required + optional).forEach { ThirdPartyPermissionRegistry.requireKnown(it) }

        val connectOrigins = normalizeOrigins(
            manifest.connectOrigins,
            "connect_origins",
            allowDevelopmentOrigins,
            blockCampusHosts = true,
        )
        val mediaOrigins = normalizeOrigins(
            manifest.mediaOrigins,
            "media_origins",
            allowDevelopmentOrigins,
            blockCampusHosts = true,
        )
        val frameOrigins = normalizeOrigins(
            manifest.frameOrigins,
            "frame_origins",
            allowDevelopmentOrigins,
            blockCampusHosts = true,
        )
        val navigationOrigins = normalizeOrigins(
            manifest.navigationOrigins,
            "navigation_origins",
            allowDevelopmentOrigins,
            blockCampusHosts = false,
        )
        if (manifest.bridgeOrigins != listOf("self")) {
            throw ThirdPartyServiceException("bridge_origins must be exactly [\"self\"]")
        }
        if (frameOrigins.isNotEmpty() && "remote.frame.v1" !in requiredCapabilities) {
            throw ThirdPartyServiceException("frame_origins requires remote.frame.v1 in required_capabilities")
        }

        if (packageRoot != null) {
            val dist = File(packageRoot, "dist").canonicalFile
            if (!dist.isDirectory) throw ThirdPartyServiceException("Third-party service package is missing dist/ directory")
            val entrypointFile = resolveAssetFile(dist, normalizedEntrypoint, "entrypoint")
            if (!entrypointFile.isFile) {
                throw ThirdPartyServiceException("Third-party service entrypoint does not exist: ${manifest.entrypoint}")
            }
            val iconFile = resolveAssetFile(dist, normalizedIcon, "icon")
            if (!iconFile.isFile) {
                throw ThirdPartyServiceException("Third-party service icon does not exist: ${manifest.icon}")
            }
            if (iconFile.length() !in 1..MAX_THIRD_PARTY_ICON_BYTES) {
                throw ThirdPartyServiceException("Third-party service icon must be 1 byte to 1 MiB")
            }
            normalizedMigrationEntrypoint?.let { migrationEntrypoint ->
                val migrationFile = resolveAssetFile(dist, migrationEntrypoint, "migration_entrypoint")
                if (!migrationFile.isFile) {
                    throw ThirdPartyServiceException(
                        "Third-party service migration_entrypoint does not exist: $migrationEntrypoint",
                    )
                }
            }
        }

        val marketplace = validateMarketplace(manifest)
        val configuration = validateConfiguration(manifest, required)

        return manifest.copy(
            id = manifest.id.trim(),
            name = manifest.name.trim(),
            description = manifest.description.trim(),
            version = manifest.version.trim(),
            requiredCapabilities = requiredCapabilities,
            optionalCapabilities = optionalCapabilities,
            entrypoint = normalizedEntrypoint,
            migrationEntrypoint = normalizedMigrationEntrypoint,
            icon = normalizedIcon,
            author = manifest.author.trim(),
            permissions = ThirdPartyServicePermissionDeclaration(required = required, optional = optional),
            connectOrigins = connectOrigins,
            mediaOrigins = mediaOrigins,
            frameOrigins = frameOrigins,
            navigationOrigins = navigationOrigins,
            bridgeOrigins = listOf("self"),
            marketplace = marketplace,
            configuration = configuration,
        )
    }

    private fun validateMarketplace(manifest: ThirdPartyServiceManifest): ThirdPartyMarketplaceMetadata? {
        val marketplace = manifest.marketplace
            ?: throw ThirdPartyServiceException("schema_version 3 requires marketplace metadata")
        val category = marketplace.category.trim().lowercase()
        if (category !in MarketplaceCategories) {
            throw ThirdPartyServiceException("Unknown marketplace category: ${marketplace.category}")
        }
        if (marketplace.tags.size > 5) {
            throw ThirdPartyServiceException("marketplace.tags cannot contain more than 5 entries")
        }
        val tags = marketplace.tags.map { it.trim() }
        if (tags.any { it.length !in 1..20 }) {
            throw ThirdPartyServiceException("marketplace tags must be 1-20 characters")
        }
        if (tags.map { it.lowercase() }.size != tags.map { it.lowercase() }.toSet().size) {
            throw ThirdPartyServiceException("marketplace.tags contains duplicates")
        }
        if (marketplace.license != null && marketplace.license.isBlank()) {
            throw ThirdPartyServiceException("marketplace.license must not be blank when provided")
        }
        val license = marketplace.license?.trim()
        if (license != null && license.length > 80) {
            throw ThirdPartyServiceException("marketplace.license cannot exceed 80 characters")
        }
        return ThirdPartyMarketplaceMetadata(category = category, tags = tags, license = license)
    }

    private fun validateConfiguration(
        manifest: ThirdPartyServiceManifest,
        requiredPermissions: List<String>,
    ): List<ThirdPartyConfigurationDefinition> {
        if (manifest.configuration.size > 32) {
            throw ThirdPartyServiceException("configuration cannot contain more than 32 entries")
        }
        if (manifest.configuration.isNotEmpty() && "app.configuration.read" !in requiredPermissions) {
            throw ThirdPartyServiceException("configuration requires app.configuration.read in permissions.required")
        }
        val normalized = manifest.configuration.map { definition ->
            val key = definition.key.trim()
            if (!ConfigurationKeyPattern.matches(key)) {
                throw ThirdPartyServiceException("Invalid configuration key: ${definition.key}")
            }
            val label = definition.label.trim()
            val description = definition.description.trim()
            if (label.isEmpty() || label.length > 80) {
                throw ThirdPartyServiceException("configuration label must be 1-80 characters: $key")
            }
            if (description.length > 240) {
                throw ThirdPartyServiceException("configuration description cannot exceed 240 characters: $key")
            }
            val type = definition.type.trim().lowercase()
            if (type !in ConfigurationTypes) {
                throw ThirdPartyServiceException("Unknown configuration type for $key: ${definition.type}")
            }
            val options = definition.options.map { it.trim() }
            if (type == "select") {
                if (options.isEmpty() || options.size > 20 || options.any { it.isEmpty() } || options.size != options.toSet().size) {
                    throw ThirdPartyServiceException("select configuration requires 1-20 unique non-empty options: $key")
                }
            } else if (options.isNotEmpty()) {
                throw ThirdPartyServiceException("configuration options are only valid for select: $key")
            }
            val default = definition.default
            if (type == "secret" && default != null) {
                throw ThirdPartyServiceException("secret configuration cannot declare a default: $key")
            }
            validateConfigurationDefault(key, type, default, options)
            definition.copy(
                key = key,
                label = label,
                description = description,
                type = type,
                default = default?.trim(),
                options = options,
            )
        }
        val duplicateKeys = normalized.groupingBy { it.key }.eachCount().filterValues { it > 1 }.keys
        if (duplicateKeys.isNotEmpty()) {
            throw ThirdPartyServiceException("configuration contains duplicate keys: ${duplicateKeys.joinToString()}")
        }
        return normalized
    }

    private fun validateConfigurationDefault(key: String, type: String, default: String?, options: List<String>) {
        val value = default?.trim() ?: return
        when (type) {
            "number" -> if (value.toDoubleOrNull() == null) {
                throw ThirdPartyServiceException("configuration default must be a number: $key")
            }
            "boolean" -> if (value != "true" && value != "false") {
                throw ThirdPartyServiceException("configuration default must be true or false: $key")
            }
            "url" -> {
                val uri = runCatching { URI(value) }.getOrNull()
                if (uri?.scheme?.lowercase() !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
                    throw ThirdPartyServiceException("configuration default must be an HTTP/HTTPS URL: $key")
                }
            }
            "select" -> if (value !in options) {
                throw ThirdPartyServiceException("configuration default must be one of its options: $key")
            }
        }
    }

    fun validateAssetPath(value: String, fieldName: String): String = normalizeAssetPath(value).also {
        val raw = value.trim()
        if (it.isBlank()) throw ThirdPartyServiceException("$fieldName cannot be blank")
        if (raw.startsWith("/") || raw.contains("\\") || raw.contains(":") || it.split('/').any { part -> part == ".." }) {
            throw ThirdPartyServiceException("$fieldName must be a relative path inside dist/")
        }
    }

    private fun resolveAssetFile(
        dist: File,
        relativePath: String,
        fieldName: String,
    ): File {
        val file = File(dist, relativePath).canonicalFile
        if (!file.toPath().startsWith(dist.toPath()) || file == dist) {
            throw ThirdPartyServiceException("$fieldName must stay inside dist/")
        }
        return file
    }

    fun normalizeOrigin(
        value: String,
        fieldName: String = "origin",
        allowDevelopmentOrigins: Boolean = false,
        blockCampusHosts: Boolean = true,
    ): String {
        val raw = value.trim()
        if (raw.isBlank()) throw ThirdPartyServiceException("$fieldName cannot contain blank values")
        val uri = runCatching { URI(raw) }.getOrElse {
            throw ThirdPartyServiceException("$fieldName contains invalid URL: $raw")
        }
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        val localhostDevelopmentOrigin = scheme == "http" &&
            allowDevelopmentOrigins &&
            host in setOf("localhost", "127.0.0.1", "::1")
        if (scheme != "https" && !localhostDevelopmentOrigin) {
            throw ThirdPartyServiceException("$fieldName only supports HTTPS origins")
        }
        if (host.isBlank() || uri.rawUserInfo != null || !uri.rawQuery.isNullOrBlank() || !uri.rawFragment.isNullOrBlank()) {
            throw ThirdPartyServiceException("$fieldName must contain origins without userinfo, query, or fragment: $raw")
        }
        val path = uri.rawPath.orEmpty()
        if (path.isNotBlank() && path != "/") {
            throw ThirdPartyServiceException("$fieldName cannot include path: $raw")
        }
        if (!localhostDevelopmentOrigin && isPrivateOrLocalHost(host)) {
            throw ThirdPartyServiceException("$fieldName cannot include private, loopback, link-local, or local hosts: $host")
        }
        if (blockCampusHosts && isCampusHost(host)) {
            throw ThirdPartyServiceException("$fieldName cannot include campus service hosts; use campus.request: $host")
        }
        val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
        return "$scheme://$host$port"
    }

    private fun normalizeCapabilities(values: List<String>, fieldName: String): List<String> {
        val normalized = values.map(String::trim)
        if (normalized.any(String::isBlank)) {
            throw ThirdPartyServiceException("$fieldName cannot contain blank values")
        }
        if (normalized.size != normalized.toSet().size) {
            throw ThirdPartyServiceException("$fieldName contains duplicates")
        }
        val unknown = normalized.toSet() - THIRD_PARTY_RUNTIME_CAPABILITIES
        if (unknown.isNotEmpty()) {
            throw ThirdPartyServiceException("$fieldName contains unknown capabilities: ${unknown.joinToString()}")
        }
        return normalized
    }

    private fun normalizePermissions(values: List<String>, fieldName: String): List<String> {
        val normalized = values.map(String::trim)
        if (normalized.any(String::isBlank)) {
            throw ThirdPartyServiceException("$fieldName cannot contain blank values")
        }
        if (normalized.size != normalized.toSet().size) {
            throw ThirdPartyServiceException("$fieldName contains duplicates")
        }
        return normalized
    }

    private fun normalizeOrigins(
        values: List<String>,
        fieldName: String,
        allowDevelopmentOrigins: Boolean,
        blockCampusHosts: Boolean,
    ): List<String> {
        val normalized = values.map { value ->
            normalizeOrigin(
                value,
                fieldName = fieldName,
                allowDevelopmentOrigins = allowDevelopmentOrigins,
                blockCampusHosts = blockCampusHosts,
            )
        }
        if (normalized.size != normalized.toSet().size) {
            throw ThirdPartyServiceException("$fieldName contains duplicates")
        }
        return normalized
    }

    private fun isCampusHost(host: String): Boolean =
        host in CampusHosts || host.endsWith(".bjtu.edu.cn")

    private fun isPrivateOrLocalHost(host: String): Boolean {
        val normalized = host.removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        if (
            normalized == "localhost" ||
            normalized.endsWith(".localhost") ||
            normalized.endsWith(".local") ||
            normalized.endsWith(".internal") ||
            normalized == "::" ||
            normalized == "::1"
        ) {
            return true
        }
        val firstIpv6Hextet = normalized
            .takeIf { ':' in it && !it.startsWith("::") }
            ?.substringBefore(':')
            ?.toIntOrNull(16)
        if (
            firstIpv6Hextet != null &&
            (
                (firstIpv6Hextet and 0xfe00) == 0xfc00 ||
                    (firstIpv6Hextet and 0xffc0) == 0xfe80 ||
                    (firstIpv6Hextet and 0xff00) == 0xff00
                )
        ) {
            return true
        }
        val address = runCatching {
            if (normalized.matches(Regex("^[0-9a-fA-F:.]+$"))) {
                InetAddress.getByName(normalized)
            } else {
                null
            }
        }.getOrNull() ?: return false
        return address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
    }

    private fun requireText(value: String, fieldName: String, maxLength: Int) {
        val normalized = value.trim()
        if (normalized.isBlank()) throw ThirdPartyServiceException("$fieldName cannot be blank")
        if (normalized.length > maxLength) {
            throw ThirdPartyServiceException("$fieldName cannot exceed $maxLength characters")
        }
    }

    private fun validateRawObjectShape(
        value: kotlinx.serialization.json.JsonElement?,
        fieldName: String,
        allowed: Set<String>,
        required: Set<String>,
    ) {
        val objectValue = value as? JsonObject ?: return
        val unknown = objectValue.keys - allowed
        if (unknown.isNotEmpty()) {
            throw ThirdPartyServiceException(
                "$fieldName contains unknown fields: ${unknown.sorted().joinToString()}",
            )
        }
        val missing = required - objectValue.keys
        if (missing.isNotEmpty()) {
            throw ThirdPartyServiceException(
                "$fieldName is missing fields: ${missing.sorted().joinToString()}",
            )
        }
    }

    private fun normalizeAssetPath(value: String): String =
        value.trim().replace('\\', '/').split('/').filter { it.isNotBlank() }.joinToString("/")
}
