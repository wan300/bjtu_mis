package cn.edu.bjtu.mis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.TeachingAssessmentCourse
import cn.edu.bjtu.mis.model.TeachingAssessmentData
import cn.edu.bjtu.mis.model.TeachingAssessmentForm
import cn.edu.bjtu.mis.model.TeachingAssessmentSubmitResult
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.SectionTitle
import kotlinx.coroutines.launch

private const val DefaultAssessmentComment = "很好"

private data class TeachingAssessmentDraft(
    val course: TeachingAssessmentCourse,
    val form: TeachingAssessmentForm? = null,
    val answers: Map<String, String> = emptyMap(),
    val comments: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val result: TeachingAssessmentSubmitResult? = null,
)

@Composable
fun TeachingAssessmentScreen(repository: ModuleRepository) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LoadState<ModuleEnvelope<TeachingAssessmentData>>>(LoadState.Loading) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var drafts by remember { mutableStateOf<List<TeachingAssessmentDraft>>(emptyList()) }
    var commentTemplate by remember { mutableStateOf(DefaultAssessmentComment) }
    var previewLoading by remember { mutableStateOf(false) }
    var submitRunning by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            state = LoadState.Loading
            runCatching { repository.teachingAssessments() }
                .onSuccess {
                    state = LoadState.Data(it)
                    val evaluableIds = it.data.courses.filter { course -> course.canEvaluate }.map { course -> course.id }.toSet()
                    selectedIds = selectedIds.intersect(evaluableIds)
                }
                .onFailure { state = LoadState.Error(it.message ?: "评教列表加载失败") }
        }
    }

    fun openPreview(courses: List<TeachingAssessmentCourse>) {
        val candidates = courses.filter { it.canEvaluate }
        if (candidates.isEmpty() || previewLoading) return
        scope.launch {
            previewLoading = true
            drafts = candidates.map { TeachingAssessmentDraft(course = it, loading = true) }
            val next = mutableListOf<TeachingAssessmentDraft>()
            for (course in candidates) {
                val draft = runCatching {
                    val form = repository.teachingAssessmentForm(course.id)
                    TeachingAssessmentDraft(
                        course = course,
                        form = form,
                        answers = form.questions.associate { question ->
                            question.name to (question.recommendedValue ?: question.options.lastOrNull()?.value.orEmpty())
                        },
                        comments = form.comments.associate { comment -> comment.name to commentTemplate },
                        error = when {
                            form.unsupportedMultiCount > 0 ->
                                "该评教表包含 ${form.unsupportedMultiCount} 个暂不支持的多选题。"
                            form.questions.isEmpty() && form.comments.isEmpty() ->
                                "未解析到可填写的评教题目。"
                            else -> null
                        },
                    )
                }.getOrElse { error ->
                    TeachingAssessmentDraft(course = course, error = error.message ?: "评教表读取失败")
                }
                next += draft
                drafts = next + candidates.drop(next.size).map { TeachingAssessmentDraft(course = it, loading = true) }
            }
            drafts = next
            previewLoading = false
        }
    }

    fun updateDraft(courseId: String, transform: (TeachingAssessmentDraft) -> TeachingAssessmentDraft) {
        drafts = drafts.map { draft -> if (draft.course.id == courseId) transform(draft) else draft }
    }

    fun applyCommentTemplate(value: String) {
        commentTemplate = value
        drafts = drafts.map { draft ->
            val form = draft.form ?: return@map draft
            draft.copy(comments = form.comments.associate { comment -> comment.name to value })
        }
    }

    fun submitOne(index: Int) {
        val draft = drafts.getOrNull(index) ?: return
        val form = draft.form ?: return
        if (!draft.readyForSubmit()) return
        scope.launch {
            updateDraft(draft.course.id) { it.copy(submitting = true, result = null) }
            val result = runCatching {
                repository.submitTeachingAssessment(form, draft.answers, draft.comments)
            }.getOrElse { error ->
                TeachingAssessmentSubmitResult(
                    courseId = draft.course.id,
                    status = "error",
                    message = error.message ?: "提交失败",
                    success = false,
                )
            }
            updateDraft(draft.course.id) { it.copy(submitting = false, result = result) }
        }
    }

    fun submitAll() {
        if (submitRunning) return
        scope.launch {
            submitRunning = true
            drafts.forEachIndexed { index, draft ->
                val form = draft.form
                if (form == null || !draft.readyForSubmit()) return@forEachIndexed
                updateDraft(draft.course.id) { it.copy(submitting = true, result = null) }
                val result = runCatching {
                    repository.submitTeachingAssessment(form, draft.answers, draft.comments)
                }.getOrElse { error ->
                    TeachingAssessmentSubmitResult(
                        courseId = draft.course.id,
                        status = "error",
                        message = error.message ?: "提交失败",
                        success = false,
                    )
                }
                updateDraft(draft.course.id) { it.copy(submitting = false, result = result) }
            }
            submitRunning = false
        }
    }

    LaunchedEffect(Unit) { load() }

    val envelope = (state as? LoadState.Data)?.value
    val evaluableCourses = envelope?.data?.courses.orEmpty().filter { it.canEvaluate }
    val excludedCourseCount = envelope?.data?.courses.orEmpty().count { !it.canEvaluate }
    val selectedCourses = evaluableCourses.filter { it.id in selectedIds }
    val previewMode = drafts.isNotEmpty() || previewLoading

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionTitle(
                title = if (previewMode) "批量评教预览" else "评教",
                subtitle = if (previewMode) {
                    "确认课程和预填内容后才会提交。"
                } else {
                    "实时读取 AA 教学支撑平台待评课程。"
                },
                trailing = {
                    if (previewMode) {
                        OutlinedButton(enabled = !submitRunning, onClick = { drafts = emptyList() }) { Text("返回列表") }
                    } else {
                        Button(onClick = ::load) { Text("刷新") }
                    }
                },
            )
        }

        if (!previewMode) {
            when (val current = state) {
                LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
                is LoadState.Data -> {
                    item {
                        TeachingAssessmentSelectionControls(
                            total = evaluableCourses.size,
                            selected = selectedCourses.size,
                            excluded = excludedCourseCount,
                            onSelectAll = { selectedIds = evaluableCourses.map { it.id }.toSet() },
                            onClear = { selectedIds = emptySet() },
                            onPreview = { openPreview(selectedCourses) },
                        )
                    }
                    if (current.value.data.courses.isEmpty()) {
                        item { InfoCard("暂无评教课程", subtitle = "当前列表为空。") {} }
                    } else {
                        items(current.value.data.courses, key = { it.id }) { course ->
                            TeachingAssessmentCourseCard(
                                course = course,
                                checked = course.id in selectedIds,
                                enabled = course.canEvaluate,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + course.id else selectedIds - course.id
                                },
                            )
                        }
                    }
                }
            }
        } else {
            item {
                val submittedCount = drafts.count { it.result != null }
                val successCount = drafts.count { it.result?.success == true }
                val failedCount = drafts.count { draft -> draft.result?.let { !it.success } == true }
                InfoCard("预填设置", subtitle = "单选题可在每门课程卡片中调整；主观题可统一填写。") {
                    if (submittedCount > 0) {
                        Text(
                            "提交结果：成功 $successCount 门，失败 $failedCount 门。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (failedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    OutlinedTextField(
                        value = commentTemplate,
                        onValueChange = ::applyCommentTemplate,
                        label = { Text("主观题默认内容") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !submitRunning,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            enabled = drafts.any { it.readyForSubmit() } && !previewLoading && !submitRunning,
                            onClick = { showConfirm = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (submitRunning) "提交中" else "确认提交")
                        }
                        OutlinedButton(
                            enabled = !previewLoading && !submitRunning,
                            onClick = { openPreview(selectedCourses) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("重新读取")
                        }
                    }
                }
            }
            items(drafts, key = { it.course.id }) { draft ->
                TeachingAssessmentDraftCard(
                    draft = draft,
                    submitRunning = submitRunning,
                    onAnswerChange = { questionName, value ->
                        updateDraft(draft.course.id) {
                            it.copy(answers = it.answers + (questionName to value), result = null)
                        }
                    },
                    onCommentChange = { commentName, value ->
                        updateDraft(draft.course.id) {
                            it.copy(comments = it.comments + (commentName to value), result = null)
                        }
                    },
                    onRetry = {
                        val index = drafts.indexOfFirst { it.course.id == draft.course.id }
                        if (index >= 0) submitOne(index)
                    },
                )
            }
        }
    }

    if (showConfirm) {
        val readyCount = drafts.count { it.readyForSubmit() }
        AlertDialog(
            onDismissRequest = { if (!submitRunning) showConfirm = false },
            title = { Text("确认提交评教") },
            text = { Text("将按当前预填内容提交 $readyCount 门课程。提交后可能无法撤回，请确认课程和评价内容无误。") },
            confirmButton = {
                TextButton(
                    enabled = readyCount > 0 && !submitRunning,
                    onClick = {
                        showConfirm = false
                        submitAll()
                    },
                ) {
                    Text("提交")
                }
            },
            dismissButton = {
                TextButton(enabled = !submitRunning, onClick = { showConfirm = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun TeachingAssessmentSelectionControls(
    total: Int,
    selected: Int,
    excluded: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onPreview: () -> Unit,
) {
    val subtitle = if (excluded > 0) {
        "已选择 $selected/$total 门待评课程；$excluded 门已评教或不可评教课程不会提交。"
    } else {
        "已选择 $selected/$total 门待评课程。"
    }
    InfoCard("批量选择", subtitle = subtitle) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(enabled = total > 0, onClick = onSelectAll, modifier = Modifier.weight(1f)) { Text("全选") }
            OutlinedButton(enabled = selected > 0, onClick = onClear, modifier = Modifier.weight(1f)) { Text("清空") }
        }
        Button(enabled = selected > 0, onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
            Text("批量预览")
        }
    }
}

@Composable
private fun TeachingAssessmentCourseCard(
    course: TeachingAssessmentCourse,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    InfoCard(
        title = course.courseName,
        subtitle = listOf(course.courseCode, course.section, course.teacher).filter { it.isNotBlank() }.joinToString(" · "),
        trailing = {
            if (course.canEvaluate) {
                Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
            } else {
                Text(
                    text = teachingAssessmentStatusLabel(course),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (course.isTeachingAssessmentDone()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("状态", teachingAssessmentStatusLabel(course), Modifier.weight(1f))
            KeyValue("类型", course.assessmentType, Modifier.weight(1f))
        }
        if (!course.canEvaluate) {
            Text(
                text = if (course.isTeachingAssessmentDone()) {
                    "已完成评教，不会被全选或批量提交。"
                } else {
                    "当前不可评教，已从全选和批量提交中排除。"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (course.isTeachingAssessmentDone()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TeachingAssessmentDraftCard(
    draft: TeachingAssessmentDraft,
    submitRunning: Boolean,
    onAnswerChange: (String, String) -> Unit,
    onCommentChange: (String, String) -> Unit,
    onRetry: () -> Unit,
) {
    val result = draft.result
    InfoCard(
        title = draft.course.courseName,
        subtitle = listOf(draft.course.teacher, draft.course.assessmentType).filter { it.isNotBlank() }.joinToString(" · "),
    ) {
        when {
            draft.loading -> Text("正在读取评教表单。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            draft.error != null -> Text(draft.error, color = MaterialTheme.colorScheme.error)
            draft.form == null -> Text("评教表单不可用。", color = MaterialTheme.colorScheme.error)
            else -> {
                val form = draft.form
                if (form.unsupportedMultiCount > 0) {
                    Text("包含暂不支持的多选题，已跳过提交。", color = MaterialTheme.colorScheme.error)
                }
                form.questions.forEach { question ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "${question.index + 1}. ${question.prompt}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            question.options.forEach { option ->
                                FilterChip(
                                    selected = draft.answers[question.name] == option.value,
                                    enabled = !submitRunning && !draft.submitting,
                                    onClick = { onAnswerChange(question.name, option.value) },
                                    label = { Text(option.label) },
                                )
                            }
                        }
                    }
                }
                form.comments.forEach { comment ->
                    OutlinedTextField(
                        value = draft.comments[comment.name].orEmpty(),
                        onValueChange = { onCommentChange(comment.name, it) },
                        label = { Text(comment.prompt.ifBlank { "主观题" }) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !submitRunning && !draft.submitting,
                    )
                }
            }
        }
        if (draft.submitting) {
            Text("正在提交。", color = MaterialTheme.colorScheme.primary)
        }
        if (result != null) {
            Text(
                result.message ?: result.status,
                color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (!result.success && draft.readyForSubmit()) {
                OutlinedButton(enabled = !submitRunning && !draft.submitting, onClick = onRetry) {
                    Text("重试")
                }
            }
        }
    }
}

private fun TeachingAssessmentDraft.readyForSubmit(): Boolean {
    val form = form ?: return false
    if (!course.canEvaluate) return false
    if (loading || submitting || error != null || form.unsupportedMultiCount > 0) return false
    if (form.questions.isEmpty() && form.comments.isEmpty()) return false
    if (result?.success == true) return false
    if (form.questions.any { answers[it.name].isNullOrBlank() }) return false
    return true
}

private fun TeachingAssessmentCourse.isTeachingAssessmentDone(): Boolean =
    status.contains("已") || viewPath != null

private fun teachingAssessmentStatusLabel(course: TeachingAssessmentCourse): String =
    when {
        course.canEvaluate -> "待评教"
        course.isTeachingAssessmentDone() -> "已评教"
        course.status.isNotBlank() -> course.status
        else -> "不可评教"
    }
