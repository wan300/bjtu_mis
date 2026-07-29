package cn.edu.bjtu.mis.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.webkit.CookieManager
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.thirdparty.CatalogPlugin
import cn.edu.bjtu.mis.data.thirdparty.CatalogPluginPage
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyPermissionRegistry
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartySandboxResourceResolution
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartySensitiveActionConfirmer
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyCatalogRepository
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
import cn.edu.bjtu.mis.ui.components.ThirdPartyPluginIcon
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
import java.io.ByteArrayInputStream
import java.io.File

private const val ThirdPartyBridgeLogTag = "ThirdPartyBridge"

@Composable
fun ThirdPartyServicesScreen(
    repository: ThirdPartyServiceRepository,
    catalogRepository: ThirdPartyCatalogRepository,
    onOpenService: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showInstalled by remember { mutableStateOf(false) }
    var servicesState by remember { mutableStateOf<LoadState<List<ThirdPartyService>>>(LoadState.Loading) }
    var catalogState by remember { mutableStateOf<LoadState<CatalogPluginPage>>(LoadState.Loading) }
    var catalogUpdates by remember { mutableStateOf<Map<String, CatalogPlugin>>(emptyMap()) }
    var searchQuery by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("") }
    var githubUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingPreview by remember { mutableStateOf<ThirdPartyServiceImportPreview?>(null) }

    fun load() {
        scope.launch {
            servicesState = LoadState.Loading
            runCatching { repository.listServices() }
                .onSuccess { services ->
                    servicesState = LoadState.Data(services)
                    catalogUpdates = runCatching { catalogRepository.resolveUpdates(services) }
                        .getOrDefault(emptyList())
                        .filter { it.updateAvailable }
                        .associateBy { it.id }
                }
                .onFailure { servicesState = LoadState.Error(it.message ?: "加载第三方服务失败") }
        }
    }

    fun loadCatalog() {
        scope.launch {
            catalogState = LoadState.Loading
            runCatching { catalogRepository.listPlugins(query = searchQuery, category = categoryFilter) }
                .onSuccess { catalogState = LoadState.Data(it) }
                .onFailure { catalogState = LoadState.Error(it.message ?: "插件大厅暂时不可用") }
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

    fun prepareCatalogInstall(plugin: CatalogPlugin) {
        scope.launch {
            busy = true
            message = null
            runCatching { repository.prepareInstallFromCatalog(plugin) }
                .onSuccess {
                    pendingPreview = it
                    message = "平台快照和双重 digest 校验完成，请确认安装风险"
                }
                .onFailure { message = it.message ?: "平台快照预检失败" }
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
        loadCatalog()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(14.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { showInstalled = false }, enabled = showInstalled, modifier = Modifier.weight(1f)) {
                    Text("插件大厅")
                }
                Button(onClick = { showInstalled = true }, enabled = !showInstalled, modifier = Modifier.weight(1f)) {
                    Text("已安装")
                }
            }
        }
        if (!showInstalled) {
            item {
                InfoCard(title = "搜索插件", subtitle = "断网时自动展示最近一次成功目录快照") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("名称、作者、描述或插件 ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = categoryFilter,
                        onValueChange = { categoryFilter = it },
                        label = { Text("分类（可选）") },
                        placeholder = { Text("academic / campus / information / productivity / assistant / other") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { loadCatalog() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("搜索") }
                }
            }
            message?.let { currentMessage -> item {
                Text(currentMessage, color = if (currentMessage.contains("失败")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            pendingPreview?.let { preview -> item {
                ThirdPartyImportPreviewCard(preview, busy, { commitPreview(preview) }, { cancelPreview(preview) })
            } }
            when (val current = catalogState) {
                LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
                is LoadState.Data -> {
                    if (current.value.fromCache) item {
                        Text("平台暂时不可用，当前展示本机缓存；已安装插件可继续运行。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (current.value.items.isEmpty()) item { InfoCard("暂无插件", subtitle = "可在 Web 插件大厅投稿公开 GitHub 仓库") {} }
                    items(current.value.items, key = { it.id }) { plugin ->
                        CatalogPluginCard(
                            plugin = plugin,
                            installed = (servicesState as? LoadState.Data<List<ThirdPartyService>>)?.value?.any { it.serviceId == plugin.id } == true,
                            busy = busy,
                            onInstall = { prepareCatalogInstall(plugin) },
                        )
                    }
                }
            }
        }
        if (showInstalled) {
        item {
            InfoCard(title = "高级 / 开发者导入", subtitle = "未经过平台自动校验；仓库根目录需包含 bjtu-service.json 和 dist/") {
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
                Text(
                    "警告：此方式直接下载 GitHub 内容，不具有平台归档 digest 背书。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
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
                            updateAvailable = service.serviceId in catalogUpdates,
                            busy = busy,
                            onOpen = { onOpenService(thirdPartyServiceRoute(service.serviceId)) },
                            onUpdate = {
                                catalogUpdates[service.serviceId]?.let { prepareCatalogInstall(it) }
                                    ?: prepareImportOrUpdate(service.sourceUrl)
                            },
                            onConfigure = {
                                scope.launch {
                                    repository.requireReview(service.serviceId)
                                    onOpenService(thirdPartyServiceRoute(service.serviceId))
                                }
                            },
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
}

@Composable
private fun CatalogPluginCard(
    plugin: CatalogPlugin,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
) {
    InfoCard(
        title = plugin.name,
        subtitle = "${plugin.version} · ${plugin.repository} · 未人工审核",
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ThirdPartyPluginIcon(
                source = plugin.iconSource,
                contentDescription = "${plugin.name} 图标",
                modifier = Modifier.size(64.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(plugin.description, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "分类：${plugin.category} · 标签：${plugin.tags.joinToString().ifBlank { "无" }}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text("Commit：${plugin.commitSha.take(12)}", style = MaterialTheme.typography.bodySmall)
        Text("归档 SHA-256：${plugin.archiveSha256.take(12)}…", style = MaterialTheme.typography.bodySmall)
        Text(
            "Runtime ${plugin.minRuntimeVersion}–${plugin.runtimeVersion} · ${plugin.compatibilityState}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Publisher：${plugin.publisherSubjectId.ifBlank { "未知" }} · ${plugin.verificationLevel}",
            style = MaterialTheme.typography.bodySmall,
        )
        PermissionSummary("必须授权", plugin.permissions.required)
        OriginPolicySummary(
            connect = plugin.connectOrigins,
            media = plugin.mediaOrigins,
            frame = plugin.frameOrigins,
            navigation = plugin.navigationOrigins,
            bridge = plugin.bridgeOrigins,
        )
        if (plugin.configuration.isNotEmpty()) {
            Text(
                "配置：${plugin.configuration.joinToString { "${it.label}${if (it.required) "（必填）" else ""}" }}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        plugin.validationWarnings.forEach { warning ->
            Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onInstall, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (installed) "下载并比较更新" else "下载并预检")
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ThirdPartyPluginIcon(
                source = preview.iconSource,
                contentDescription = "${preview.manifest.name} 图标",
                modifier = Modifier.size(64.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(preview.manifest.description, style = MaterialTheme.typography.bodyMedium)
                Text("版本：${preview.manifest.version}", style = MaterialTheme.typography.bodySmall)
                Text("作者：${preview.manifest.author}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Digest：${preview.packageDigestSha256.take(12)}", style = MaterialTheme.typography.bodySmall)
        Text(
            if (preview.platformVerified) "来源：平台不可变快照 · 未人工审核" else "来源：高级直链导入 · 未经平台校验",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        preview.archiveSha256?.let { Text("归档 SHA-256：${it.take(12)}…", style = MaterialTheme.typography.bodySmall) }
        Text(
            "包内容：${preview.packageFileCount} 个文件，${formatBytes(preview.packageBytes)}",
            style = MaterialTheme.typography.bodySmall,
        )
        PermissionSummary("必须授权", preview.manifest.permissions.required)
        if (preview.manifest.permissions.optional.isNotEmpty()) {
            PermissionSummary("可选授权", preview.manifest.permissions.optional)
        }
        Text(
            "Publisher：${preview.publisherSubjectId} · ${preview.verificationLevel}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (preview.updatedExisting) {
            PermissionSummary("新增必须权限（确认更新即同意）", preview.addedRequiredPermissions)
            PermissionSummary("新增可选权限（更新后保持未授权）", preview.addedOptionalPermissions)
            PermissionSummary("将自动撤销", preview.removedPermissions)
            PermissionSummary("新增 origin", preview.addedOrigins)
            PermissionSummary("移除 origin", preview.removedOrigins)
        }
        OriginPolicySummary(
            connect = preview.manifest.connectOrigins,
            media = preview.manifest.mediaOrigins,
            frame = preview.manifest.frameOrigins,
            navigation = preview.manifest.navigationOrigins,
            bridge = preview.manifest.bridgeOrigins,
        )
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
private fun OriginPolicySummary(
    connect: List<String>,
    media: List<String>,
    frame: List<String>,
    navigation: List<String>,
    bridge: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Origin 策略", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        listOf(
            "Connect" to connect,
            "Media" to media,
            "Frame" to frame,
            "Navigation" to navigation,
            "Bridge" to bridge,
        ).forEach { (label, origins) ->
            Text(
                "$label：${origins.joinToString().ifBlank { "无" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (frame.isNotEmpty()) {
            Text(
                "远程 iframe 无原生桥、无顶层导航/下载/弹窗，第三方 Cookie 已关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (bridge != listOf("self")) {
            Text(
                "Bridge 策略无效，客户端将拒绝运行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ThirdPartyServiceManagementCard(
    service: ThirdPartyService,
    updateAvailable: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onUpdate: () -> Unit,
    onConfigure: () -> Unit,
    onDelete: () -> Unit,
) {
    val canUpdateFromGitHub = service.sourceUrl.startsWith("https://github.com/")
    InfoCard(
        title = service.manifest.name,
        subtitle = "${service.manifest.version} · ${service.githubOwner}/${service.githubRepo}",
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ThirdPartyPluginIcon(
                source = service.iconSource,
                contentDescription = "${service.manifest.name} 图标",
                modifier = Modifier.size(64.dp),
            )
            Text(
                service.manifest.description,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (service.needsReview || !service.enabled) {
                "需要确认权限 · ${service.packageDigestSha256.take(12)}"
            } else if (updateAvailable) {
                "发现平台快照更新 · 当前 ${service.commitSha.take(8)}"
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
        if (service.manifest.configuration.isNotEmpty()) {
            OutlinedButton(onClick = onConfigure, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("配置插件环境变量")
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
            if (
                service.compatibilityState !=
                cn.edu.bjtu.mis.data.thirdparty.ThirdPartyCompatibilityState.Compatible.value
            ) {
                ThirdPartyLegacyRescueScreen(service, onBackToServices)
            } else if (service.needsReview || !service.enabled) {
                ThirdPartyPermissionReviewScreen(
                    service = service,
                    repository = repository,
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ThirdPartyLegacyRescueScreen(
    service: ThirdPartyService,
    onBackToServices: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        InfoCard(
            title = "Legacy 只读救援模式",
            subtitle = "Manifest v1/v2 已停用；旧包和 WebStorage 未删除。",
        ) {
            Text("此入口无原生桥、无网络，仅用于查看旧插件本地数据。")
            OutlinedButton(onClick = onBackToServices, modifier = Modifier.fillMaxWidth()) {
                Text("返回插件管理")
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.setSupportMultipleWindows(false)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    setDownloadListener { _, _, _, _, _ -> }
                    webViewClient = ThirdPartyLegacyRescueWebViewClient(service)
                    val legacyOrigin = ThirdPartyServiceSandbox.originFor(
                        service.serviceId,
                        service.commitSha,
                    )
                    val path = java.net.URI(
                        null,
                        null,
                        "/${service.manifest.entrypoint.trimStart('/')}",
                        null,
                    ).rawPath
                    loadUrl(legacyOrigin + path)
                }
            },
        )
    }
}

@Composable
private fun ThirdPartyPermissionReviewScreen(
    service: ThirdPartyService,
    repository: ThirdPartyServiceRepository,
    onGrant: (Set<String>) -> Unit,
    onBackToServices: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val required = service.manifest.permissions.required
    val optional = service.manifest.permissions.optional
    var selected by remember(service.serviceId, service.commitSha) {
        mutableStateOf((required + optional).toSet())
    }
    var configurationValues by remember(service.serviceId, service.commitSha) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var configurationError by remember(service.serviceId, service.commitSha) { mutableStateOf<String?>(null) }
    LaunchedEffect(service.serviceId, service.commitSha) {
        runCatching { repository.getConfiguration(service.serviceId) }
            .onSuccess { configurationValues = it }
            .onFailure { configurationError = it.message ?: "读取插件配置失败" }
    }
    val configurationComplete = service.manifest.configuration
        .filter { it.required }
        .all { !(configurationValues[it.key] ?: it.default).isNullOrBlank() }
    val canEnable = selected.containsAll(required) && configurationComplete

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
        if (service.manifest.configuration.isNotEmpty()) {
            item {
                InfoCard(title = "插件配置", subtitle = "配置值仅保存在本机，并使用 Android Keystore / AES-GCM 加密") {
                    service.manifest.configuration.forEach { definition ->
                        OutlinedTextField(
                            value = configurationValues[definition.key].orEmpty(),
                            onValueChange = { value -> configurationValues = configurationValues + (definition.key to value) },
                            label = { Text("${definition.label}${if (definition.required) " *" else ""}") },
                            supportingText = { Text("${definition.key} · ${definition.description}") },
                            visualTransformation = if (definition.type == "secret") PasswordVisualTransformation() else VisualTransformation.None,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    configurationError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            InfoCard(title = "Origin 与桥接边界", subtitle = "远程内容永远不能调用原生桥") {
                OriginPolicySummary(
                    connect = service.manifest.connectOrigins,
                    media = service.manifest.mediaOrigins,
                    frame = service.manifest.frameOrigins,
                    navigation = service.manifest.navigationOrigins,
                    bridge = service.manifest.bridgeOrigins,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canEnable,
                    onClick = {
                        scope.launch {
                            configurationError = null
                            runCatching { repository.saveConfiguration(service.serviceId, configurationValues) }
                                .onSuccess { onGrant(selected) }
                                .onFailure { configurationError = it.message ?: "保存插件配置失败" }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("同意并启用")
                }
                OutlinedButton(onClick = onBackToServices, modifier = Modifier.fillMaxWidth()) {
                    Text("返回服务管理")
                }
                if (!canEnable) {
                    Text(
                        if (!configurationComplete) "请先填写全部必填配置。" else "必须权限未全部同意，服务无法启用。",
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val darkTheme = isSystemInDarkTheme()
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
            webViewRef?.let { view ->
                dispatchBackEvent(view, onCloseService)
                true
            } ?: false
        }
        onDispose { onBackHandlerChanged(null) }
    }

    DisposableEffect(webViewRef, lifecycleOwner, darkTheme) {
        val view = webViewRef
        if (view == null) return@DisposableEffect onDispose { }
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                dispatchRuntimeEvent(view, "resume", buildJsonObject { })
                dispatchRuntimeEnvironment(view, context, darkTheme)
            }

            override fun onPause(owner: LifecycleOwner) {
                dispatchRuntimeEvent(view, "pause", buildJsonObject { })
            }
        }
        val layoutListener = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            dispatchResizeEvent(view, context)
        }
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                dispatchNetworkEvent(view, connectivity)
            }

            override fun onLost(network: Network) {
                dispatchNetworkEvent(view, connectivity)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                dispatchNetworkEvent(view, connectivity)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        view.addOnLayoutChangeListener(layoutListener)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            dispatchResizeEvent(view, context, insets)
            insets
        }
        runCatching {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback)
        }
        dispatchRuntimeEnvironment(view, context, darkTheme)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.removeOnLayoutChangeListener(layoutListener)
            ViewCompat.setOnApplyWindowInsetsListener(view, null)
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        }
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
                configureThirdPartyPluginWebView(this)
                webViewClient = ThirdPartyWebViewClient(
                    service = service,
                    onMainPageReady = { view ->
                        dispatchRuntimeEnvironment(view, context, darkTheme)
                    },
                    openExternal = { url ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                )
                val supportsSecureRuntime = supportsThirdPartyV3Runtime()
                if (supportsSecureRuntime) {
                    val nativeBridge = ThirdPartyNativeBridge(
                        service = service,
                        apiRegistry = apiRegistry,
                        confirmer = confirmer,
                        scope = bridgeScope,
                        onCloseService = onCloseService,
                    )
                    val localOrigin = ThirdPartyServiceSandbox.originFor(
                        service.serviceId,
                        service.publisherSubjectId,
                    )
                    WebViewCompat.addDocumentStartJavaScript(
                        this,
                        BjtuServiceBridgeScript,
                        setOf(localOrigin),
                    )
                    WebViewCompat.addWebMessageListener(
                        this,
                        ThirdPartyBridgeObjectName,
                        setOf(localOrigin),
                        object : WebViewCompat.WebMessageListener {
                            override fun onPostMessage(
                                view: WebView,
                                message: WebMessageCompat,
                                sourceOrigin: Uri,
                                isMainFrame: Boolean,
                                replyProxy: JavaScriptReplyProxy,
                            ) {
                                val source = ThirdPartyWebViewAccessPolicy.origin(sourceOrigin.toString())
                                if (
                                    !ThirdPartyWebViewAccessPolicy.isTrustedBridgeMessage(
                                        isMainFrame,
                                        sourceOrigin.toString(),
                                        localOrigin,
                                    ) ||
                                    message.type != WebMessageCompat.TYPE_STRING
                                ) {
                                    Log.w(ThirdPartyBridgeLogTag, "Rejected non-string WebMessage from $sourceOrigin")
                                    return
                                }
                                nativeBridge.invoke(
                                    requestJson = message.data.orEmpty(),
                                    callerUrl = sourceOrigin.toString(),
                                    reply = replyProxy::postMessage,
                                )
                            }
                        },
                    )
                    loadUrl(ThirdPartyServiceSandbox.entrypointUrlFor(service))
                } else {
                    Log.e(ThirdPartyBridgeLogTag, "System WebView lacks secure v3 runtime features")
                    loadData(
                        "<html><body><h2>系统 WebView 不兼容</h2><p>Manifest v3 同时需要 DOCUMENT_START_SCRIPT 与 WEB_MESSAGE_LISTENER；不会降级到页面加载后注入。</p></body></html>",
                        "text/html",
                        "UTF-8",
                    )
                }
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
    private val onCloseService: () -> Unit,
) {
    fun invoke(
        requestJson: String,
        callerUrl: String,
        reply: (String) -> Unit,
    ) {
        scope.launch {
            val requestStart = System.currentTimeMillis()
            val parsedRequest = runCatching {
                if (requestJson.toByteArray(Charsets.UTF_8).size > 512 * 1024) {
                    throw IllegalArgumentException("第三方服务桥接请求超过 512 KiB")
                }
                AppJson.parseToJsonElement(requestJson).jsonObject
            }
            val request = parsedRequest.getOrNull()
            val requestId = request?.get("request_id")?.jsonPrimitive?.contentOrNull.orEmpty()
            val protocolVersion = request
                ?.get("protocol_version")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toIntOrNull()
            val method = request?.get("method")?.jsonPrimitive?.contentOrNull.orEmpty()
            val params = request?.get("params") as? JsonObject
            val unknownFields = request?.keys.orEmpty() -
                setOf("protocol_version", "request_id", "method", "params")
            val traceRequestId = requestId.ifBlank { "unknown" }

            val response = if (
                request == null ||
                protocolVersion != cn.edu.bjtu.mis.data.thirdparty.THIRD_PARTY_BRIDGE_PROTOCOL_VERSION ||
                requestId.isBlank() ||
                requestId.length > 128 ||
                method.isBlank() ||
                method.length > 128 ||
                params == null ||
                unknownFields.isNotEmpty()
            ) {
                Log.w(
                    ThirdPartyBridgeLogTag,
                    "Third-party bridge parse failure: serviceId=${service.serviceId}, requestId=$traceRequestId, method=$method, error=${parsedRequest.exceptionOrNull()?.message}",
                )
                requestId to bridgeErrorResponse(
                    code = "invalid_request",
                    message = parsedRequest.exceptionOrNull()?.message ?: "第三方服务桥接请求格式错误",
                )
            } else {
                runCatching {
                    val trace = "serviceId=${service.serviceId}, requestId=$traceRequestId, platform=android_webview"
                    if (!ThirdPartyWebViewAccessPolicy.isTrustedRuntimeUrl(
                            url = callerUrl,
                            serviceId = service.serviceId,
                            publisherSubjectId = service.publisherSubjectId,
                        )
                    ) {
                        Log.w(
                            ThirdPartyBridgeLogTag,
                            "Third-party bridge blocked by policy: $trace, url=$callerUrl",
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
                    val result = requestId to apiRegistry.invoke(
                        service = service,
                        method = method,
                        params = params,
                        confirmer = confirmer,
                        currentPageUrl = callerUrl,
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
            val shouldClose = method == "app.close_service" &&
                response.second["ok"]?.jsonPrimitive?.booleanOrNull == true
            Log.i(
                ThirdPartyBridgeLogTag,
                "Third-party bridge resolved: serviceId=${service.serviceId}, requestId=$traceRequestId, method=$method, ok=${response.second["ok"]?.jsonPrimitive?.booleanOrNull}",
            )
            withContext(Dispatchers.Main.immediate) {
                reply(normalizeBridgeResponse(callbackId, response.second).toString())
                if (shouldClose) onCloseService()
            }
        }
    }
}

private fun bridgeErrorResponse(code: String, message: String): JsonObject = buildJsonObject {
    put("ok", false)
    put("error", buildJsonObject {
        put("code", code)
        put("message", message)
        put("retryable", false)
    })
}

private fun normalizeBridgeResponse(requestId: String, payload: JsonObject): JsonObject =
    buildJsonObject {
        put(
            "protocol_version",
            cn.edu.bjtu.mis.data.thirdparty.THIRD_PARTY_BRIDGE_PROTOCOL_VERSION,
        )
        put("request_id", requestId)
        put("ok", payload["ok"] ?: JsonPrimitive(false))
        payload["data"]?.let { put("data", it) }
        val rawError = payload["error"] as? JsonObject
        rawError?.let { error ->
            put("error", buildJsonObject {
                put("code", error["code"] ?: JsonPrimitive("bridge_failed"))
                put("message", error["message"] ?: JsonPrimitive("第三方服务桥接失败"))
                put("request_id", requestId)
                put("retryable", error["retryable"] ?: JsonPrimitive(false))
                error["http_status"]?.let { put("http_status", it) }
                error["details"]?.let { put("details", it) }
            })
        }
    }

private class ThirdPartyLegacyRescueWebViewClient(
    private val service: ThirdPartyService,
) : WebViewClient() {
    private val installRoot = File(service.installDir)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !ThirdPartyServiceSandbox.isServiceSandboxUrl(
            request.url.toString(),
            service.serviceId,
            service.commitSha,
        )

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse = when (
        val resolution = ThirdPartyServiceSandbox.resolveLocalResource(
            url = request.url.toString(),
            serviceId = service.serviceId,
            publisherSubjectId = service.commitSha,
            installDir = installRoot,
            entrypoint = service.manifest.entrypoint,
        )
    ) {
        is ThirdPartySandboxResourceResolution.Found -> {
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(resolution.resource.file.extension.lowercase())
                ?: "application/octet-stream"
            WebResourceResponse(
                mime,
                if (mime.startsWith("text/") || mime.contains("javascript")) "UTF-8" else null,
                200,
                "OK",
                LegacyRescueHeaders,
                resolution.resource.file.inputStream(),
            )
        }
        ThirdPartySandboxResourceResolution.NotFound -> rescueError(404, "Not Found")
        ThirdPartySandboxResourceResolution.Blocked,
        ThirdPartySandboxResourceResolution.NotSandboxUrl -> rescueError(403, "Forbidden")
    }

    private fun rescueError(status: Int, reason: String) = WebResourceResponse(
        "text/plain",
        "UTF-8",
        status,
        reason,
        LegacyRescueHeaders,
        ByteArrayInputStream(reason.toByteArray()),
    )

    private companion object {
        val LegacyRescueHeaders = mapOf(
            "Cache-Control" to "no-store",
            "Content-Security-Policy" to
                "default-src 'self' data: blob:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'self'",
            "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
            "Referrer-Policy" to "no-referrer",
            "X-Content-Type-Options" to "nosniff",
        )
    }
}

private class ThirdPartyWebViewClient(
    private val service: ThirdPartyService,
    private val onMainPageReady: (WebView) -> Unit,
    private val openExternal: (String) -> Unit,
) : WebViewClient() {
    private val installRoot = File(service.installDir)
    private val resourceOrigins = (
        service.manifest.connectOrigins +
            service.manifest.mediaOrigins +
            service.manifest.frameOrigins
        ).toSet()
    private val frameOrigins = service.manifest.frameOrigins.toSet()
    private val navigationOrigins = service.manifest.navigationOrigins.toSet()

    override fun onPageFinished(view: WebView, url: String?) {
        if (
            ThirdPartyServiceSandbox.isServiceSandboxUrl(
                url,
                service.serviceId,
                service.publisherSubjectId,
            )
        ) {
            onMainPageReady(view)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
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
        val origin = ThirdPartyWebViewAccessPolicy.origin(url) ?: return true
        return if (request.isForMainFrame) {
            if (request.hasGesture() && origin in navigationOrigins) openExternal(url)
            true
        } else {
            origin !in frameOrigins
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        when (
            val resolution = ThirdPartyServiceSandbox.resolveLocalResource(
                url = request.url.toString(),
                serviceId = service.serviceId,
                publisherSubjectId = service.publisherSubjectId,
                installDir = installRoot,
                entrypoint = service.manifest.entrypoint,
            )
        ) {
            is ThirdPartySandboxResourceResolution.Found -> localFileResponse(resolution.resource.file)
            ThirdPartySandboxResourceResolution.NotFound -> notFoundResponse()
            ThirdPartySandboxResourceResolution.Blocked -> blockedResponse()
            ThirdPartySandboxResourceResolution.NotSandboxUrl ->
                if (ThirdPartyWebViewAccessPolicy.origin(request.url.toString()) in resourceOrigins) {
                    null
                } else {
                    blockedResponse()
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
                thirdPartySecurityHeaders(service.manifest),
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
            thirdPartySecurityHeaders(service.manifest),
            ByteArrayInputStream("Third-party service resource not found.".toByteArray()),
        )

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            thirdPartySecurityHeaders(service.manifest),
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

@SuppressLint("SetJavaScriptEnabled")
internal fun configureThirdPartyPluginWebView(webView: WebView) {
    with(webView.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        useWideViewPort = true
        loadWithOverviewMode = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        textZoom = 100
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        safeBrowsingEnabled = true
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    webView.setDownloadListener { _, _, _, _, _ ->
        Log.w(ThirdPartyBridgeLogTag, "Blocked plugin download request")
    }
}

internal fun supportsThirdPartyV3Runtime(): Boolean =
    WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

internal fun thirdPartySecurityHeaders(
    manifest: cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceManifest,
): Map<String, String> = mapOf(
    "Cache-Control" to "no-store",
    "Content-Security-Policy" to buildString {
        append("default-src 'self'; ")
        append("script-src 'self'; style-src 'self' 'unsafe-inline'; ")
        append("img-src 'self' data:; font-src 'self' data:; ")
        append("connect-src 'self'")
        manifest.connectOrigins.forEach { append(" ").append(it) }
        append("; media-src 'self'")
        manifest.mediaOrigins.forEach { append(" ").append(it) }
        append("; frame-src 'self'")
        manifest.frameOrigins.forEach { append(" ").append(it) }
        append("; object-src 'none'; base-uri 'self'; form-action 'self'")
    },
    "Permissions-Policy" to
        "camera=(), microphone=(), geolocation=(), payment=(), usb=(), bluetooth=(), serial=()",
    "Referrer-Policy" to "no-referrer",
    "X-Content-Type-Options" to "nosniff",
)

private fun dispatchBackEvent(view: WebView, onFallbackClose: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    var completed = false
    val fallback = Runnable {
        if (completed) return@Runnable
        completed = true
        if (view.canGoBack()) view.goBack() else onFallbackClose()
    }
    handler.postDelayed(fallback, 150L)
    view.evaluateJavascript(
        """
        (function () {
          var event = new CustomEvent('back', { cancelable: true, detail: {} });
          var namespacedEvent = new CustomEvent('bjtu:back', { cancelable: true, detail: {} });
          window.dispatchEvent(event);
          window.dispatchEvent(namespacedEvent);
          return event.defaultPrevented === true || namespacedEvent.defaultPrevented === true;
        })();
        """.trimIndent(),
    ) { raw ->
        if (completed) return@evaluateJavascript
        completed = true
        handler.removeCallbacks(fallback)
        if (raw != "true") {
            if (view.canGoBack()) view.goBack() else onFallbackClose()
        }
    }
}

private fun dispatchRuntimeEnvironment(view: WebView, context: Context, darkTheme: Boolean) {
    val reduceMotion = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
    dispatchRuntimeEvent(view, "theme", buildJsonObject {
        put("color_scheme", if (darkTheme) "dark" else "light")
        put("reduced_motion", reduceMotion)
        put(
            "high_contrast",
            runCatching {
                Settings.Secure.getInt(
                    context.contentResolver,
                    "high_text_contrast_enabled",
                    0,
                ) == 1
            }.getOrDefault(false),
        )
    })
    dispatchResizeEvent(view, context)
    dispatchNetworkEvent(
        view,
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
    )
}

private fun dispatchResizeEvent(
    view: WebView,
    context: Context,
    insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(view),
) {
    val configuration = context.resources.configuration
    val safeInsets = insets?.getInsets(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    )
    val imeInsets = insets?.getInsets(WindowInsetsCompat.Type.ime())
    dispatchRuntimeEvent(view, "resize", buildJsonObject {
        put("viewport_width_px", view.width)
        put("viewport_height_px", view.height)
        put("density", context.resources.displayMetrics.density)
        put("font_scale", configuration.fontScale)
        put(
            "orientation",
            if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                "landscape"
            } else {
                "portrait"
            },
        )
        put("safe_area_top_px", safeInsets?.top ?: 0)
        put("safe_area_right_px", safeInsets?.right ?: 0)
        put("safe_area_bottom_px", safeInsets?.bottom ?: 0)
        put("safe_area_left_px", safeInsets?.left ?: 0)
        put("ime_height_px", imeInsets?.bottom ?: 0)
    })
}

private fun dispatchNetworkEvent(view: WebView, connectivity: ConnectivityManager) {
    val capabilities = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
    val transport = when {
        capabilities == null -> "offline"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "other"
    }
    dispatchRuntimeEvent(view, "network", buildJsonObject {
        put(
            "online",
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
        )
        put(
            "validated",
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
        put("metered", connectivity.isActiveNetworkMetered)
        put("transport", transport)
    })
}

internal fun dispatchRuntimeEvent(view: WebView, name: String, detail: JsonObject) {
    view.post {
        view.evaluateJavascript(
            """
            (function () {
              window.dispatchEvent(new CustomEvent('$name', { detail: $detail }));
              window.dispatchEvent(new CustomEvent('bjtu:$name', { detail: $detail }));
            })();
            """.trimIndent(),
            null,
        )
    }
}

private val BjtuServiceBridgeScript = """
    (function () {
      var rootElement = function () { return document.documentElement; };
      var updateHostViewport = function () {
        var doc = rootElement();
        if (!doc) return;
        var viewport = window.visualViewport;
        var width = viewport && viewport.width ? viewport.width : (window.innerWidth || doc.clientWidth || 0);
        var height = viewport && viewport.height ? viewport.height : (window.innerHeight || doc.clientHeight || 0);
        if (width > 0) {
          doc.style.setProperty('--bjtu-host-vw', String(Math.round(width)) + 'px');
        }
        if (height > 0) {
          doc.style.setProperty('--bjtu-host-vh', String(Math.round(height)) + 'px');
        }
      };
      if (!window.__BjtuHostViewportPatched) {
        window.__BjtuHostViewportPatched = true;
        window.addEventListener('resize', updateHostViewport);
        if (window.visualViewport) {
          window.visualViewport.addEventListener('resize', updateHostViewport);
          window.visualViewport.addEventListener('scroll', updateHostViewport);
        }
      }
      updateHostViewport();
      window.addEventListener('bjtu:resize', function (event) {
        var doc = rootElement();
        if (!doc) return;
        var detail = event.detail || {};
        var css = {
          '--bjtu-safe-area-top': detail.safe_area_top_px,
          '--bjtu-safe-area-right': detail.safe_area_right_px,
          '--bjtu-safe-area-bottom': detail.safe_area_bottom_px,
          '--bjtu-safe-area-left': detail.safe_area_left_px,
          '--bjtu-ime-height': detail.ime_height_px
        };
        Object.keys(css).forEach(function (name) {
          var value = Number(css[name] || 0);
          doc.style.setProperty(name, String(Math.max(0, Math.round(value))) + 'px');
        });
      });
      window.addEventListener('bjtu:theme', function (event) {
        var doc = rootElement();
        if (!doc) return;
        var detail = event.detail || {};
        if (detail.color_scheme) doc.setAttribute('data-bjtu-theme', detail.color_scheme);
        doc.setAttribute('data-bjtu-reduced-motion', detail.reduced_motion ? 'true' : 'false');
        doc.setAttribute('data-bjtu-high-contrast', detail.high_contrast ? 'true' : 'false');
      });
      var secureFrame = function (frame) {
        if (!frame || frame.tagName !== 'IFRAME') return;
        if (frame.getAttribute('sandbox') !== 'allow-scripts allow-forms allow-same-origin') {
          frame.setAttribute('sandbox', 'allow-scripts allow-forms allow-same-origin');
        }
        frame.removeAttribute('allowfullscreen');
      };
      Array.prototype.forEach.call(document.querySelectorAll('iframe'), secureFrame);
      new MutationObserver(function (records) {
        records.forEach(function (record) {
          if (record.type === 'attributes') secureFrame(record.target);
          Array.prototype.forEach.call(record.addedNodes || [], function (node) {
            if (node && node.tagName === 'IFRAME') secureFrame(node);
            if (node && node.querySelectorAll) {
              Array.prototype.forEach.call(node.querySelectorAll('iframe'), secureFrame);
            }
          });
        });
      }).observe(document, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['sandbox', 'allow', 'allowfullscreen']
      });
      if (window.BjtuService && window.BjtuService.invoke) return;
      var callbacks = {};
      window.BjtuService = {
        invoke: function (method, params) {
          return new Promise(function (resolve) {
            var requestId = String(Date.now()) + "-" + Math.random().toString(16).slice(2);
            callbacks[requestId] = resolve;
            if (!window.BjtuServiceNative || typeof window.BjtuServiceNative.postMessage !== 'function') {
              delete callbacks[requestId];
              resolve({
                protocol_version: 1,
                request_id: requestId,
                ok: false,
                error: {
                  code: 'bridge_unavailable',
                  message: '第三方插件安全桥接不可用',
                  request_id: requestId,
                  retryable: false
                }
              });
              return;
            }
            window.BjtuServiceNative.postMessage(JSON.stringify({
              protocol_version: 1,
              request_id: requestId,
              method: method,
              params: params || {}
            }));
          });
        },
        __resolve: function (requestId, payload) {
          var callback = callbacks[requestId];
          if (!callback) return;
          delete callbacks[requestId];
          callback(payload);
        }
      };
      if (window.BjtuServiceNative) {
        window.BjtuServiceNative.onmessage = function (event) {
          try {
            var response = JSON.parse(event.data);
            window.BjtuService.__resolve(response.request_id, response);
          } catch (_) {
            // Ignore malformed native responses; native payloads are generated by the host.
          }
        };
      }
    })();
""".trimIndent()

private const val ThirdPartyBridgeObjectName = "BjtuServiceNative"

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MiB"
        bytes >= 1024L -> "${bytes / 1024L} KiB"
        else -> "$bytes B"
    }
