package cn.edu.bjtu.mis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.repository.CourseResourceRepository
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.model.AcademicProgressData
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.ExamData
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.model.TimetableData
import cn.edu.bjtu.mis.ui.components.CoverageChip
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.SectionTitle
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(repository: ModuleRepository) {
    DataScreen(title = "我的信息", loader = { repository.profile() }) { envelope ->
        val profile = envelope.data
        item {
            SectionTitle(profile.name ?: "我的信息", profile.studentId, trailing = { CoverageChip(envelope.coverage) })
        }
        val sections = profile.sections.ifEmpty { listOf(cn.edu.bjtu.mis.model.ProfileSection("基本信息", profile.fields)) }
        items(sections, key = { it.title }) { section ->
            InfoCard(section.title) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    section.fields.forEach { KeyValue(it.label, it.value) }
                }
            }
        }
    }
}

@Composable
fun AcademicProgressScreen(repository: ModuleRepository) {
    DataScreen(title = "学业进度", loader = { repository.academicProgress() }) { envelope ->
        val data = envelope.data
        item {
            SectionTitle("学业进度", "完成率 ${data.summary.completionRate}%", trailing = { CoverageChip(envelope.coverage) })
        }
        item {
            InfoCard("学分概览") {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("已获学分", data.summary.passedCredits.toString(), Modifier.weight(1f))
                    KeyValue("目标学分", data.summary.targetCredits?.toString(), Modifier.weight(1f))
                    KeyValue("需关注课程", data.summary.failedCourseCount.toString(), Modifier.weight(1f))
                }
            }
        }
        items(data.buckets, key = { it.name + it.parent.orEmpty() }) { bucket ->
            InfoCard(bucket.name, subtitle = bucket.parent) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("要求", bucket.requiredCredits?.toString(), Modifier.weight(1f))
                    KeyValue("已完成", bucket.earnedCredits.toString(), Modifier.weight(1f))
                    KeyValue("完成率", bucket.completionRate?.let { "$it%" }, Modifier.weight(1f))
                }
            }
        }
        items(data.courses.take(80), key = { it.courseName + it.term.orEmpty() }) { course ->
            InfoCard(course.courseName, subtitle = course.term) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("学分", course.credit?.toString(), Modifier.weight(1f))
                    KeyValue("成绩", course.score, Modifier.weight(1f))
                    KeyValue("状态", course.status, Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    repository: ModuleRepository,
    courseResourceRepository: CourseResourceRepository,
) {
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isWide = configuration.screenWidthDp >= 840
    var state by remember { mutableStateOf<LoadState<ModuleEnvelope<TimetableData>>>(LoadState.Loading) }
    var selectedCourse by remember { mutableStateOf<CourseEntry?>(null) }
    var homeworkState by remember { mutableStateOf<LoadState<List<HomeworkItem>>>(LoadState.Data(emptyList())) }
    var resourceState by remember { mutableStateOf<LoadState<ModuleEnvelope<CourseResourcesData>>>(LoadState.Data(emptyEnvelopeCourseResources())) }
    var currentWeek by remember { mutableStateOf<Int?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun loadTimetable() {
        scope.launch {
            state = LoadState.Loading
            runCatching { repository.timetable() }
                .onSuccess { state = LoadState.Data(it) }
                .onFailure { state = LoadState.Error(it.message ?: "加载失败") }
        }
    }

    fun loadDetail(entry: CourseEntry) {
        selectedCourse = entry
        val selectedKey = courseEntryKey(entry)
        homeworkState = LoadState.Loading
        resourceState = LoadState.Loading
        scope.launch {
            runCatching {
                repository.homework("all").data.items
                    .filter { matchesCourse(entry, it) }
                    .sortedWith(compareBy<HomeworkItem> { it.dueAt ?: "9999" }.thenBy { it.title })
            }.onSuccess {
                if (selectedCourse?.let(::courseEntryKey) == selectedKey) homeworkState = LoadState.Data(it)
            }.onFailure {
                if (selectedCourse?.let(::courseEntryKey) == selectedKey) homeworkState = LoadState.Error(it.message ?: "作业加载失败")
            }
        }
        scope.launch {
            runCatching {
                courseResourceRepository.listing(courseId = courseLookupKey(entry), folderId = "0")
            }.onSuccess {
                if (selectedCourse?.let(::courseEntryKey) == selectedKey) resourceState = LoadState.Data(it)
            }.onFailure {
                if (selectedCourse?.let(::courseEntryKey) == selectedKey) resourceState = LoadState.Error(it.message ?: "资源加载失败")
            }
        }
    }

    LaunchedEffect(Unit) {
        loadTimetable()
        runCatching { repository.calendar() }
            .onSuccess { currentWeek = parseWeekNumber(it.data.currentWeek) }
    }

    when (val current = state) {
        LoadState.Loading, is LoadState.Error -> LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { LoadingOrError(current) }
        }
        is LoadState.Data -> {
            val envelope = current.value
            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TimetableList(
                        envelope = envelope,
                        currentWeek = currentWeek,
                        selectedCourse = selectedCourse,
                        onSelect = ::loadDetail,
                        modifier = Modifier.weight(1f),
                    )
                    selectedCourse?.let { entry ->
                        Surface(
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight(),
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            CourseDetailPanel(
                                entry = entry,
                                currentWeek = currentWeek,
                                homeworkState = homeworkState,
                                resourceState = resourceState,
                                courseResourceRepository = courseResourceRepository,
                            )
                        }
                    }
                }
            } else {
                TimetableList(
                    envelope = envelope,
                    currentWeek = currentWeek,
                    selectedCourse = selectedCourse,
                    onSelect = ::loadDetail,
                    modifier = Modifier.fillMaxSize(),
                )
                selectedCourse?.let { entry ->
                    ModalBottomSheet(
                        onDismissRequest = { selectedCourse = null },
                        sheetState = sheetState,
                    ) {
                        CourseDetailPanel(
                            entry = entry,
                            currentWeek = currentWeek,
                            homeworkState = homeworkState,
                            resourceState = resourceState,
                            courseResourceRepository = courseResourceRepository,
                            modifier = Modifier.heightIn(max = 720.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExamsScreen(repository: ModuleRepository) {
    DataScreen(title = "考务", loader = { repository.exams() }) { envelope ->
        val data = envelope.data
        item { SectionTitle("考务", data.currentTerm, trailing = { CoverageChip(envelope.coverage) }) }
        items(data.items, key = { it.courseName + it.schedule.orEmpty() }) { exam ->
            InfoCard(exam.courseName, subtitle = exam.schedule) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("方式", exam.examMode, Modifier.weight(1f))
                    KeyValue("状态", exam.status, Modifier.weight(1f))
                }
                KeyValue("备注", exam.remark)
            }
        }
    }
}

@Composable
fun ScoresScreen(repository: ModuleRepository, history: Boolean = false) {
    DataScreen(
        title = if (history) "历史成绩" else "主修成绩",
        loader = { if (history) repository.historyScores() else repository.scores(ctype = "lr") },
    ) { envelope ->
        val data = envelope.data
        item { SectionTitle(if (history) "历史成绩" else "主修成绩", data.currentTerm, trailing = { CoverageChip(envelope.coverage) }) }
        items(data.items, key = { it.courseName + it.term.orEmpty() + it.score.orEmpty() }) { score ->
            InfoCard(score.courseName, subtitle = score.term) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("学分", score.credit, Modifier.weight(1f))
                    KeyValue("成绩", score.score, Modifier.weight(1f))
                    KeyValue("教师", score.teacher, Modifier.weight(1f))
                }
                KeyValue("详情", score.detail)
            }
        }
    }
}

@Composable
fun CalendarScreen(repository: ModuleRepository) {
    DataScreen(title = "学年日历", loader = { repository.calendar() }) { envelope ->
        val data = envelope.data
        item { SectionTitle("学年日历", "${data.month} 第 ${data.currentWeek ?: "-"} 周", trailing = { CoverageChip(envelope.coverage) }) }
        items(data.items, key = { it.date }) { item ->
            InfoCard(item.date, subtitle = item.week) {
                Text(item.note ?: "教学日历记录", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun HomeworkScreen(repository: ModuleRepository) {
    var status by remember { mutableStateOf("all") }
    DataScreen(title = "作业", refreshKey = status, loader = { repository.homework(status) }) { envelope ->
        val data = envelope.data
        item {
            SectionTitle("作业", data.currentTerm, trailing = { CoverageChip(envelope.coverage) })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "全部", "open" to "待完成", "done" to "已完成").forEach { (key, label) ->
                    FilterChip(selected = status == key, onClick = { status = key }, label = { Text(label) })
                }
            }
        }
        items(data.items, key = { it.homeworkId ?: (it.title + it.courseId).hashCode() }) { item ->
            InfoCard(item.title, subtitle = item.course) {
                KeyValue("开始", item.openedAt)
                KeyValue("截止", item.dueAt)
                KeyValue("状态", item.status)
                KeyValue("内容", item.contentExcerpt)
            }
        }
    }
}

@Composable
fun EmptyRoomsScreen(repository: ModuleRepository) {
    val scope = rememberCoroutineScope()
    var week by remember { mutableStateOf("8") }
    var building by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<LoadState<ModuleEnvelope<EmptyRoomData>>>(LoadState.Loading) }

    fun load() {
        scope.launch {
            state = LoadState.Loading
            runCatching { repository.emptyRooms(week = week, building = building.ifBlank { null }, room = room.ifBlank { null }) }
                .onSuccess { state = LoadState.Data(it) }
                .onFailure { state = LoadState.Error(it.message ?: "加载失败") }
        }
    }

    LaunchedEffect(Unit) { load() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionTitle("空教室", "按周次、教学楼或教室筛选")
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = week, onValueChange = { week = it }, label = { Text("周次") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = building, onValueChange = { building = it }, label = { Text("教学楼") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = room, onValueChange = { room = it }, label = { Text("教室") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { load() }) { Text("查询") }
            }
        }
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
            is LoadState.Data -> {
                val envelope = current.value
                item { CoverageChip(envelope.coverage) }
                items(envelope.data.rooms, key = { it.room }) { row ->
                    InfoCard(row.room, subtitle = row.seatLabel) {
                        Text(row.availability.map { if (it) "空" else "占" }.joinToString("  "))
                    }
                }
            }
        }
    }
}

@Composable
private fun TimetableList(
    envelope: ModuleEnvelope<TimetableData>,
    currentWeek: Int?,
    selectedCourse: CourseEntry?,
    onSelect: (CourseEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val data = envelope.data
    val selectedKey = selectedCourse?.let(::courseEntryKey)
    val entries = data.entries.sortedWith(
        compareBy<CourseEntry> { orderedIndex(data.days, it.weekday) }
            .thenBy { orderedIndex(data.periods, it.period) }
            .thenBy { it.courseName },
    )
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionTitle(
                title = "课表",
                subtitle = listOfNotNull(data.currentTerm, currentWeek?.let { "第 $it 周" }).joinToString(" · "),
                trailing = { CoverageChip(envelope.coverage) },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("${entries.size} 门课程") })
                if (data.days.isNotEmpty()) {
                    AssistChip(onClick = {}, label = { Text("${data.days.size} 天") })
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                InfoCard("暂无课表") {
                    Text("当前没有可显示的课程。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            items(entries, key = { courseEntryKey(it) }) { entry ->
                val entryKey = courseEntryKey(entry)
                InfoCard(
                    title = entry.courseName,
                    subtitle = listOfNotNull(entry.weekday, entry.period, entry.timeRange).joinToString(" · "),
                    modifier = Modifier.clickable { onSelect(entry) },
                    trailing = {
                        if (entryKey == selectedKey) {
                            AssistChip(onClick = {}, label = { Text("已选择") })
                        }
                    },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("课程号", entry.courseCode, Modifier.weight(1f))
                        KeyValue("教师", entry.teacher, Modifier.weight(1f))
                    }
                    KeyValue("周次", entry.weeks)
                    KeyValue("地点", entry.locationLabel())
                }
            }
        }
    }
}

@Composable
private fun CourseDetailPanel(
    entry: CourseEntry,
    currentWeek: Int?,
    homeworkState: LoadState<List<HomeworkItem>>,
    resourceState: LoadState<ModuleEnvelope<CourseResourcesData>>,
    courseResourceRepository: CourseResourceRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val entryKey = courseEntryKey(entry)
    var downloading by remember(entryKey) { mutableStateOf<String?>(null) }
    var downloadError by remember(entryKey) { mutableStateOf<String?>(null) }

    LazyColumn(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionTitle(
                title = entry.courseName,
                subtitle = listOfNotNull(entry.courseCode, entry.teacher).joinToString(" · "),
            )
        }
        item {
            InfoCard("课程信息") {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("星期", entry.weekday, Modifier.weight(1f))
                    KeyValue("节次", entry.period, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                    KeyValue("当前周", currentWeek?.toString(), Modifier.weight(1f))
                    KeyValue("上课时间", entry.timeRange, Modifier.weight(1f))
                }
                KeyValue("周次", entry.weeks)
                KeyValue("地点", entry.locationLabel())
            }
        }
        item { HorizontalDivider() }
        item { Text("作业", style = MaterialTheme.typography.titleMedium) }
        when (val current = homeworkState) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
            is LoadState.Data -> {
                if (current.value.isEmpty()) {
                    item { Text("暂无匹配作业。", style = MaterialTheme.typography.bodyMedium) }
                } else {
                    items(current.value, key = { it.homeworkId ?: "${it.courseId}-${it.title}".hashCode() }) { homework ->
                        InfoCard(homework.title, subtitle = homework.course) {
                            KeyValue("开始", homework.openedAt)
                            KeyValue("截止", homework.dueAt)
                            KeyValue("状态", homework.status)
                            KeyValue("内容", homework.contentExcerpt)
                        }
                    }
                }
            }
        }
        item { HorizontalDivider() }
        item { Text("课程资源", style = MaterialTheme.typography.titleMedium) }
        if (!downloadError.isNullOrBlank()) {
            item { Text(downloadError.orEmpty(), color = MaterialTheme.colorScheme.error) }
        }
        when (val current = resourceState) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
            is LoadState.Data -> {
                val data = current.value.data
                if (data.folders.isEmpty() && data.resources.isEmpty()) {
                    item { Text("暂无匹配资源。", style = MaterialTheme.typography.bodyMedium) }
                } else {
                    items(data.folders, key = { it.folderId }) { folder ->
                        InfoCard(folder.name, subtitle = "目录 ${folder.folderId}") {
                            Text("请到课程资源页继续浏览该目录。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(data.resources, key = { it.rpId }) { resource ->
                        InfoCard(
                            title = resource.name,
                            subtitle = resource.uploadedAt,
                            trailing = {
                                if (resource.canDownload) {
                                    Button(
                                        enabled = downloading == null,
                                        onClick = {
                                            scope.launch {
                                                downloading = resource.rpId
                                                downloadError = null
                                                runCatching { courseResourceRepository.download(resource.rpId, resource.name) }
                                                    .onSuccess { openFile(context, it) }
                                                    .onFailure { downloadError = it.message ?: "下载失败" }
                                                downloading = null
                                            }
                                        },
                                    ) {
                                        Text(if (downloading == resource.rpId) "下载中" else "下载")
                                    }
                                }
                            },
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                                KeyValue("类型", resource.extension, Modifier.weight(1f))
                                KeyValue("大小", resource.size?.let { "$it MB" }, Modifier.weight(1f))
                                KeyValue("下载", resource.downloadCount?.toString(), Modifier.weight(1f))
                            }
                            KeyValue("教师", resource.teacherName)
                        }
                    }
                }
            }
        }
    }
}

private fun emptyEnvelopeCourseResources(): ModuleEnvelope<CourseResourcesData> =
    ModuleEnvelope(
        module = "course_resources",
        sourceSystem = "ve",
        coverage = CoverageLevel.Provisional,
        data = CourseResourcesData(),
    )

private fun courseEntryKey(entry: CourseEntry): String =
    listOf(entry.courseCode, entry.section.orEmpty(), entry.courseName, entry.weekday, entry.period)
        .joinToString("|")

private fun courseLookupKey(entry: CourseEntry): String? =
    entry.courseCode.takeIf { it.isNotBlank() }

private fun matchesCourse(entry: CourseEntry, homework: HomeworkItem): Boolean {
    val entryCode = entry.courseCode.normalizedCourseText()
    val homeworkCode = homework.courseCode.normalizedCourseText()
    if (entryCode.isNotBlank() && entryCode == homeworkCode) return true

    val entryName = entry.courseName.normalizedCourseText()
    val homeworkCourse = homework.course.normalizedCourseText()
    return entryName.isNotBlank() && homeworkCourse.isNotBlank() &&
        (entryName == homeworkCourse || entryName.contains(homeworkCourse) || homeworkCourse.contains(entryName))
}

private fun parseWeekNumber(value: String?): Int? =
    value?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

private fun orderedIndex(values: List<String>, value: String): Int =
    values.indexOf(value).takeIf { it >= 0 } ?: Int.MAX_VALUE

private fun CourseEntry.locationLabel(): String? =
    locationText ?: listOfNotNull(campus, building, room).joinToString(" ").takeIf { it.isNotBlank() }

private fun String?.normalizedCourseText(): String =
    this?.trim()?.lowercase()?.replace(Regex("""\s+"""), "").orEmpty()

@Composable
private fun <T> DataScreen(
    title: String,
    refreshKey: Any? = Unit,
    loader: suspend () -> ModuleEnvelope<T>,
    content: androidx.compose.foundation.lazy.LazyListScope.(ModuleEnvelope<T>) -> Unit,
) {
    var state by remember(refreshKey) { mutableStateOf<LoadState<ModuleEnvelope<T>>>(LoadState.Loading) }
    LaunchedEffect(refreshKey) {
        state = LoadState.Loading
        runCatching { loader() }
            .onSuccess { state = LoadState.Data(it) }
            .onFailure { state = LoadState.Error(it.message ?: "加载失败") }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
            is LoadState.Data -> content(current.value)
        }
    }
}
