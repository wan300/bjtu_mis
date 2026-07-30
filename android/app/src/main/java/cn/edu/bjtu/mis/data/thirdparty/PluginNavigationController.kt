package cn.edu.bjtu.mis.data.thirdparty

import android.webkit.WebResourceRequest
import java.net.URI
import java.util.Locale

class PluginNavigationController(
    private val service: ThirdPartyService,
    private val openExternal: (String) -> Unit,
) {
    fun shouldOverride(request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (
            ThirdPartyServiceSandbox.isServiceSandboxUrl(
                url,
                service.serviceId,
                service.publisherSubjectId,
            )
        ) {
            return false
        }
        val origin = origin(url) ?: return true
        return if (request.isForMainFrame) {
            if (
                "navigation.external@1" in service.grantedCapabilities &&
                request.hasGesture() &&
                origin in service.manifest.navigationOrigins
            ) {
                openExternal(url)
            }
            true
        } else {
            "remote.frame@1" !in service.grantedCapabilities ||
                origin !in service.manifest.frameOrigins
        }
    }

    fun openFromCapability(url: String): Boolean {
        val origin = origin(url) ?: return false
        if ("navigation.external@1" !in service.grantedCapabilities) return false
        if (origin !in service.manifest.navigationOrigins) return false
        openExternal(url)
        return true
    }

    private fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = uri.host?.lowercase(Locale.US)
        if (scheme != "https" || host.isNullOrBlank()) return@runCatching null
        val port = uri.port.takeIf { it != -1 && it != 443 }?.let { ":$it" }.orEmpty()
        "$scheme://$host$port"
    }.getOrNull()
}
