package cn.edu.bjtu.mis.data.thirdparty

import java.net.URI

object ThirdPartyWebViewAccessPolicy {
    fun isTrustedBridgeMessage(
        isMainFrame: Boolean,
        sourceOrigin: String?,
        expectedOrigin: String,
    ): Boolean =
        isMainFrame && sourceOrigin?.let(::origin) == expectedOrigin

    fun isTrustedRuntimeUrl(
        url: String?,
        serviceId: String,
        publisherSubjectId: String,
    ): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return false
        return ThirdPartyServiceSandbox.isServiceSandboxUrl(raw, serviceId, publisherSubjectId)
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
