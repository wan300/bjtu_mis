package cn.edu.bjtu.mis.data.thirdparty

import java.io.File
import java.net.URI

object ThirdPartyManifestValidator {
    private val ServiceIdPattern = Regex("^[a-z][a-z0-9_\\-.]{2,63}$")
    private val AllowedOriginSchemes = setOf("http", "https")

    fun validate(manifest: ThirdPartyServiceManifest, packageRoot: File? = null): ThirdPartyServiceManifest {
        if (manifest.schemaVersion != THIRD_PARTY_SERVICE_SCHEMA_VERSION) {
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
        )
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
