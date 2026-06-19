package cn.edu.bjtu.mis.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.edu.bjtu.mis.data.course.CourseSelectionForegroundService
import cn.edu.bjtu.mis.data.course.CourseSelectionRunner
import cn.edu.bjtu.mis.data.repository.CourseSelectionRepository
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.model.CourseSelectionCourse
import cn.edu.bjtu.mis.model.CourseSelectionData
import cn.edu.bjtu.mis.model.CourseSelectionReplaceRule
import cn.edu.bjtu.mis.model.CourseSelectionRunConfig
import cn.edu.bjtu.mis.model.CourseSelectionTarget
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.SectionTitle
import kotlinx.coroutines.launch

@Composable
fun CourseSelectionScreen(
    repository: CourseSelectionRepository,
    runner: CourseSelectionRunner,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runState by runner.state.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<LoadState<ModuleEnvelope<CourseSelectionData>>>(LoadState.Loading) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var replaceTargetKey by remember { mutableStateOf<String?>(null) }
    var replaceDropKey by remember { mutableStateOf<String?>(null) }
    var replaceRules by remember { mutableStateOf<List<CourseSelectionReplaceRule>>(emptyList()) }
    var retryInterval by remember { mutableStateOf("2") }
    var maxRetries by remember { mutableStateOf("100") }
    var captchaText by remember { mutableStateOf("") }
    var uiError by remember { mutableStateOf<String?>(null) }
    var pendingConfig by remember { mutableStateOf<CourseSelectionRunConfig?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val config = pendingConfig
        pendingConfig = null
        if (granted && config != null) {
            runCatching { CourseSelectionForegroundService.start(context, config) }
                .onSuccess { uiError = null }
                .onFailure { uiError = it.message ?: "抢课后台服务启动失败" }
        } else {
            uiError = "需要通知权限才能在后台持续显示抢课状态并提醒验证码。"
        }
    }

    fun load(strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst) {
        scope.launch {
            state = LoadState.Loading
            runCatching { repository.listing(strategy) }
                .onSuccess { state = LoadState.Data(it) }
                .onFailure { state = LoadState.Error(it.message ?: "课程列表加载失败") }
        }
    }

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun startSelecting(courses: List<CourseSelectionCourse>, rules: List<CourseSelectionReplaceRule>) {
        if (runState.running || (courses.isEmpty() && rules.isEmpty())) return
        val rounds = maxRetries.toIntOrNull()?.coerceAtLeast(1) ?: 100
        val intervalMs = ((retryInterval.toDoubleOrNull() ?: 2.0).coerceAtLeast(0.0) * 1000).toLong()
        val config = CourseSelectionRunConfig(
            targets = courses.map { CourseSelectionTarget(it.key, it.courseName) },
            replaceRules = rules,
            retryIntervalMillis = intervalMs,
            maxRounds = rounds,
        )
        if (!hasNotificationPermission()) {
            pendingConfig = config
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        runCatching { CourseSelectionForegroundService.start(context, config) }
            .onSuccess { uiError = null }
            .onFailure { uiError = it.message ?: "抢课后台服务启动失败" }
    }

    LaunchedEffect(Unit) { load(initialLoadStrategy) }
    LaunchedEffect(runState.completed) {
        if (runState.completed) load()
    }
    LaunchedEffect(runState.awaitingCaptcha?.challengeId) {
        captchaText = ""
    }

    val chosenCourses = (state as? LoadState.Data)?.value?.data?.availableCourses.orEmpty().filter { it.key in selectedKeys }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionTitle(
                title = "抢课",
                subtitle = "从 AA 选课页读取可选课程，支持普通多门抢课和高级换课规则。",
                trailing = {
                    Button(enabled = !runState.running, onClick = ::load) {
                        Text("刷新")
                    }
                },
            )
        }
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
            is LoadState.Data -> {
                val currentData = current.value.data
                item {
                    InfoCard("控制") {
                        if (!currentData.submitError.isNullOrBlank()) {
                            Text(currentData.submitError, color = MaterialTheme.colorScheme.error)
                        }
                        uiError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        runState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (runState.awaitingCaptcha != null) {
                            Text("正在等待验证码，抢课任务已暂停。", color = MaterialTheme.colorScheme.primary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = retryInterval,
                                onValueChange = { retryInterval = it },
                                label = { Text("间隔秒") },
                                enabled = !runState.running,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = maxRetries,
                                onValueChange = { maxRetries = it },
                                label = { Text("最大轮数") },
                                enabled = !runState.running,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                enabled = !runState.running && (chosenCourses.isNotEmpty() || replaceRules.isNotEmpty()) && currentData.canSubmit,
                                onClick = { startSelecting(chosenCourses, replaceRules) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (runState.running) "抢课中" else "开始抢课")
                            }
                            OutlinedButton(
                                enabled = runState.running,
                                onClick = { CourseSelectionForegroundService.stop(context) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(if (runState.stopping) "停止中" else "停止")
                            }
                        }
                    }
                }
                item {
                    ReplaceRulesCard(
                        availableCourses = currentData.availableCourses,
                        selectedCourses = currentData.selectedCourses,
                        targetKey = replaceTargetKey,
                        dropKey = replaceDropKey,
                        rules = replaceRules,
                        doneRuleIds = runState.doneReplaceRuleIds,
                        enabled = !runState.running,
                        onTargetSelected = { replaceTargetKey = it },
                        onDropSelected = { replaceDropKey = it },
                        onAddRule = {
                            val target = currentData.availableCourses.firstOrNull { it.key == replaceTargetKey }
                            val drop = currentData.selectedCourses.firstOrNull { it.key == replaceDropKey }
                            if (target != null && drop != null) {
                                val id = "${target.key}->${drop.key}"
                                if (replaceRules.none { it.id == id }) {
                                    replaceRules = replaceRules + CourseSelectionReplaceRule(
                                        id = id,
                                        target = CourseSelectionTarget(target.key, target.courseName),
                                        drop = CourseSelectionTarget(drop.key, drop.courseName),
                                    )
                                }
                            }
                        },
                        onRemoveRule = { id -> replaceRules = replaceRules.filterNot { it.id == id } },
                        onClearRules = { replaceRules = emptyList() },
                    )
                }
                items(currentData.availableCourses, key = { it.key }) { course ->
                    CourseSelectionCourseCard(
                        course = course,
                        checked = course.key in selectedKeys,
                        done = course.key in runState.doneKeys,
                        enabled = !runState.running && course.key !in runState.doneKeys,
                        onCheckedChange = { checked ->
                            selectedKeys = if (checked) selectedKeys + course.key else selectedKeys - course.key
                        },
                    )
                }
                item {
                    InfoCard("已选课程") {
                        if (currentData.selectedCourses.isEmpty()) {
                            Text("当前没有读取到已选课程。")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                currentData.selectedCourses.forEach { course ->
                                    FilterChip(selected = true, onClick = {}, label = { Text(course.courseName) })
                                }
                            }
                        }
                    }
                }
                item {
                    InfoCard("运行日志") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 260.dp)) {
                            if (runState.logs.isEmpty()) {
                                Text("暂无运行日志。", style = MaterialTheme.typography.bodySmall)
                            } else {
                                runState.logs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    }

    runState.awaitingCaptcha?.let { challenge ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("输入验证码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    runState.awaitingCaptchaCourse?.let { Text(it.courseName, style = MaterialTheme.typography.bodyMedium) }
                    challenge.prompt?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    CaptchaImage(challenge.imageDataUrl)
                    OutlinedTextField(
                        value = captchaText,
                        onValueChange = { captchaText = it },
                        label = { Text("验证码") },
                        singleLine = true,
                        enabled = !runState.captchaSubmitting,
                    )
                    runState.captchaError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = captchaText.isNotBlank() && !runState.captchaSubmitting,
                    onClick = {
                        CourseSelectionForegroundService.submitCaptcha(context, captchaText)
                    },
                ) {
                    Text(if (runState.captchaSubmitting) "提交中" else "提交")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !runState.captchaSubmitting,
                    onClick = {
                        CourseSelectionForegroundService.cancelCaptcha(context)
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ReplaceRulesCard(
    availableCourses: List<CourseSelectionCourse>,
    selectedCourses: List<CourseSelectionCourse>,
    targetKey: String?,
    dropKey: String?,
    rules: List<CourseSelectionReplaceRule>,
    doneRuleIds: Set<String>,
    enabled: Boolean,
    onTargetSelected: (String) -> Unit,
    onDropSelected: (String) -> Unit,
    onAddRule: () -> Unit,
    onRemoveRule: (String) -> Unit,
    onClearRules: () -> Unit,
) {
    InfoCard("高级换课规则") {
        Text("目标课程 A")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            availableCourses.forEach { course ->
                FilterChip(
                    selected = course.key == targetKey,
                    enabled = enabled,
                    onClick = { onTargetSelected(course.key) },
                    label = { Text("${course.courseName} / 余量 ${course.remainingText ?: course.remaining ?: "-"}") },
                )
            }
        }
        Text("要退课程 B")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedCourses.isEmpty()) {
                Text("当前没有可用于换课的已选课程。", style = MaterialTheme.typography.bodySmall)
            } else {
                selectedCourses.forEach { course ->
                    FilterChip(
                        selected = course.key == dropKey,
                        enabled = enabled,
                        onClick = { onDropSelected(course.key) },
                        label = { Text(course.courseName) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = enabled && targetKey != null && dropKey != null,
                onClick = onAddRule,
                modifier = Modifier.weight(1f),
            ) {
                Text("添加规则")
            }
            OutlinedButton(
                enabled = enabled && rules.isNotEmpty(),
                onClick = onClearRules,
                modifier = Modifier.weight(1f),
            ) {
                Text("清空")
            }
        }
        if (rules.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rules.forEach { rule ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${if (rule.id in doneRuleIds) "已完成" else "待执行"}：${rule.drop.courseName} -> ${rule.target.courseName}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(enabled = enabled, onClick = { onRemoveRule(rule.id) }) {
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseSelectionCourseCard(
    course: CourseSelectionCourse,
    checked: Boolean,
    done: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    InfoCard(
        title = course.courseName,
        subtitle = listOfNotNull(course.teacher, course.timeLocation).joinToString(" · "),
        trailing = {
            Checkbox(checked = checked || done, enabled = enabled, onCheckedChange = onCheckedChange)
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("状态", if (done) "已完成" else course.status, Modifier.weight(1f))
            KeyValue("余量", course.remainingText ?: course.remaining?.toString(), Modifier.weight(1f))
            KeyValue("学分", course.credit, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("类型", course.courseType, Modifier.weight(1f))
            KeyValue("考核", course.examType, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CaptchaImage(dataUrl: String) {
    val base64 = dataUrl.substringAfter("base64,", "")
    val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
    val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "验证码")
}
