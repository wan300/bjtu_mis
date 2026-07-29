package cn.edu.bjtu.mis.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
import cn.edu.bjtu.mis.model.DefaultCourseSelectionGroupNames
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.theme.AppHapticEvent
import cn.edu.bjtu.mis.ui.theme.LocalAppHaptics
import cn.edu.bjtu.mis.ui.components.PageActionRow
import kotlinx.coroutines.launch

private enum class CourseSelectionAvailabilityFilter {
    All,
    HasRemaining,
    Full,
    Checked,
}

private const val CourseSelectionPageSize = 20

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CourseSelectionScreen(
    repository: CourseSelectionRepository,
    runner: CourseSelectionRunner,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val captchaFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val haptics = LocalAppHaptics.current
    val runState by runner.state.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<LoadState<ModuleEnvelope<CourseSelectionData>>>(LoadState.Loading) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var replaceTargetKey by remember { mutableStateOf<String?>(null) }
    var replaceDropKey by remember { mutableStateOf<String?>(null) }
    var replaceRules by remember { mutableStateOf<List<CourseSelectionReplaceRule>>(emptyList()) }
    var retryInterval by remember { mutableStateOf("2") }
    var maxRetries by remember { mutableStateOf("100") }
    var courseGroupName by remember { mutableStateOf<String?>(null) }
    var courseSearchText by remember { mutableStateOf("") }
    var courseSectionText by remember { mutableStateOf("") }
    var courseListFilter by remember { mutableStateOf(CourseSelectionAvailabilityFilter.All) }
    var coursePage by remember { mutableStateOf(0) }
    var courseRemoteFilterActive by remember { mutableStateOf(false) }
    var captchaText by remember { mutableStateOf("") }
    var uiError by remember { mutableStateOf<String?>(null) }
    var pendingConfig by remember { mutableStateOf<CourseSelectionRunConfig?>(null) }
    var showStartConfirmation by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val config = pendingConfig
        pendingConfig = null
        if (granted && config != null) {
            runCatching { CourseSelectionForegroundService.start(context, config) }
                .onSuccess {
                    uiError = null
                    haptics.perform(AppHapticEvent.Success)
                }
                .onFailure {
                    uiError = it.message ?: "抢课后台服务启动失败"
                    haptics.perform(AppHapticEvent.Error)
                }
        } else {
            uiError = "需要通知权限才能在后台持续显示抢课状态并提醒验证码。"
        }
    }

    fun load(strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst) {
        scope.launch {
            courseRemoteFilterActive = false
            state = LoadState.Loading
            runCatching { repository.listing(strategy) }
                .onSuccess { state = LoadState.Data(it) }
                .onFailure { state = LoadState.Error(it.message ?: "课程列表加载失败") }
        }
    }

    fun loadQuery(
        groupName: String?,
        courseQuery: String,
        sectionQuery: String,
        fallback: ModuleEnvelope<CourseSelectionData>,
    ) {
        val requestedGroupName = groupName?.trim()?.takeIf { it.isNotBlank() }
        scope.launch {
            state = LoadState.Loading
            runCatching {
                repository.listingQuery(
                    groupName = requestedGroupName,
                    courseQuery = courseQuery,
                    sectionQuery = sectionQuery,
                )
            }
                .onSuccess {
                    courseRemoteFilterActive = true
                    uiError = null
                    state = LoadState.Data(it)
                }
                .onFailure {
                    courseRemoteFilterActive = false
                    if (requestedGroupName != null && courseGroupName == requestedGroupName) courseGroupName = null
                    uiError = it.message ?: "课组课程加载失败"
                    state = LoadState.Data(fallback)
                }
        }
    }

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun startSelecting(courses: List<CourseSelectionCourse>, rules: List<CourseSelectionReplaceRule>) {
        if (runState.running || (courses.isEmpty() && rules.isEmpty())) return
        val rounds = maxRetries.toIntOrNull()?.coerceAtLeast(1) ?: 100
        val intervalMs = ((retryInterval.toDoubleOrNull() ?: 2.0).coerceAtLeast(0.0) * 1000).toLong()
        val remoteCourseQuery = if (courseRemoteFilterActive) courseSearchText.trim() else ""
        val remoteSectionQuery = if (courseRemoteFilterActive) courseSectionText.trim() else ""
        val config = CourseSelectionRunConfig(
            targets = courses.map {
                CourseSelectionTarget(
                    key = it.key,
                    courseName = it.courseName,
                    groupName = it.groupName?.trim()?.takeIf { groupName -> groupName.isNotBlank() }
                        ?: courseGroupName?.trim()?.takeIf { groupName -> groupName.isNotBlank() },
                    courseQuery = remoteCourseQuery,
                    sectionQuery = remoteSectionQuery,
                )
            },
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
            .onSuccess {
                uiError = null
                haptics.perform(AppHapticEvent.Success)
            }
            .onFailure {
                uiError = it.message ?: "抢课后台服务启动失败"
                haptics.perform(AppHapticEvent.Error)
            }
    }

    fun submitCaptcha() {
        val captcha = captchaText.trim()
        if (captcha.isBlank() || runState.captchaSubmitting) return
        CourseSelectionForegroundService.submitCaptcha(context, captcha)
    }

    LaunchedEffect(Unit) { load(initialLoadStrategy) }
    LaunchedEffect(runState.completed) {
        if (runState.completed) load()
    }
    LaunchedEffect(runState.awaitingCaptcha?.challengeId) {
        captchaText = ""
        if (runState.awaitingCaptcha != null) {
            runCatching { captchaFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    val chosenCourses = (state as? LoadState.Data)?.value?.data?.availableCourses.orEmpty()
        .filter { it.key in selectedKeys && it.remaining != 0 }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            PageActionRow {
                Button(enabled = !runState.running, onClick = ::load) {
                    Text("刷新")
                }
            }
        }
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
            is LoadState.Data -> {
                val currentData = current.value.data
                val courseGroupNames = courseSelectionGroupNames(currentData)
                val effectiveCourseGroupName = courseGroupName?.takeIf { it in courseGroupNames }
                val filteredCourses = filteredCourseSelectionCourses(
                    courses = currentData.availableCourses,
                    groupName = effectiveCourseGroupName,
                    query = courseSearchText,
                    sectionQuery = courseSectionText,
                    filter = courseListFilter,
                    selectedKeys = selectedKeys,
                )
                val totalCoursePages = courseSelectionTotalPages(filteredCourses.size)
                val coursePageIndex = if (totalCoursePages == 0) {
                    0
                } else {
                    coursePage.coerceIn(0, totalCoursePages - 1)
                }
                val pagedCourses = filteredCourses
                    .drop(coursePageIndex * CourseSelectionPageSize)
                    .take(CourseSelectionPageSize)
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
                                onClick = { showStartConfirmation = true },
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
                    CourseSelectionTableSummary(
                        data = currentData,
                        selectedCount = chosenCourses.size,
                    )
                }
                item {
                    CourseSelectionSearchPanel(
                        groupNames = courseGroupNames,
                        selectedGroupName = effectiveCourseGroupName,
                        query = courseSearchText,
                        sectionQuery = courseSectionText,
                        filter = courseListFilter,
                        resultCount = filteredCourses.size,
                        totalCount = currentData.availableCourses.size,
                        onGroupChange = {
                            courseGroupName = it
                            coursePage = 0
                            if (it == null) {
                                if (courseSearchText.isNotBlank() || courseSectionText.isNotBlank()) {
                                    loadQuery(null, courseSearchText, courseSectionText, current.value)
                                } else if (courseRemoteFilterActive) {
                                    load()
                                } else {
                                    courseRemoteFilterActive = false
                                }
                            } else {
                                loadQuery(it, courseSearchText, courseSectionText, current.value)
                            }
                        },
                        onQueryChange = {
                            courseSearchText = it
                            coursePage = 0
                        },
                        onSectionQueryChange = {
                            courseSectionText = it
                            coursePage = 0
                        },
                        onFilterChange = {
                            courseListFilter = it
                            coursePage = 0
                        },
                        onSubmitQuery = {
                            loadQuery(effectiveCourseGroupName, courseSearchText, courseSectionText, current.value)
                        },
                        onClear = {
                            val shouldReload = courseRemoteFilterActive
                            courseGroupName = null
                            courseSearchText = ""
                            courseSectionText = ""
                            courseListFilter = CourseSelectionAvailabilityFilter.All
                            coursePage = 0
                            courseRemoteFilterActive = false
                            if (shouldReload) load()
                        },
                    )
                }
                if (filteredCourses.isNotEmpty()) {
                    item { CourseSelectionTableHeader() }
                    itemsIndexed(pagedCourses, key = { _, course -> course.key }) { index, course ->
                        CourseSelectionCourseRow(
                            index = coursePageIndex * CourseSelectionPageSize + index + 1,
                            course = course,
                            checked = course.key in selectedKeys,
                            done = course.key in runState.doneKeys,
                            enabled = !runState.running && course.key !in runState.doneKeys && course.remaining != 0,
                            onCheckedChange = { checked ->
                                selectedKeys = if (checked) selectedKeys + course.key else selectedKeys - course.key
                            },
                        )
                    }
                    item {
                        CourseSelectionPaginationControls(
                            pageIndex = coursePageIndex,
                            totalPages = totalCoursePages,
                            totalCount = filteredCourses.size,
                            onPageChange = { coursePage = it },
                        )
                    }
                } else {
                    item {
                        Text("没有符合筛选条件的课程。", style = MaterialTheme.typography.bodyMedium)
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
                                        target = CourseSelectionTarget(
                                            key = target.key,
                                            courseName = target.courseName,
                                            groupName = target.groupName?.trim()?.takeIf { groupName -> groupName.isNotBlank() }
                                                ?: courseGroupName?.trim()?.takeIf { groupName -> groupName.isNotBlank() },
                                            courseQuery = if (courseRemoteFilterActive) courseSearchText.trim() else "",
                                            sectionQuery = if (courseRemoteFilterActive) courseSectionText.trim() else "",
                                        ),
                                        drop = CourseSelectionTarget(drop.key, drop.courseName),
                                    )
                                }
                            }
                        },
                        onRemoveRule = { id -> replaceRules = replaceRules.filterNot { it.id == id } },
                        onClearRules = { replaceRules = emptyList() },
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

    if (showStartConfirmation) {
        CourseSelectionStartConfirmationDialog(
            courseCount = chosenCourses.size,
            replacementCount = replaceRules.size,
            onDismiss = { showStartConfirmation = false },
            onConfirm = {
                showStartConfirmation = false
                haptics.perform(AppHapticEvent.Commit)
                startSelecting(chosenCourses, replaceRules)
            },
        )
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
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitCaptcha() }),
                        modifier = Modifier.focusRequester(captchaFocusRequester),
                    )
                    runState.captchaError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = captchaText.isNotBlank() && !runState.captchaSubmitting,
                    onClick = { submitCaptcha() },
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
private fun CourseSelectionSearchPanel(
    groupNames: List<String>,
    selectedGroupName: String?,
    query: String,
    sectionQuery: String,
    filter: CourseSelectionAvailabilityFilter,
    resultCount: Int,
    totalCount: Int,
    onGroupChange: (String?) -> Unit,
    onQueryChange: (String) -> Unit,
    onSectionQueryChange: (String) -> Unit,
    onFilterChange: (CourseSelectionAvailabilityFilter) -> Unit,
    onSubmitQuery: () -> Unit,
    onClear: () -> Unit,
) {
    InfoCard("查询筛选", subtitle = "显示 $resultCount / $totalCount 门课程") {
        CourseSelectionGroupDropdown(
            groupNames = groupNames,
            selectedGroupName = selectedGroupName,
            enabled = groupNames.isNotEmpty(),
            onGroupChange = onGroupChange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("课程号/名称") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitQuery() }),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = sectionQuery,
                onValueChange = onSectionQueryChange,
                label = { Text("课序号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitQuery() }),
                modifier = Modifier.width(132.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CourseSelectionFilterChip("全部", filter == CourseSelectionAvailabilityFilter.All) {
                onFilterChange(CourseSelectionAvailabilityFilter.All)
            }
            CourseSelectionFilterChip("有余量", filter == CourseSelectionAvailabilityFilter.HasRemaining) {
                onFilterChange(CourseSelectionAvailabilityFilter.HasRemaining)
            }
            CourseSelectionFilterChip("无余量", filter == CourseSelectionAvailabilityFilter.Full) {
                onFilterChange(CourseSelectionAvailabilityFilter.Full)
            }
            CourseSelectionFilterChip("已勾选", filter == CourseSelectionAvailabilityFilter.Checked) {
                onFilterChange(CourseSelectionAvailabilityFilter.Checked)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSubmitQuery,
                modifier = Modifier.weight(1f),
            ) {
                Text("查询")
            }
            OutlinedButton(
                enabled = selectedGroupName != null ||
                    query.isNotBlank() ||
                    sectionQuery.isNotBlank() ||
                    filter != CourseSelectionAvailabilityFilter.All,
                onClick = onClear,
                modifier = Modifier.weight(1f),
            ) {
                Text("清空")
            }
        }
    }
}

@Composable
private fun CourseSelectionGroupDropdown(
    groupNames: List<String>,
    selectedGroupName: String?,
    enabled: Boolean,
    onGroupChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            enabled = enabled,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                selectedGroupName ?: "-- 课组名称 --",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("-- 课组名称 --") },
                onClick = {
                    onGroupChange(null)
                    expanded = false
                },
            )
            groupNames.forEach { groupName ->
                DropdownMenuItem(
                    text = { Text(groupName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onGroupChange(groupName)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CourseSelectionFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
internal fun CourseSelectionStartConfirmationDialog(
    courseCount: Int,
    replacementCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认开始抢课") },
        text = {
            Text(
                buildString {
                    append("将持续尝试选择 ")
                    append(courseCount)
                    append(" 门课程")
                    if (replacementCount > 0) {
                        append("，并执行 ")
                        append(replacementCount)
                        append(" 条换课规则；换课成功时会退掉原课程")
                    }
                    append("。请确认课程、重试间隔和最大轮数无误。")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认开始")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("返回检查")
            }
        },
    )
}

@Composable
private fun CourseSelectionTableSummary(
    data: CourseSelectionData,
    selectedCount: Int,
) {
    val selectableCount = data.availableCourses.count { it.remaining == null || it.remaining > 0 }
    InfoCard(
        title = "可选课程清单",
        subtitle = "可选 $selectableCount 门 / 已勾选 $selectedCount 门 / 已选 ${data.selectedCourses.size} 门",
    ) {
        if (data.availableCourses.isEmpty()) {
            Text("当前没有读取到可选课程。", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                KeyValue("课程数", data.availableCourses.size.toString(), Modifier.weight(1f))
                KeyValue("有余量", selectableCount.toString(), Modifier.weight(1f))
                KeyValue("已勾选", selectedCount.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CourseSelectionTableHeader() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CourseSelectionHeaderText("选择", Modifier.width(52.dp))
            CourseSelectionHeaderText("序号", Modifier.width(42.dp))
            CourseSelectionHeaderText("课程", Modifier.weight(1f))
            CourseSelectionHeaderText("余量", Modifier.width(58.dp))
            CourseSelectionHeaderText("状态", Modifier.width(72.dp))
        }
    }
}

@Composable
private fun CourseSelectionPaginationControls(
    pageIndex: Int,
    totalPages: Int,
    totalCount: Int,
    onPageChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(
                enabled = pageIndex > 0,
                onClick = { onPageChange(0) },
                modifier = Modifier.weight(1f),
            ) {
                Text("首页", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(
                enabled = pageIndex > 0,
                onClick = { onPageChange(pageIndex - 1) },
                modifier = Modifier.weight(1f),
            ) {
                Text("上一页", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(
                enabled = pageIndex < totalPages - 1,
                onClick = { onPageChange(pageIndex + 1) },
                modifier = Modifier.weight(1f),
            ) {
                Text("下一页", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(
                enabled = pageIndex < totalPages - 1,
                onClick = { onPageChange(totalPages - 1) },
                modifier = Modifier.weight(1f),
            ) {
                Text("末页", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(
            text = "页次：${pageIndex + 1}/$totalPages，共${totalCount}条记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CourseSelectionCourseRow(
    index: Int,
    course: CourseSelectionCourse,
    checked: Boolean,
    done: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val selected = checked || done
    val statusText = when {
        done -> "已完成"
        course.remaining == 0 -> "无余量"
        course.status == "available" -> "可选"
        course.status.isNotBlank() -> course.status
        else -> "可选"
    }
    val rowColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        color = rowColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = selected,
                    enabled = enabled,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.width(52.dp),
                )
                Text(
                    index.toString(),
                    modifier = Modifier.width(42.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        course.courseName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!course.note.isNullOrBlank()) {
                        Text(
                            course.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    course.remainingText ?: course.remaining?.toString() ?: "-",
                    modifier = Modifier.width(58.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (course.remaining == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    statusText,
                    modifier = Modifier.width(72.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (course.remaining == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 94.dp),
            ) {
                CourseSelectionMeta("学分", course.credit, Modifier.weight(1f))
                CourseSelectionMeta("考核", course.examType, Modifier.weight(1f))
                CourseSelectionMeta("教师", course.teacher, Modifier.weight(1f))
            }
            if (!course.timeLocation.isNullOrBlank()) {
                Text(
                    course.timeLocation,
                    modifier = Modifier.padding(start = 94.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun CourseSelectionHeaderText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CourseSelectionMeta(label: String, value: String?, modifier: Modifier = Modifier) {
    Text(
        "$label ${value?.takeIf { it.isNotBlank() } ?: "-"}",
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun filteredCourseSelectionCourses(
    courses: List<CourseSelectionCourse>,
    groupName: String?,
    query: String,
    sectionQuery: String,
    filter: CourseSelectionAvailabilityFilter,
    selectedKeys: Set<String>,
): List<CourseSelectionCourse> {
    val normalizedGroupName = groupName?.normalizedCourseSelectionFilter().orEmpty()
    val normalizedQuery = query.normalizedCourseSelectionFilter()
    val normalizedSection = sectionQuery.normalizedCourseSelectionFilter()
    return courses.filter { course ->
        val matchesGroup = normalizedGroupName.isBlank() ||
            course.groupName?.normalizedCourseSelectionFilter() == normalizedGroupName
        val matchesQuery = normalizedQuery.isBlank() ||
            listOfNotNull(
                course.key,
                course.courseName,
                course.courseCode,
                course.groupName,
                course.teacher,
                course.timeLocation,
                course.note,
            ).any { it.normalizedCourseSelectionFilter().contains(normalizedQuery) }
        val matchesSection = normalizedSection.isBlank() ||
            listOfNotNull(course.section, course.courseName, course.key)
                .any { it.normalizedCourseSelectionFilter().contains(normalizedSection) }
        val matchesFilter = when (filter) {
            CourseSelectionAvailabilityFilter.All -> true
            CourseSelectionAvailabilityFilter.HasRemaining -> course.remaining == null || course.remaining > 0
            CourseSelectionAvailabilityFilter.Full -> course.remaining == 0
            CourseSelectionAvailabilityFilter.Checked -> course.key in selectedKeys
        }
        matchesGroup && matchesQuery && matchesSection && matchesFilter
    }
}

private fun courseSelectionGroupNames(data: CourseSelectionData): List<String> =
    (data.courseGroupNames + data.availableCourses.mapNotNull { it.groupName })
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .ifEmpty { if (data.availableCourses.isNotEmpty()) DefaultCourseSelectionGroupNames else emptyList() }

private fun courseSelectionTotalPages(totalCount: Int): Int =
    if (totalCount <= 0) 0 else ((totalCount - 1) / CourseSelectionPageSize) + 1

private fun String.normalizedCourseSelectionFilter(): String =
    trim().lowercase().replace(Regex("""\s+"""), "")

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
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "验证码",
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = 180.dp),
        contentScale = ContentScale.Fit,
    )
}
