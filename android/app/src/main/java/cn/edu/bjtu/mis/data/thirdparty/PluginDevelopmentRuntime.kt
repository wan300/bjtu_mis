package cn.edu.bjtu.mis.data.thirdparty

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import cn.edu.bjtu.mis.BuildConfig

/**
 * The transport implementation and its externally reachable receiver exist only
 * in the debug source set. Release builds keep this inert interface/loader so the
 * shared host compiles, but the loader always returns null.
 */
interface PluginDevelopmentRuntime {
    fun attach(webView: WebView, stableOrigin: String)
    fun intercept(request: WebResourceRequest): WebResourceResponse?
    fun close(webView: WebView)
}

internal object PluginDevelopmentRuntimeLoader {
    private const val DEBUG_IMPLEMENTATION =
        "cn.edu.bjtu.mis.data.thirdparty.PluginDevelopmentTransport"

    fun create(
        context: Context,
        service: ThirdPartyService,
    ): PluginDevelopmentRuntime? {
        if (!BuildConfig.DEBUG) return null
        return runCatching {
            Class.forName(DEBUG_IMPLEMENTATION)
                .getConstructor(Context::class.java, ThirdPartyService::class.java)
                .newInstance(context.applicationContext, service) as PluginDevelopmentRuntime
        }.getOrNull()
    }
}
