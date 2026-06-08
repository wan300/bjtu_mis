package cn.edu.bjtu.mis.data.thirdparty

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

private const val SandboxParentDomain = "third-party.bjtu-mis.local"
private const val SandboxHashLength = 16

data class ThirdPartySandboxLocalResource(
    val file: File,
    val fallbackToEntrypoint: Boolean,
)

sealed class ThirdPartySandboxResourceResolution {
    data class Found(val resource: ThirdPartySandboxLocalResource) : ThirdPartySandboxResourceResolution()
    data object NotFound : ThirdPartySandboxResourceResolution()
    data object Blocked : ThirdPartySandboxResourceResolution()
    data object NotSandboxUrl : ThirdPartySandboxResourceResolution()
}

object ThirdPartyServiceSandbox {
    fun hostFor(serviceId: String, commitSha: String): String {
        val hash = sha256("$serviceId@$commitSha").take(SandboxHashLength)
        val prefixLimit = 63 - hash.length - 1
        val prefix = serviceId
            .lowercase(Locale.US)
            .map { char -> if (char in 'a'..'z' || char in '0'..'9') char else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(prefixLimit)
            .trim('-')
            .ifBlank { "service" }
        return "$prefix-$hash.$SandboxParentDomain"
    }

    fun originFor(serviceId: String, commitSha: String): String =
        "https://${hostFor(serviceId, commitSha)}"

    fun entrypointUrlFor(service: ThirdPartyService): String =
        originFor(service.serviceId, service.commitSha) + encodedAbsolutePath(service.manifest.entrypoint)

    fun isServiceSandboxUrl(url: String?, serviceId: String, commitSha: String): Boolean {
        val uri = parseUrl(url) ?: return false
        return uri.scheme?.lowercase(Locale.US) == "https" &&
            uri.host?.lowercase(Locale.US) == hostFor(serviceId, commitSha)
    }

    fun resolveLocalResource(
        url: String?,
        serviceId: String,
        commitSha: String,
        installDir: File,
        entrypoint: String,
    ): ThirdPartySandboxResourceResolution {
        val uri = parseUrl(url) ?: return ThirdPartySandboxResourceResolution.NotSandboxUrl
        if (!isServiceSandboxUrl(url, serviceId, commitSha)) {
            return ThirdPartySandboxResourceResolution.NotSandboxUrl
        }

        val installRoot = runCatching { installDir.canonicalFile }
            .getOrElse { return ThirdPartySandboxResourceResolution.Blocked }
        val relativePath = relativePathFor(uri, entrypoint)
            ?: return ThirdPartySandboxResourceResolution.Blocked
        val requestedFile = fileWithin(installRoot, relativePath)
            ?: return ThirdPartySandboxResourceResolution.Blocked
        if (requestedFile.isFile) {
            return ThirdPartySandboxResourceResolution.Found(
                ThirdPartySandboxLocalResource(requestedFile, fallbackToEntrypoint = false)
            )
        }

        if (!shouldFallbackToEntrypoint(relativePath)) {
            return ThirdPartySandboxResourceResolution.NotFound
        }

        val entrypointFile = fileWithin(installRoot, entrypoint)
            ?: return ThirdPartySandboxResourceResolution.Blocked
        return if (entrypointFile.isFile) {
            ThirdPartySandboxResourceResolution.Found(
                ThirdPartySandboxLocalResource(entrypointFile, fallbackToEntrypoint = true)
            )
        } else {
            ThirdPartySandboxResourceResolution.NotFound
        }
    }

    private fun relativePathFor(uri: URI, entrypoint: String): String? {
        val rawPath = uri.rawPath.orEmpty()
        val decodedPath = uri.path.orEmpty()
        if (rawPath.contains('\\') || decodedPath.contains('\\')) return null
        val normalizedEntrypoint = entrypoint.trim().replace('\\', '/').trimStart('/')
        if (normalizedEntrypoint.isBlank() || normalizedEntrypoint.split('/').any { it == ".." }) {
            return null
        }
        val relative = decodedPath.trimStart('/').takeIf { it.isNotBlank() } ?: normalizedEntrypoint
        val segments = relative.split('/').filter { it.isNotBlank() }
        if (segments.any { it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }

    private fun shouldFallbackToEntrypoint(relativePath: String): Boolean {
        val lastSegment = relativePath.substringAfterLast('/')
        return lastSegment.isBlank() || !lastSegment.contains('.')
    }

    private fun fileWithin(root: File, relativePath: String): File? =
        runCatching {
            File(root, relativePath).canonicalFile.takeIf { file ->
                file == root || file.path.startsWith(root.path + File.separator)
            }
        }.getOrNull()

    private fun parseUrl(url: String?): URI? {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { URI(raw) }.getOrNull()
    }

    private fun encodedAbsolutePath(relativePath: String): String =
        URI(null, null, "/${relativePath.trimStart('/')}", null).rawPath

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
