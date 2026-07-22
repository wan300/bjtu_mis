package cn.edu.bjtu.mis.data.thirdparty

import java.io.File
import java.net.URI

object ThirdPartyManifestValidator {
    private val ServiceIdPattern = Regex("^[a-z][a-z0-9_\\-.]{2,63}$")
    private val ConfigurationKeyPattern = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    private val SemVerPattern = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
    private val AllowedOriginSchemes = setOf("http", "https")
    private val MarketplaceCategories = setOf("academic", "campus", "information", "productivity", "assistant", "other")
    private val ConfigurationTypes = setOf("text", "secret", "url", "number", "boolean", "select")

    fun validate(manifest: ThirdPartyServiceManifest, packageRoot: File? = null): ThirdPartyServiceManifest {
        if (manifest.schemaVersion !in THIRD_PARTY_SERVICE_SCHEMA_VERSION_V1..THIRD_PARTY_SERVICE_SCHEMA_VERSION) {
            throw ThirdPartyServiceException("Unsupported third-party service schema_version: ${manifest.schemaVersion}")
        }
        if (!ServiceIdPattern.matches(manifest.id)) {
            throw ThirdPartyServiceException("Third-party service id must be 3-64 chars and contain only lowercase letters, digits, dot, underscore, or hyphen")
        }
        requireText(manifest.name, "name")
        requireText(manifest.description, "description")
        requireText(manifest.version, "version")
        requireText(manifest.author, "author")
        validateAssetPath(manifest.entrypoint, "entrypoint")
        validateAssetPath(manifest.icon, "icon")

        val required = manifest.permissions.required.map { it.trim() }.filter { it.isNotBlank() }
        val optional = manifest.permissions.optional.map { it.trim() }.filter { it.isNotBlank() }
        val duplicates = required.toSet().intersect(optional.toSet())
        if (duplicates.isNotEmpty()) {
            throw ThirdPartyServiceException("Permissions cannot appear in both required and optional: ${duplicates.joinToString()}")
        }
        (required + optional).forEach { ThirdPartyPermissionRegistry.requireKnown(it) }

        val normalizedOrigins = manifest.allowedOrigins.map { normalizeOrigin(it) }
        if (normalizedOrigins.size != normalizedOrigins.toSet().size) {
            throw ThirdPartyServiceException("allowed_origins contains duplicates")
        }

        if (packageRoot != null) {
            val dist = File(packageRoot, "dist").canonicalFile
            if (!dist.isDirectory) throw ThirdPartyServiceException("Third-party service package is missing dist/ directory")
            if (!File(dist, manifest.entrypoint).canonicalFile.isFile) {
                throw ThirdPartyServiceException("Third-party service entrypoint does not exist: ${manifest.entrypoint}")
            }
            if (!File(dist, manifest.icon).canonicalFile.isFile) {
                throw ThirdPartyServiceException("Third-party service icon does not exist: ${manifest.icon}")
            }
        }

        val marketplace = validateMarketplace(manifest)
        val configuration = validateConfiguration(manifest, required)

        return manifest.copy(
            id = manifest.id.trim(),
            name = manifest.name.trim(),
            description = manifest.description.trim(),
            version = manifest.version.trim(),
            entrypoint = normalizeAssetPath(manifest.entrypoint),
            icon = normalizeAssetPath(manifest.icon),
            author = manifest.author.trim(),
            permissions = ThirdPartyServicePermissionDeclaration(required = required, optional = optional),
            allowedOrigins = normalizedOrigins,
            marketplace = marketplace,
            configuration = configuration,
        )
    }

    private fun validateMarketplace(manifest: ThirdPartyServiceManifest): ThirdPartyMarketplaceMetadata? {
        if (manifest.schemaVersion == THIRD_PARTY_SERVICE_SCHEMA_VERSION_V1) {
            if (manifest.marketplace != null || manifest.configuration.isNotEmpty()) {
                throw ThirdPartyServiceException("schema_version 1 cannot declare marketplace or configuration")
            }
            return null
        }
        if (!SemVerPattern.matches(manifest.version.trim())) {
            throw ThirdPartyServiceException("schema_version 2 version must use semantic versioning")
        }
        val marketplace = manifest.marketplace
            ?: throw ThirdPartyServiceException("schema_version 2 requires marketplace metadata")
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
        val license = marketplace.license?.trim()?.takeIf { it.isNotEmpty() }
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

    fun normalizeOrigin(value: String): String {
        val raw = value.trim()
        if (raw.isBlank()) throw ThirdPartyServiceException("allowed_origins cannot contain blank values")
        val uri = runCatching { URI(raw) }.getOrElse {
            throw ThirdPartyServiceException("allowed_origins contains invalid URL: $raw")
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme !in AllowedOriginSchemes) {
            throw ThirdPartyServiceException("allowed_origins only supports HTTP/HTTPS origin: $raw")
        }
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank() || uri.rawUserInfo != null || !uri.rawQuery.isNullOrBlank() || !uri.rawFragment.isNullOrBlank()) {
            throw ThirdPartyServiceException("allowed_origins must be an HTTP/HTTPS origin: $raw")
        }
        val path = uri.rawPath.orEmpty()
        if (path.isNotBlank() && path != "/") {
            throw ThirdPartyServiceException("allowed_origins cannot include path: $raw")
        }
        val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
        return "$scheme://$host$port"
    }

    private fun requireText(value: String, fieldName: String) {
        if (value.isBlank()) throw ThirdPartyServiceException("$fieldName cannot be blank")
    }

    private fun normalizeAssetPath(value: String): String =
        value.trim().replace('\\', '/').split('/').filter { it.isNotBlank() }.joinToString("/")
}
