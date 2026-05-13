package cn.edu.bjtu.mis.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.edu.bjtu.mis.data.agent.model.AgentSettings
import cn.edu.bjtu.mis.data.agent.repository.AgentRepository
import cn.edu.bjtu.mis.data.agent.repository.AgentSecretStore
import cn.edu.bjtu.mis.data.agent.repository.AgentSettingsStore
import cn.edu.bjtu.mis.data.agent.runtime.RuntimeManager
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.SectionTitle
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AgentScreen(
    repository: AgentRepository,
    settingsStore: AgentSettingsStore,
    secretStore: AgentSecretStore,
    runtimeManager: RuntimeManager,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks by repository.observeTasks().collectAsState(initial = emptyList())
    var capabilities by remember { mutableStateOf<List<cn.edu.bjtu.mis.data.agent.model.RuntimeCapability>>(emptyList()) }

    LaunchedEffect(Unit) {
        capabilities = runtimeManager.capabilities()
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionTitle(
                title = "作业助手",
                subtitle = "配置 OpenAI-compatible API，查看 Agent 任务和运行环境。",
            )
        }
        item {
            AgentConfigCard(
                settingsStore = settingsStore,
                secretStore = secretStore,
            )
        }
        item {
            InfoCard("运行能力") {
                capabilities.forEach { capability ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        AssistChip(onClick = {}, label = { Text(capability.name) })
                        FilterChip(
                            selected = capability.status.name == "Available",
                            onClick = {},
                            label = { Text(capability.status.name) },
                        )
                    }
                    if (capability.limitations.isNotEmpty()) {
                        Text(capability.limitations.joinToString(" · "))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            repository.clearAll()
                            File(context.filesDir, "agent-workspaces").deleteRecursively()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清理 Agent 缓存")
                }
            }
        }
        if (tasks.isEmpty()) {
            item {
                InfoCard("暂无 Agent 任务") {
                    Text("在作业模块的具体作业卡片中点击 Agent 协助开始。")
                }
            }
        } else {
            items(tasks, key = { it.id }) { task ->
                InfoCard(task.title ?: task.id, subtitle = task.updatedAt) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text(task.status) })
                        task.sourceKind?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                    if (!task.finalAnswer.isNullOrBlank()) {
                        Text(task.finalAnswer.take(220), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    if (!task.errorMessage.isNullOrBlank()) {
                        Text(task.errorMessage)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { shareResults(context, task.id) }) {
                            Text("导出结果")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AgentConfigDialog(
    settingsStore: AgentSettingsStore,
    secretStore: AgentSecretStore,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 Agent") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AgentConfigForm(
                    settingsStore = settingsStore,
                    secretStore = secretStore,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun AgentConfigCard(
    settingsStore: AgentSettingsStore,
    secretStore: AgentSecretStore,
) {
    InfoCard("模型配置") {
        AgentConfigForm(
            settingsStore = settingsStore,
            secretStore = secretStore,
        )
    }
}

@Composable
private fun AgentConfigForm(
    settingsStore: AgentSettingsStore,
    secretStore: AgentSecretStore,
) {
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settings.collectAsState(initial = AgentSettings())
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var textModel by remember { mutableStateOf(settings.textModel) }
    var visionModel by remember { mutableStateOf(settings.visionModel.orEmpty()) }
    var timeout by remember { mutableStateOf(settings.requestTimeoutSeconds.toString()) }
    var maxSteps by remember { mutableStateOf(settings.maxSteps.toString()) }
    var temperature by remember { mutableStateOf(settings.temperature.toString()) }
    var apiKey by remember { mutableStateOf("") }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings) {
        baseUrl = settings.baseUrl
        textModel = settings.textModel
        visionModel = settings.visionModel.orEmpty()
        timeout = settings.requestTimeoutSeconds.toString()
        maxSteps = settings.maxSteps.toString()
        temperature = settings.temperature.toString()
    }

    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(textModel, { textModel = it }, label = { Text("Text model") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(visionModel, { visionModel = it }, label = { Text("Vision model（可选）") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(timeout, { timeout = it }, label = { Text("Timeout") }, modifier = Modifier.weight(1f))
        OutlinedTextField(maxSteps, { maxSteps = it }, label = { Text("Max steps") }, modifier = Modifier.weight(1f))
        OutlinedTextField(temperature, { temperature = it }, label = { Text("Temperature") }, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = {
            scope.launch {
                settingsStore.save(
                    AgentSettings(
                        baseUrl = baseUrl,
                        textModel = textModel,
                        visionModel = visionModel.takeIf { it.isNotBlank() },
                        requestTimeoutSeconds = timeout.toIntOrNull() ?: settings.requestTimeoutSeconds,
                        temperature = temperature.toDoubleOrNull() ?: settings.temperature,
                        maxSteps = maxSteps.toIntOrNull() ?: settings.maxSteps,
                    )
                )
                if (apiKey.isNotBlank()) {
                    secretStore.saveApiKey(apiKey)
                    apiKey = ""
                }
                saveMessage = "已保存 Agent 配置"
            }
        }) {
            Text("保存配置")
        }
        OutlinedButton(onClick = {
            secretStore.clearApiKey()
            saveMessage = "已清除 API Key"
        }) {
            Text("清除 Key")
        }
    }
    saveMessage?.let { Text(it) }
}

@Composable
fun AgentTaskDialog(
    homework: cn.edu.bjtu.mis.model.HomeworkItem,
    repository: AgentRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var instruction by remember { mutableStateOf("") }
    var selectedUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var taskId by remember { mutableStateOf<String?>(null) }
    var followUp by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> selectedUris = uris }
    val messages by (taskId?.let { repository.observeMessages(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
    val steps by (taskId?.let { repository.observeSteps(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
    val task by (taskId?.let { repository.observeTask(it) } ?: kotlinx.coroutines.flow.flowOf(null)).collectAsState(initial = null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent 协助：${homework.title}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                KeyValue("课程", homework.course)
                KeyValue("截止", homework.dueAt)
                Text(homework.requirementText ?: homework.contentExcerpt ?: "暂无作业要求")
                if (taskId == null) {
                    OutlinedTextField(
                        value = instruction,
                        onValueChange = { instruction = it },
                        label = { Text("补充要求") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (selectedUris.isEmpty()) "选择给 Agent 的附件" else "已选择 ${selectedUris.size} 个附件")
                    }
                    error?.let { Text(it) }
                } else {
                    task?.let {
                        AssistChip(onClick = {}, label = { Text(it.status) })
                    }
                    if (steps.isNotEmpty()) {
                        Text("步骤")
                        steps.forEach { step ->
                            Text("${step.stepIndex + 1}. ${step.toolName} · ${step.status}")
                        }
                    }
                    Text("对话")
                    messages.filter { it.role != "system" }.forEach { message ->
                        Text("${message.role}: ${message.content.ifBlank { "…" }}")
                    }
                    OutlinedTextField(
                        value = followUp,
                        onValueChange = { followUp = it },
                        label = { Text("追加修改意见") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            when (val currentTaskId = taskId) {
                null -> Button(onClick = {
                    scope.launch {
                        runCatching {
                            repository.createHomeworkTask(homework, instruction, selectedUris)
                        }.onSuccess {
                            taskId = it
                            cn.edu.bjtu.mis.data.agent.service.AgentForegroundService.start(context, it)
                        }.onFailure {
                            error = it.message ?: "创建 Agent 任务失败"
                        }
                    }
                }) {
                    Text("启动 Agent")
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = followUp.isNotBlank(),
                        onClick = {
                            scope.launch {
                                repository.appendUserMessage(currentTaskId, followUp)
                                followUp = ""
                            }
                        },
                    ) {
                        Text("发送")
                    }
                    OutlinedButton(onClick = { shareResults(context, currentTaskId) }) {
                        Text("导出")
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                taskId?.let { currentTaskId ->
                    TextButton(onClick = {
                        scope.launch {
                            repository.cancel(currentTaskId)
                            cn.edu.bjtu.mis.data.agent.service.AgentForegroundService.stop(context, currentTaskId)
                        }
                    }) {
                        Text("取消任务")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        },
    )
}

private fun shareResults(context: Context, taskId: String) {
    val zip = File(context.filesDir, "agent-workspaces/$taskId/results.zip")
    if (!zip.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "导出 Agent 结果"))
}
