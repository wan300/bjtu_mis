package cn.edu.bjtu.mis.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.edu.bjtu.mis.data.repository.MailRepository
import cn.edu.bjtu.mis.data.repository.MailUploadFile
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.model.MailAttachment
import cn.edu.bjtu.mis.model.MailComposeAttachment
import cn.edu.bjtu.mis.model.MailComposeRequest
import cn.edu.bjtu.mis.model.MailContactSuggestion
import cn.edu.bjtu.mis.model.MailFolder
import cn.edu.bjtu.mis.model.MailFoldersData
import cn.edu.bjtu.mis.model.MailMessageDetail
import cn.edu.bjtu.mis.model.MailMessageSummary
import cn.edu.bjtu.mis.model.MailMessagesData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.PageActionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 20

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(
    repository: MailRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var foldersState by remember { mutableStateOf<LoadState<ModuleEnvelope<MailFoldersData>>>(LoadState.Loading) }
    var messagesState by remember { mutableStateOf<LoadState<ModuleEnvelope<MailMessagesData>>>(LoadState.Loading) }
    var selectedFolderId by remember { mutableStateOf("1") }
    var start by remember { mutableStateOf(0) }
    var detailMessage by remember { mutableStateOf<MailMessageSummary?>(null) }
    var detailState by remember { mutableStateOf<LoadState<ModuleEnvelope<MailMessageDetail>>?>(null) }
    var showCompose by remember { mutableStateOf(false) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadFolders(strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst) {
        scope.launch {
            foldersState = LoadState.Loading
            runCatching { repository.folders(strategy) }
                .onSuccess { envelope ->
                    foldersState = LoadState.Data(envelope)
                    val ids = envelope.data.folders.map { it.folderId }
                    if (selectedFolderId !in ids) {
                        selectedFolderId = ids.firstOrNull() ?: "1"
                    }
                }
                .onFailure { foldersState = LoadState.Error(it.message ?: "加载邮箱文件夹失败") }
        }
    }

    fun loadMessages(
        nextStart: Int = start,
        folderId: String = selectedFolderId,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ) {
        scope.launch {
            messagesState = LoadState.Loading
            error = null
            runCatching { repository.messages(folderId = folderId, start = nextStart, limit = PAGE_SIZE, strategy = strategy) }
                .onSuccess {
                    start = it.data.start
                    messagesState = LoadState.Data(it)
                }
                .onFailure { messagesState = LoadState.Error(it.message ?: "加载邮件失败") }
        }
    }

    fun loadDetail(message: MailMessageSummary) {
        detailMessage = message
        detailState = LoadState.Loading
        scope.launch {
            runCatching { repository.detail(message.messageId) }
                .onSuccess { detailState = LoadState.Data(it) }
                .onFailure { detailState = LoadState.Error(it.message ?: "加载邮件详情失败") }
        }
    }

    LaunchedEffect(Unit) {
        loadFolders(initialLoadStrategy)
        loadMessages(strategy = initialLoadStrategy)
    }

    if (showCompose) {
        ComposeMailDialog(
            repository = repository,
            onDismiss = { showCompose = false },
            onSent = {
                showCompose = false
                loadMessages(0)
            },
        )
    }

    val currentDetail = detailState
    if (detailMessage != null && currentDetail != null) {
        MailDetailDialog(
            state = currentDetail,
            busyMessage = busyMessage,
            error = error,
            onDismiss = {
                detailMessage = null
                detailState = null
                busyMessage = null
                error = null
            },
            onDelete = { detail ->
                scope.launch {
                    busyMessage = "正在删除"
                    error = null
                    runCatching { repository.delete(listOf(detail.messageId)) }
                        .onSuccess {
                            detailMessage = null
                            detailState = null
                            loadMessages(start)
                        }
                        .onFailure { error = it.message ?: "删除失败" }
                    busyMessage = null
                }
            },
            onDownload = { detail, attachment ->
                scope.launch {
                    busyMessage = "正在下载 ${attachment.filename}"
                    error = null
                    runCatching {
                        repository.download(
                            messageId = detail.messageId,
                            part = attachment.part,
                            filename = attachment.filename,
                            contentType = attachment.contentType,
                        )
                    }.onSuccess {
                        if (!openFile(context, it)) {
                            error = "附件已下载，但未找到可打开该文件的应用"
                        }
                    }
                        .onFailure { error = it.message ?: "下载附件失败" }
                    busyMessage = null
                }
            },
        )
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            PageActionRow {
                OutlinedButton(onClick = {
                    loadFolders()
                    loadMessages(0)
                }) { Text("刷新") }
                Button(onClick = { showCompose = true }) { Text("写信") }
            }
        }
        item {
            val folders = (foldersState as? LoadState.Data)?.value?.data?.folders.orEmpty()
            FolderSelector(
                folders = folders,
                value = selectedFolderId,
                onValueChange = {
                    selectedFolderId = it
                    start = 0
                    loadMessages(0, it)
                },
            )
        }
        when (val state = messagesState) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(state) }
            is LoadState.Data -> {
                item {
                    val data = state.value.data
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text("共 ${data.total} 封") })
                        AssistChip(onClick = {}, label = { Text("第 ${data.start + 1}-${data.start + data.messages.size} 封") })
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = start > 0,
                            onClick = { loadMessages((start - PAGE_SIZE).coerceAtLeast(0)) },
                        ) { Text("上一页") }
                        OutlinedButton(
                            enabled = (messagesState as? LoadState.Data)?.value?.data?.messages?.size == PAGE_SIZE,
                            onClick = { loadMessages(start + PAGE_SIZE) },
                        ) { Text("下一页") }
                    }
                }
                items(state.value.data.messages, key = { it.messageId }) { message ->
                    InfoCard(
                        title = message.subject.ifBlank { "(无主题)" },
                        subtitle = listOfNotNull(message.fromText.ifBlank { null }, message.receivedAt ?: message.sentAt)
                            .joinToString(" · "),
                        modifier = Modifier.clickable { loadDetail(message) },
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!message.read) AssistChip(onClick = {}, label = { Text("未读") })
                                if (message.attached) AssistChip(onClick = {}, label = { Text("附件") })
                            }
                        },
                    ) {
                        if (!message.summary.isNullOrBlank()) {
                            Text(message.summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderSelector(
    folders: List<MailFolder>,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = folders.firstOrNull { it.folderId == value }?.let { "${it.name} (${it.unreadCount}/${it.messageCount})" }
        ?: value
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("文件夹") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            folders.forEach { folder ->
                DropdownMenuItem(
                    text = { Text("${folder.name} (${folder.unreadCount}/${folder.messageCount})") },
                    onClick = {
                        expanded = false
                        onValueChange(folder.folderId)
                    },
                )
            }
        }
    }
}

@Composable
private fun MailDetailDialog(
    state: LoadState<ModuleEnvelope<MailMessageDetail>>,
    busyMessage: String?,
    error: String?,
    onDismiss: () -> Unit,
    onDelete: (MailMessageDetail) -> Unit,
    onDownload: (MailMessageDetail, MailAttachment) -> Unit,
) {
    val detail = (state as? LoadState.Data)?.value?.data
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = detail?.subject?.ifBlank { "(无主题)" } ?: "邮件详情",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (detail != null) {
                        TextButton(enabled = busyMessage == null, onClick = { onDelete(detail) }) {
                            Text("删除")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
                HorizontalDivider()
                when (state) {
                    LoadState.Loading, is LoadState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            LoadingOrError(state)
                            if (!busyMessage.isNullOrBlank()) {
                                Text(busyMessage)
                            }
                            if (!error.isNullOrBlank()) {
                                Text(error, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    is LoadState.Data -> {
                        MailDetailContent(
                            detail = state.value.data,
                            busyMessage = busyMessage,
                            error = error,
                            onDownload = onDownload,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MailDetailContent(
    detail: MailMessageDetail,
    busyMessage: String?,
    error: String?,
    onDownload: (MailMessageDetail, MailAttachment) -> Unit,
) {
    val context = LocalContext.current
    var bodyRenderMode by remember(detail.messageId) { mutableStateOf(MailBodyRenderMode.Mobile) }
    val bodyHtml = remember(detail.htmlContent, bodyRenderMode) {
        mailBodyHtml(detail.htmlContent, bodyRenderMode)
    }
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = bodyRenderMode == MailBodyRenderMode.Mobile,
                    onClick = { bodyRenderMode = MailBodyRenderMode.Mobile },
                    label = { Text("移动版") },
                )
                FilterChip(
                    selected = bodyRenderMode == MailBodyRenderMode.Original,
                    onClick = { bodyRenderMode = MailBodyRenderMode.Original },
                    label = { Text("原始版") },
                )
            }
            KeyValue("发件人", detail.fromList.joinToString(", ").ifBlank { detail.fromText })
            KeyValue("收件人", detail.toList.joinToString(", ").ifBlank { detail.toText })
            KeyValue("抄送", detail.ccList.joinToString(", "))
            KeyValue("时间", detail.receivedAt ?: detail.sentAt)
            if (detail.attachments.isNotEmpty()) {
                MailAttachmentsSection(
                    detail = detail,
                    busyMessage = busyMessage,
                    onDownload = onDownload,
                )
            } else if (detail.attached) {
                HorizontalDivider()
                Text("附件", style = MaterialTheme.typography.titleSmall)
                Text(
                    "邮件标记包含附件，但详情接口未返回可下载的附件列表。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!busyMessage.isNullOrBlank()) {
                Text(busyMessage)
            }
            if (!error.isNullOrBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
        HorizontalDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            MailBodyWebView(
                html = bodyHtml,
                modifier = Modifier.fillMaxSize(),
                onOpenExternalUrl = { url -> openMailExternalUrl(context, url) },
            )
        }
    }
}

@Composable
private fun MailBodyWebView(
    html: String,
    modifier: Modifier = Modifier,
    onOpenExternalUrl: (String) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportZoom(true)
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                webViewClient = MailBodyWebViewClient(onOpenExternalUrl)
            }
        },
        update = { webView ->
            val previousHtml = webView.tag as? String
            if (previousHtml != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
    )
}

private class MailBodyWebViewClient(
    private val onOpenExternalUrl: (String) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handleUri(request.url)

    private fun handleUri(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return true
        return when (scheme) {
            "http", "https" -> {
                onOpenExternalUrl(uri.toString())
                true
            }

            "about", "data" -> false
            else -> true
        }
    }
}

@Composable
private fun MailDetailDialogLegacy(
    state: LoadState<ModuleEnvelope<MailMessageDetail>>,
    busyMessage: String?,
    error: String?,
    onDismiss: () -> Unit,
    onDelete: (MailMessageDetail) -> Unit,
    onDownload: (MailMessageDetail, MailAttachment) -> Unit,
) {
    val detail = (state as? LoadState.Data)?.value?.data
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail?.subject?.ifBlank { "(无主题)" } ?: "邮件详情") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (state) {
                    LoadState.Loading -> LoadingOrError(state)
                    is LoadState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    is LoadState.Data -> {
                        val value = state.value.data
                        KeyValue("发件人", value.fromList.joinToString(", ").ifBlank { value.fromText })
                        KeyValue("收件人", value.toList.joinToString(", ").ifBlank { value.toText })
                        KeyValue("抄送", value.ccList.joinToString(", "))
                        KeyValue("时间", value.receivedAt ?: value.sentAt)
                        if (value.attachments.isNotEmpty()) {
                            MailAttachmentsSection(
                                detail = value,
                                busyMessage = busyMessage,
                                onDownload = onDownload,
                            )
                        } else if (value.attached) {
                            HorizontalDivider()
                            Text("附件", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "邮件标记包含附件，但详情接口未返回可下载的附件列表。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                            factory = { context ->
                                WebView(context).apply {
                                    settings.javaScriptEnabled = false
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    settings.domStorageEnabled = false
                                }
                            },
                            update = { webView ->
                                webView.loadDataWithBaseURL(null, value.htmlContent, "text/html", "UTF-8", null)
                            },
                        )
                    }
                }
                if (!busyMessage.isNullOrBlank()) Text(busyMessage)
                if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (detail != null) {
                TextButton(enabled = busyMessage == null, onClick = { onDelete(detail) }) {
                    Text("删除")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun MailAttachmentsSection(
    detail: MailMessageDetail,
    busyMessage: String?,
    onDownload: (MailMessageDetail, MailAttachment) -> Unit,
) {
    HorizontalDivider()
    Text("附件", style = MaterialTheme.typography.titleSmall)
    detail.attachments.forEach { attachment ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(attachment.filename, style = MaterialTheme.typography.bodyMedium)
                Text("${attachment.size} bytes", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                enabled = busyMessage == null,
                onClick = { onDownload(detail, attachment) },
            ) { Text("下载") }
        }
    }
}

@Composable
private fun ComposeMailDialog(
    repository: MailRepository,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var composeId by remember { mutableStateOf<String?>(null) }
    var toText by remember { mutableStateOf("") }
    var ccText by remember { mutableStateOf("") }
    var bccText by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<MailComposeAttachment>>(emptyList()) }
    var suggestions by remember { mutableStateOf<List<MailContactSuggestion>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "正在上传附件"
            error = null
            runCatching {
                var currentComposeId = composeId
                val uploaded = attachments.toMutableList()
                val files = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri -> readUploadFile(context, uri) }
                }
                files.forEach { file ->
                    val response = repository.uploadAttachment(currentComposeId, file)
                    currentComposeId = response.composeId
                    uploaded += MailComposeAttachment(
                        attachmentId = response.attachment.attachmentId,
                        filename = response.attachment.filename,
                        size = response.attachment.size,
                        contentType = response.attachment.contentType,
                    )
                }
                composeId = currentComposeId
                attachments = uploaded
            }.onFailure { error = it.message ?: "上传附件失败" }
            busy = null
        }
    }

    LaunchedEffect(toText) {
        val keyword = lastRecipientToken(toText)
        if (keyword.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        runCatching { repository.contacts(keyword, 8) }
            .onSuccess { suggestions = it.data.contacts }
            .onFailure { suggestions = emptyList() }
    }

    fun submit(saveDraft: Boolean) {
        scope.launch {
            busy = if (saveDraft) "正在保存草稿" else "正在发送"
            error = null
            val request = MailComposeRequest(
                composeId = composeId,
                to = splitRecipients(toText),
                cc = splitRecipients(ccText),
                bcc = splitRecipients(bccText),
                subject = subject,
                content = body,
                isHtml = false,
                attachments = attachments,
            )
            runCatching {
                if (saveDraft) repository.saveDraft(request) else repository.send(request)
            }.onSuccess {
                onSent()
            }.onFailure {
                error = it.message ?: if (saveDraft) "保存草稿失败" else "发送失败"
            }
            busy = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        title = { Text("写信") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        label = { Text("收件人") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (suggestions.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            suggestions.forEach { contact ->
                                AssistChip(
                                    onClick = { toText = replaceLastRecipientToken(toText, contact.email.ifBlank { contact.displayName }) },
                                    label = { Text(listOf(contact.displayName, contact.email).filter { it.isNotBlank() }.distinct().joinToString(" · ")) },
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = ccText,
                        onValueChange = { ccText = it },
                        label = { Text("抄送") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = bccText,
                        onValueChange = { bccText = it },
                        label = { Text("密送") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = subject,
                        onValueChange = { subject = it },
                        label = { Text("主题") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text("正文") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        minLines = 5,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = busy == null,
                            onClick = { filePicker.launch(arrayOf("*/*")) },
                        ) { Text("添加附件") }
                        AssistChip(onClick = {}, label = { Text("${attachments.size} 个附件") })
                    }
                }
                if (attachments.isNotEmpty()) {
                    items(attachments, key = { it.attachmentId }) { attachment ->
                        Text("${attachment.filename} · ${attachment.size} bytes", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!busy.isNullOrBlank()) item { Text(busy.orEmpty()) }
                if (!error.isNullOrBlank()) item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = busy == null && splitRecipients(toText).isNotEmpty(),
                onClick = { submit(saveDraft = false) },
            ) { Text("发送") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = busy == null, onClick = { submit(saveDraft = true) }) {
                    Text("存草稿")
                }
                TextButton(enabled = busy == null, onClick = onDismiss) {
                    Text("取消")
                }
            }
        },
    )
}

private fun splitRecipients(value: String): List<String> =
    value.split(',', ';', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }

private fun lastRecipientToken(value: String): String =
    value.split(',', ';', '\n').lastOrNull()?.trim().orEmpty()

private fun replaceLastRecipientToken(value: String, replacement: String): String {
    val delimiter = Regex("""[,;\n]""").findAll(value).lastOrNull()
    return if (delimiter == null) {
        "$replacement, "
    } else {
        value.take(delimiter.range.first + 1) + " $replacement, "
    }
}

private fun openMailExternalUrl(context: Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    return runCatching { context.startActivity(Intent.createChooser(intent, "打开链接")) }.isSuccess
}

private fun readUploadFile(context: Context, uri: Uri): MailUploadFile? {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.takeIf { it.isNotBlank() } ?: "attachment"
    val type = resolver.getType(uri)
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return MailUploadFile(name, bytes, type)
}
