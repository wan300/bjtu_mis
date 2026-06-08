package cn.edu.bjtu.mis.data.thirdparty

import java.io.File
import java.net.URI

object ThirdPartyWebViewAccessPolicy {
    fun isTrustedRuntimeUrl(
        url: String?,
        serviceId: String,
        commitSha: String,
        allowedOrigins: Collection<String>,
    ): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return false
        return ThirdPartyServiceSandbox.isServiceSandboxUrl(raw, serviceId, commitSha) ||
            origin(raw) in allowedOrigins
    }

    fun isTrustedUrl(url: String?, installDir: File, allowedOrigins: Collection<String>): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return false
        val uri = runCatching { URI(raw) }.getOrElse { return false }
        return when (uri.scheme?.lowercase()) {
            "file" -> runCatching {
                val installRoot = installDir.canonicalFile
                val file = File(uri).canonicalFile
                file == installRoot || file.path.startsWith(installRoot.path + File.separator)
            }.getOrDefault(false)
            "http", "https" -> origin(raw) in allowedOrigins
            else -> false
        }
    }

    fun origin(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrElse { return null }
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme !in setOf("http", "https")) return null
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank()) return null
        val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
        return "$scheme://$host$port"
    }
}
