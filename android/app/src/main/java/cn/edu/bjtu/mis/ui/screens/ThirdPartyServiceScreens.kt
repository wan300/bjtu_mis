package cn.edu.bjtu.mis.ui.screens

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyPermissionRegistry
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartySandboxResourceResolution
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartySensitiveActionConfirmer
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyService
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceApiRegistry
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceImportPreview
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceRepository
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceSandbox
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyWebViewAccessPolicy
import cn.edu.bjtu.mis.data.thirdparty.thirdPartyServiceRoute
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.SectionTitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request as OkHttpRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

private const val MaxThirdPartyHttpResponseBytes = 5L * 1024L * 1024L
private const val ThirdPartyBridgeLogTag = "ThirdPartyBridge"
private val AUTH_EXPIRED_SERVICE_CODES = setOf(10001, 10002)
private val ThirdPartyBridgeHttpClient = OkHttpClient()

@Composable
fun ThirdPartyServicesScreen(
    repository: ThirdPartyServiceRepository,
    onOpenService: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var servicesState by remember { mutableStateOf<LoadState<List<ThirdPartyService>>>(LoadState.Loading) }
    var githubUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingPreview by remember { mutableStateOf<ThirdPartyServiceImportPreview?>(null) }

    fun load() {
        scope.launch {
            servicesState = LoadState.Loading
            runCatching { repository.listServices() }
                .onSuccess { servicesState = LoadState.Data(it) }
                .onFailure { servicesState = LoadState.Error(it.message ?: "加载第三方服务失败") }
        }
    }

    fun prepareImportOrUpdate(url: String) {
        scope.launch {
            busy = true
            message = null
            runCatching { repository.prepareImportFromGitHub(url) }
                .onSuccess {
                    pendingPreview = it
                    message = "预检完成，请确认权限和允许来源"
                }
                .onFailure { message = it.message ?: "预检失败" }
            busy = false
        }
    }

    fun commitPreview(preview: ThirdPartyServiceImportPreview) {
        scope.launch {
            busy = true
            message = null
            runCatching { repository.commitPreparedImport(preview.token) }
                .onSuccess {
                    githubUrl = ""
                    pendingPreview = null
                    message = if (it.updatedExisting) "已更新服务，请重新确认权限" else "已导入服务，请打开后确认权限"
                    load()
                }
                .onFailure { message = it.message ?: "导入失败" }
            busy = false
        }
    }

    fun cancelPreview(preview: ThirdPartyServiceImportPreview) {
        repository.discardPreparedImport(preview.token)
        pendingPreview = null
        message = "已取消导入"
    }

    LaunchedEffect(Unit) {
        repository.cleanupStalePreparedImports()
        load()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(14.dp),
    ) {
        item {
            SectionTitle(
                title = "第三方服务",
                subtitle = "从公开 GitHub 仓库导入静态 Web 服务",
            )
        }
        item {
            InfoCard(title = "导入服务", subtitle = "仓库根目录需包含 bjtu-service.json 和 dist/") {
                OutlinedTextField(
                    value = githubUrl,
                    onValueChange = { githubUrl = it },
                    label = { Text("GitHub 仓库链接") },
                    placeholder = { Text("https://github.com/owner/repo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !busy && githubUrl.isNotBlank(),
                    onClick = { prepareImportOrUpdate(githubUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(if (busy) "处理中" else "预检")
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        pendingPreview?.let { preview ->
            item {
                ThirdPartyImportPreviewCard(
                    preview = preview,
                    busy = busy,
                    onConfirm = { commitPreview(preview) },
                    onCancel = { cancelPreview(preview) },
                )
            }
        }
        when (val current = servicesState) {
            LoadState.Loading, is LoadState.Error -> item {
                LoadingOrError(current)
            }
            is LoadState.Data -> {
                if (current.value.isEmpty()) {
                    item {
                        InfoCard("尚未安装第三方服务", subtitle = "导入公开 GitHub 仓库后会显示在这里") {}
                    }
                } else {
                    items(current.value, key = { it.serviceId }) { service ->
                        ThirdPartyServiceManagementCard(
                            service = service,
                            busy = busy,
                            onOpen = { onOpenService(thirdPartyServiceRoute(service.serviceId)) },
                            onUpdate = { prepareImportOrUpdate(service.sourceUrl) },
                            onDelete = {
                                scope.launch {
                                    busy = true
                                    runCatching { repository.deleteService(service.serviceId) }
                                        .onFailure { message = it.message ?: "删除失败" }
                                    busy = false
                                    load()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThirdPartyImportPreviewCard(
    preview: ThirdPartyServiceImportPreview,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    InfoCard(
        title = if (preview.updatedExisting) "确认更新" else "确认导入",
        subtitle = "${preview.manifest.name} · ${preview.githubOwner}/${preview.githubRepo}@${preview.commitSha.take(8)}",
    ) {
        Text(preview.manifest.description, style = MaterialTheme.typography.bodyMedium)
        Text("版本：${preview.manifest.version}", style = MaterialTheme.typography.bodySmall)
        Text("作者：${preview.manifest.author}", style = MaterialTheme.typography.bodySmall)
        Text("Digest：${preview.packageDigestSha256.take(12)}", style = MaterialTheme.typography.bodySmall)
        Text(
            "包内容：${preview.packageFileCount} 个文件，${formatBytes(preview.packageBytes)}",
            style = MaterialTheme.typography.bodySmall,
        )
        PermissionSummary("必须授权", preview.manifest.permissions.required)
        if (preview.manifest.permissions.optional.isNotEmpty()) {
            PermissionSummary("可选授权", preview.manifest.permissions.optional)
        }
        TrustedOriginsSummary(preview.manifest.allowedOrigins)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(if (preview.updatedExisting) "确认更新" else "确认导入")
            }
            OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("取消")
            }
        }
    }
}

@Composable
private fun PermissionSummary(title: String, permissions: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        if (permissions.isEmpty()) {
            Text("无", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            permissions.forEach { id ->
                val permission = ThirdPartyPermissionRegistry.get(id)
                Text(
                    permission?.title ?: id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrustedOriginsSummary(origins: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("允许执行和联网来源", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        if (origins.isEmpty()) {
            Text("仅本地安装目录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            origins.forEach { origin ->
                Text(origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "这些来源加载的页面或 iframe 视为插件代码，可调用已授权接口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ThirdPartyServiceManagementCard(
    service: ThirdPartyService,
    busy: Boolean,
    onOpen: () -> Unit,
    onUpdate: () -> Unit,
    onDelete: () -> Unit,
) {
    val canUpdateFromGitHub = service.sourceUrl.startsWith("https://github.com/")
    InfoCard(
        title = service.manifest.name,
        subtitle = "${service.manifest.version} · ${service.githubOwner}/${service.githubRepo}",
    ) {
        Text(
            service.manifest.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (service.needsReview || !service.enabled) {
                "需要确认权限 · ${service.packageDigestSha256.take(12)}"
            } else {
                "已启用 · ${service.commitSha.take(8)} · ${service.packageDigestSha256.take(12)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (service.needsReview || !service.enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onOpen, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Security, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(if (service.needsReview || !service.enabled) "授权" else "打开")
            }
            OutlinedButton(onClick = onUpdate, enabled = !busy && canUpdateFromGitHub, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 3.dp))
                Text(if (canUpdateFromGitHub) "更新" else "内置")
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
fun ThirdPartyServiceRoute(
    serviceId: String,
    repository: ThirdPartyServiceRepository,
    apiRegistry: ThirdPartyServiceApiRegistry,
    onBackToServices: () -> Unit,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var state by remember(serviceId) { mutableStateOf<LoadState<ThirdPartyService>>(LoadState.Loading) }

    fun load() {
        scope.launch {
            state = LoadState.Loading
            runCatching { repository.getService(serviceId) }
                .onSuccess { service ->
                    state = service?.let { LoadState.Data(it) } ?: LoadState.Error("第三方服务不存在")
                }
                .onFailure { state = LoadState.Error(it.message ?: "加载第三方服务失败") }
        }
    }

    LaunchedEffect(serviceId) { load() }

    when (val current = state) {
        LoadState.Loading, is LoadState.Error -> Box(Modifier.padding(14.dp)) {
            LoadingOrError(current)
        }
        is LoadState.Data -> {
            val service = current.value
            if (service.needsReview || !service.enabled) {
                ThirdPartyPermissionReviewScreen(
                    service = service,
                    onGrant = { granted ->
                        scope.launch {
                            state = LoadState.Loading
                            runCatching { repository.grantService(service.serviceId, granted) }
                                .onSuccess { state = LoadState.Data(it) }
                                .onFailure { state = LoadState.Error(it.message ?: "授权失败") }
                        }
                    },
                    onBackToServices = onBackToServices,
                )
            } else {
                ThirdPartyServiceWebViewScreen(
                    service = service,
                    apiRegistry = apiRegistry,
                    onCloseService = onBackToServices,
                    onBackHandlerChanged = onBackHandlerChanged,
                )
            }
        }
    }
}

@Composable
private fun ThirdPartyPermissionReviewScreen(
    service: ThirdPartyService,
    onGrant: (Set<String>) -> Unit,
    onBackToServices: () -> Unit,
) {
    val required = service.manifest.permissions.required
    val optional = service.manifest.permissions.optional
    var selected by remember(service.serviceId, service.commitSha) {
        mutableStateOf((required + optional).toSet())
    }
    val canEnable = selected.containsAll(required)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(14.dp),
    ) {
        item {
            SectionTitle(
                title = "确认权限",
                subtitle = "${service.manifest.name} · ${service.githubOwner}/${service.githubRepo}@${service.commitSha.take(8)}",
            )
        }
        item {
            InfoCard(title = "服务信息", subtitle = service.manifest.description) {
                Text("作者：${service.manifest.author}", style = MaterialTheme.typography.bodySmall)
                Text("版本：${service.manifest.version}", style = MaterialTheme.typography.bodySmall)
                Text("来源：${service.sourceUrl}", style = MaterialTheme.typography.bodySmall)
                Text("Digest：${service.packageDigestSha256.take(12)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            PermissionGroupCard(
                title = "必须授权",
                subtitle = "不同意这些权限则无法启用服务",
                permissions = required,
                selected = selected,
                onSelectedChange = { id, checked ->
                    selected = if (checked) selected + id else selected - id
                },
            )
        }
        if (optional.isNotEmpty()) {
            item {
                PermissionGroupCard(
                    title = "可选授权",
                    subtitle = "拒绝后仍可进入服务，对应接口会被拒绝",
                    permissions = optional,
                    selected = selected,
                    onSelectedChange = { id, checked ->
                        selected = if (checked) selected + id else selected - id
                    },
                )
            }
        }
        item {
            InfoCard(title = "允许执行和联网来源", subtitle = "这些 HTTP/HTTPS origin 视为受信任插件代码来源") {
                if (service.allowedOrigins.isEmpty()) {
                    Text("未声明远程来源，仅允许运行本地安装目录内页面。", style = MaterialTheme.typography.bodySmall)
                } else {
                    service.allowedOrigins.forEach { origin ->
                        Text(origin, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "这些来源加载的页面或 iframe 可以调用本服务已授权的 BJTU MIS 接口。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canEnable,
                    onClick = { onGrant(selected) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("同意并启用")
                }
                OutlinedButton(onClick = onBackToServices, modifier = Modifier.fillMaxWidth()) {
                    Text("返回服务管理")
                }
                if (!canEnable) {
                    Text(
                        "必须权限未全部同意，服务无法启用。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionGroupCard(
    title: String,
    subtitle: String,
    permissions: List<String>,
    selected: Set<String>,
    onSelectedChange: (String, Boolean) -> Unit,
) {
    InfoCard(title = title, subtitle = subtitle) {
        permissions.forEach { id ->
            val permission = ThirdPartyPermissionRegistry.get(id)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = id in selected,
                    onCheckedChange = { onSelectedChange(id, it) },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        permission?.title ?: id,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        permission?.description ?: "未知权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ThirdPartyServiceWebViewScreen(
    service: ThirdPartyService,
    apiRegistry: ThirdPartyServiceApiRegistry,
    onCloseService: () -> Unit,
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit = {},
) {
    val composeScope = rememberCoroutineScope()
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pendingConfirmation by remember { mutableStateOf<PendingConfirmation?>(null) }
    val bridgeScope = remember(service.serviceId, service.commitSha) { CoroutineScope(SupervisorJob()) }
    val confirmer = remember(service.serviceId, service.commitSha) {
        ThirdPartySensitiveActionConfirmer { title, message ->
            val deferred = CompletableDeferred<Boolean>()
            composeScope.launch {
                pendingConfirmation = PendingConfirmation(title, message, deferred)
            }
            deferred.await()
        }
    }

    DisposableEffect(bridgeScope) {
        onDispose { bridgeScope.cancel() }
    }

    DisposableEffect(service, onBackHandlerChanged) {
        onBackHandlerChanged {
            if (webViewRef?.canGoBack() == true) {
                webViewRef?.goBack()
                true
            } else {
                false
            }
        }
        onDispose { onBackHandlerChanged(null) }
    }

    pendingConfirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pending.decision.complete(false)
                pendingConfirmation = null
            },
            title = { Text(pending.title) },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending.decision.complete(true)
                        pendingConfirmation = null
                    },
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pending.decision.complete(false)
                        pendingConfirmation = null
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewRef = this
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                webViewClient = ThirdPartyWebViewClient(service)
                addJavascriptInterface(
                    ThirdPartyNativeBridge(
                        service = service,
                        apiRegistry = apiRegistry,
                        confirmer = confirmer,
                        scope = bridgeScope,
                        webViewProvider = { webViewRef },
                        onCloseService = onCloseService,
                    ),
                    "BjtuServiceNative",
                )
                loadUrl(ThirdPartyServiceSandbox.entrypointUrlFor(service))
            }
        },
    )
}

private data class PendingConfirmation(
    val title: String,
    val message: String,
    val decision: CompletableDeferred<Boolean>,
)

private class ThirdPartyNativeBridge(
    private val service: ThirdPartyService,
    private val apiRegistry: ThirdPartyServiceApiRegistry,
    private val confirmer: ThirdPartySensitiveActionConfirmer,
    private val scope: CoroutineScope,
    private val webViewProvider: () -> WebView?,
    private val onCloseService: () -> Unit,
) {
    @JavascriptInterface
    fun invoke(requestJson: String) {
        scope.launch {
            val requestStart = System.currentTimeMillis()
            val parsedRequest = runCatching { AppJson.parseToJsonElement(requestJson).jsonObject }
            val request = parsedRequest.getOrNull()
            val requestId = request?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
            val method = request?.get("method")?.jsonPrimitive?.contentOrNull.orEmpty()
            val params = request?.get("params") as? JsonObject ?: buildJsonObject { }
            val traceRequestId = requestId.ifBlank { "unknown" }
            val userIdentity = extractRequestUserIdentity(params)

            val response = if (request == null) {
                Log.w(
                    ThirdPartyBridgeLogTag,
                    "Third-party bridge parse failure: serviceId=${service.serviceId}, requestId=$traceRequestId, method=$method, error=${parsedRequest.exceptionOrNull()?.message}",
                )
                requestId to bridgeErrorResponse(
                    code = "bridge_failed",
                    message = parsedRequest.exceptionOrNull()?.message ?: "第三方服务桥接请求格式错误",
                )
            } else {
                runCatching {
                    val currentPageUrl = withContext(Dispatchers.Main.immediate) {
                        webViewProvider()?.url.orEmpty()
                    }
                    val trace = "serviceId=${service.serviceId}, requestId=$traceRequestId, userIdentity=$userIdentity, platform=android_webview"
                    if (!ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                            url = currentPageUrl,
                            serviceId = service.serviceId,
                            commitSha = service.commitSha,
                            allowedOrigins = service.allowedOrigins,
                        )
                    ) {
                        Log.w(
                            ThirdPartyBridgeLogTag,
                            "Third-party bridge blocked by policy: $trace, url=$currentPageUrl",
                        )
                        return@runCatching requestId to bridgeErrorResponse("bridge_failed", "当前页面不在第三方服务允许执行来源内")
                    }
                    if (method == "app.close_service") {
                        Log.i(
                            ThirdPartyBridgeLogTag,
                            "Third-party bridge close_service: $trace, durationMs=${System.currentTimeMillis() - requestStart}",
                        )
                        return@runCatching requestId to buildJsonObject {
                            put("ok", true)
                            put("data", buildJsonObject { })
                        }
                    }
                    if (method == "app.http_request") {
                        val requestTargetUrl = params.string("url").orEmpty()
                        Log.i(
                            ThirdPartyBridgeLogTag,
                            "Third-party bridge http_request start: $trace, requestUrl=${urlForLog(requestTargetUrl)}, durationMs=${System.currentTimeMillis() - requestStart}",
                        )
                        return@runCatching requestId to httpRequest(
                            requestId = traceRequestId,
                            params = params,
                            requestUrl = currentPageUrl,
                            userIdentity = userIdentity,
                        )
                    }
                    val result = requestId to apiRegistry.invoke(
                        service = service,
                        method = method,
                        params = params,
                        confirmer = confirmer,
                        currentPageUrl = currentPageUrl,
                    )
                    val ok = result.second["ok"]?.jsonPrimitive?.booleanOrNull == true
                    Log.i(
                        ThirdPartyBridgeLogTag,
                        "Third-party bridge api invoke: $trace, method=$method, ok=$ok, durationMs=${System.currentTimeMillis() - requestStart}",
                    )
                    result
                }.getOrElse { error ->
                    Log.w(
                        ThirdPartyBridgeLogTag,
                        "Third-party bridge invoke failed: requestId=$traceRequestId serviceId=${service.serviceId} method=$method, error=${error.message}",
                        error,
                    )
                    requestId to bridgeErrorResponse("bridge_failed", error.message ?: "第三方服务桥接失败")
                }
            }
            val callbackId = response.first
            val payload = response.second.toString()
            val shouldClose = method == "app.close_service" &&
                response.second["ok"]?.jsonPrimitive?.booleanOrNull == true
            Log.i(
                ThirdPartyBridgeLogTag,
                "Third-party bridge resolved: serviceId=${service.serviceId}, requestId=$traceRequestId, method=$method, ok=${response.second["ok"]?.jsonPrimitive?.booleanOrNull}",
            )
            webViewProvider()?.post {
                webViewProvider()?.evaluateJavascript(
                    "window.BjtuService && window.BjtuService.__resolve(${JSONObject.quote(callbackId)}, $payload);",
                    null,
                )
                if (shouldClose) onCloseService()
            }
        }
    }

    private suspend fun httpRequest(
        requestId: String,
        params: JsonObject,
        requestUrl: String,
        userIdentity: String?,
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = params.string("url") ?: throw IllegalArgumentException("缺少请求 URL")
        val origin = ThirdPartyWebViewAccessPolicy.origin(url)
            ?: throw IllegalArgumentException("仅支持 HTTP/HTTPS 请求")
        if (origin !in service.allowedOrigins) {
            throw IllegalArgumentException("请求来源未在第三方服务 allowed_origins 中声明：$origin")
        }

        val method = (params.string("method") ?: "GET").uppercase(Locale.US)
        if (method !in setOf("GET", "POST", "PUT", "DELETE")) {
            throw IllegalArgumentException("不支持的请求方法：$method")
        }

        val headers = params["headers"]?.jsonObject.orEmpty()
        val trace = "serviceId=${service.serviceId}, requestId=$requestId, userIdentity=${userIdentity ?: "unknown"}, platform=android_webview"
        Log.i(
            ThirdPartyBridgeLogTag,
            "Third-party HTTP request start: $trace, pageUrl=${urlForLog(requestUrl)}, requestUrl=${urlForLog(url)}, method=$method",
        )
        val requestBuilder = OkHttpRequest.Builder().url(url)
        headers.forEach { (name, value) ->
            val headerValue = value.jsonPrimitive.contentOrNull.orEmpty()
            if (isForwardableHeader(name) && headerValue.isNotBlank()) {
                requestBuilder.header(name.trim(), headerValue)
            }
        }

        val bodyText = params["data"]?.toString()
        if (method == "GET") {
            requestBuilder.get()
        } else {
            val contentType = headers.firstNotNullOfOrNull { (name, value) ->
                value.jsonPrimitive.contentOrNull?.takeIf { name.equals("Content-Type", ignoreCase = true) }
            } ?: "application/json;charset=UTF-8"
            requestBuilder.method(method, (bodyText ?: "").toRequestBody(contentType.toMediaTypeOrNull()))
        }

        ThirdPartyBridgeHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (responseText.toByteArray(Charsets.UTF_8).size > MaxThirdPartyHttpResponseBytes) {
                throw IllegalArgumentException("第三方服务 HTTP 响应超过 5 MiB 限制")
            }
            val responseData = runCatching { AppJson.parseToJsonElement(responseText) }
                .getOrElse { JsonPrimitive(responseText) }
            val responseStatusCode = response.code
            val responseJson = responseData as? JsonObject
            val responseBizCode = responseJson
                ?.get("code")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toIntOrNull()
            val responseBizStatus = responseJson?.get("status")?.jsonPrimitive?.contentOrNull
            val responseBizMessage = responseJson?.get("message")?.jsonPrimitive?.contentOrNull
            val responseBizAttempts = responseJson?.get("attempts")?.jsonPrimitive?.contentOrNull
            val responseCaptchaChallengeId = runCatching {
                responseJson
                    ?.get("captcha")
                    ?.jsonObject
                    ?.get("challengeId")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrElse { null }
            val responseTrace = if (
                url.contains("/api/auth/mis/auto-login") ||
                url.contains("/api/auth/mis/manual-login")
            ) {
                ", payloadStatus=$responseBizStatus, payloadMessage=$responseBizMessage, attempts=$responseBizAttempts, challengeId=${responseCaptchaChallengeId ?: "null"}"
            } else {
                ", payloadMessage=$responseBizMessage"
            }
            if (responseStatusCode in 401..403 || responseBizCode in AUTH_EXPIRED_SERVICE_CODES) {
                Log.w(
                    ThirdPartyBridgeLogTag,
                    "Third-party HTTP auth-expired hint: $trace, requestUrl=${urlForLog(url)}, status=$responseStatusCode, appCode=$responseBizCode$responseTrace",
                )
            } else {
                Log.i(
                    ThirdPartyBridgeLogTag,
                    "Third-party HTTP request done: $trace, requestUrl=${urlForLog(url)}, status=$responseStatusCode, appCode=$responseBizCode$responseTrace",
                )
            }
            buildJsonObject {
                put("ok", true)
                put(
                    "data",
                    buildJsonObject {
                        put("statusCode", responseStatusCode)
                        put("data", responseData)
                        put(
                            "header",
                            buildJsonObject {
                                response.headers.names().forEach { name ->
                                    put(name, response.headers.values(name).joinToString(","))
                                }
                            }
                        )
                    }
                )
            }
        }
    }

    private fun extractRequestUserIdentity(params: JsonObject): String {
        val identityByParam = params.string("userIdentity")?.ifBlank { null }
            ?: params.string("userId")?.ifBlank { null }
            ?: params.string("user_id")?.ifBlank { null }
        if (!identityByParam.isNullOrBlank()) return identityByParam

        val token = params["headers"]?.jsonObject?.entries?.firstOrNull { (name) ->
            name.equals("authorization", ignoreCase = true)
        }?.value?.jsonPrimitive?.contentOrNull
        if (!token.isNullOrBlank()) return "authorization_header"
        return "unknown"
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

    private fun isForwardableHeader(name: String): Boolean {
        val normalized = name.trim()
        if (!normalized.matches(Regex("^[A-Za-z0-9-]+$"))) return false
        return normalized.lowercase(Locale.US) !in setOf(
            "host",
            "connection",
            "content-length",
            "transfer-encoding",
            "accept-encoding",
            "cookie",
            "origin",
        )
    }

    private fun urlForLog(value: String): String {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return value.take(256)
        val scheme = uri.scheme ?: return value.take(256)
        val authority = uri.encodedAuthority ?: return value.take(256)
        val path = uri.encodedPath.orEmpty()
        return "$scheme://$authority$path"
    }
}

private fun bridgeErrorResponse(code: String, message: String): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", buildJsonObject {
        put("code", code)
        put("message", message)
    })
}

private class ThirdPartyWebViewClient(
    private val service: ThirdPartyService,
) : WebViewClient() {
    private val installRoot = File(service.installDir)
    private val allowedOrigins = service.allowedOrigins.toSet()

    override fun onPageFinished(view: WebView, url: String?) {
        view.evaluateJavascript(BjtuServiceBridgeScript, null)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !isAllowed(request.url)

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        when (
            val resolution = ThirdPartyServiceSandbox.resolveLocalResource(
                url = request.url.toString(),
                serviceId = service.serviceId,
                commitSha = service.commitSha,
                installDir = installRoot,
                entrypoint = service.manifest.entrypoint,
            )
        ) {
            is ThirdPartySandboxResourceResolution.Found -> localFileResponse(resolution.resource.file)
            ThirdPartySandboxResourceResolution.NotFound -> notFoundResponse()
            ThirdPartySandboxResourceResolution.Blocked -> blockedResponse()
            ThirdPartySandboxResourceResolution.NotSandboxUrl ->
                if (isAllowed(request.url)) null else blockedResponse()
        }

    private fun isAllowed(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> ThirdPartyServiceSandbox.isServiceSandboxUrl(
                uri.toString(),
                service.serviceId,
                service.commitSha,
            ) || ThirdPartyWebViewAccessPolicy.origin(uri.toString()) in allowedOrigins
            else -> false
        }
    }

    private fun localFileResponse(file: File): WebResourceResponse =
        runCatching {
            val mimeType = mimeTypeFor(file)
            WebResourceResponse(
                mimeType,
                encodingFor(mimeType),
                200,
                "OK",
                mapOf("Cache-Control" to "no-store"),
                file.inputStream(),
            )
        }.getOrElse {
            notFoundResponse()
        }

    private fun notFoundResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream("Third-party service resource not found.".toByteArray()),
        )

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            emptyMap(),
            ByteArrayInputStream("Blocked by BJTU MIS third-party service policy.".toByteArray()),
        )

    private fun mimeTypeFor(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "html", "htm" -> "text/html"
                "js", "mjs" -> "application/javascript"
                "css" -> "text/css"
                "json", "map" -> "application/json"
                "svg" -> "image/svg+xml"
                "wasm" -> "application/wasm"
                else -> "application/octet-stream"
            }
    }

    private fun encodingFor(mimeType: String): String? =
        if (
            mimeType.startsWith("text/") ||
            mimeType == "application/javascript" ||
            mimeType == "application/json" ||
            mimeType == "image/svg+xml"
        ) {
            "UTF-8"
        } else {
            null
        }
}

private val BjtuServiceBridgeScript = """
    (function () {
      if (window.BjtuService && window.BjtuService.invoke) return;
      var callbacks = {};
      window.BjtuService = {
        invoke: function (method, params) {
          return new Promise(function (resolve) {
            var id = String(Date.now()) + "-" + Math.random().toString(16).slice(2);
            callbacks[id] = resolve;
            window.BjtuServiceNative.invoke(JSON.stringify({ id: id, method: method, params: params || {} }));
          });
        },
        __resolve: function (id, payload) {
          var callback = callbacks[id];
          if (!callback) return;
          delete callbacks[id];
          callback(payload);
        }
      };
    })();
""".trimIndent()

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MiB"
        bytes >= 1024L -> "${bytes / 1024L} KiB"
        else -> "$bytes B"
    }
