package cn.edu.bjtu.mis.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.edu.bjtu.mis.data.repository.DocumentPreview
import cn.edu.bjtu.mis.data.repository.DocumentPreviewCookie

@Composable
internal fun DocumentPreviewScreen(
    title: String,
    subtitle: String?,
    preview: DocumentPreview,
    downloadBusy: Boolean,
    error: String?,
    onClose: () -> Unit,
    onDownload: () -> Unit,
) {
    var refreshNonce by remember(preview.url) { mutableStateOf(0) }
    var loading by remember(preview.url) { mutableStateOf(true) }
    var webError by remember(preview.url) { mutableStateOf<String?>(null) }

    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) {
                        Text("关闭")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        enabled = !downloadBusy,
                        onClick = {
                            webError = null
                            refreshNonce += 1
                        },
                    ) {
                        Text("刷新")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !downloadBusy, onClick = onDownload) {
                        Text(if (downloadBusy) "下载中" else "下载")
                    }
                }
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        val message = webError ?: error?.takeIf { it.isNotBlank() }
        if (!message.isNullOrBlank()) {
            Text(
                message,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { context ->
                WebView(context).apply {
                    configureDocumentPreviewWebView(
                        onLoadingChange = { loading = it },
                        onError = { webError = it },
                    )
                }
            },
            update = { webView ->
                val loadKey = "${preview.url}#$refreshNonce"
                if (webView.tag != loadKey) {
                    webView.tag = loadKey
                    webError = null
                    loading = true
                    if (preview.url.isHttpPreviewUrl()) {
                        webView.injectDocumentPreviewCookies(preview)
                        webView.loadUrl(preview.url)
                    } else {
                        loading = false
                        webError = "不支持的预览链接：${preview.url}"
                    }
                }
            },
        )
    }
}

private fun WebView.configureDocumentPreviewWebView(
    onLoadingChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.setSupportMultipleWindows(false)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            val scheme = uri.scheme?.lowercase()
            return if (scheme == "http" || scheme == "https") {
                false
            } else {
                onError("应用内预览不支持该链接：$uri")
                true
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            onLoadingChange(true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            onLoadingChange(false)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                onLoadingChange(false)
                onError(error?.description?.toString()?.takeIf { it.isNotBlank() } ?: "预览加载失败")
            }
        }
    }
}

private fun WebView.injectDocumentPreviewCookies(preview: DocumentPreview) {
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        cookieManager.setAcceptThirdPartyCookies(this, true)
    }
    preview.cookies.forEach { cookie ->
        val targetUrl = cookie.toWebViewCookieUrl(preview.url)
        val value = cookie.toWebViewCookieString()
        if (targetUrl.isNotBlank() && value.isNotBlank()) {
            cookieManager.setCookie(targetUrl, value)
        }
    }
    cookieManager.flush()
}

private fun DocumentPreviewCookie.toWebViewCookieUrl(fallbackUrl: String): String {
    val host = domain.trim().trimStart('.')
    if (host.isBlank()) return fallbackUrl
    val fallbackScheme = Uri.parse(fallbackUrl).scheme?.lowercase()
    val scheme = when {
        secure -> "https"
        fallbackScheme == "http" || fallbackScheme == "https" -> fallbackScheme
        else -> "https"
    }
    val safePath = path.takeIf { it.startsWith("/") } ?: "/"
    return "$scheme://$host$safePath"
}

private fun DocumentPreviewCookie.toWebViewCookieString(): String =
    buildString {
        append(name)
        append('=')
        append(value)
        if (!hostOnly && domain.isNotBlank()) {
            append("; Domain=")
            append(domain.trim())
        }
        append("; Path=")
        append(path.ifBlank { "/" })
        if (secure) append("; Secure")
        if (httpOnly) append("; HttpOnly")
    }

private fun String.isHttpPreviewUrl(): Boolean {
    val scheme = Uri.parse(this).scheme?.lowercase()
    return scheme == "http" || scheme == "https"
}
