package cn.edu.bjtu.mis.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.repository.CourseResourceRepository
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.model.AcademicProgressData
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.EmptyRoomRow
import cn.edu.bjtu.mis.model.EmptyRoomSlotHeader
import cn.edu.bjtu.mis.model.ExamData
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.model.TermOption
import cn.edu.bjtu.mis.model.TimetableData
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.SectionTitle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val HISTORY_ALL_TERMS = "all"

@Composable
fun ProfileScreen(repository: ModuleRepository, onLogout: () -> Unit) {
    DataScreen(title = "我的信息", loader = { repository.profile() }) { envelope ->
        val profile = envelope.data
        if (!profile.name.isNullOrBlank() || !profile.studentId.isNullOrBlank()) {
            item {
                InfoCard(profile.name ?: "基本信息", subtitle = profile.studentId) {}
            }
        }
        val sections = profile.sections.ifEmpty { listOf(cn.edu.bjtu.mis.model.ProfileSection("基本信息", profile.fields)) }
        items(sections, key = { it.title }) { section ->
            InfoCard(section.title) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    section.fields.forEach { KeyValue(it.label, it.value) }
                }
            }
        }
        item {
            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("退出登录")
            }
        }
    }
}

@Composable
fun AcademicProgressScreen(repository: ModuleRepository) {
    DataScreen(title = "学业进度", loader = { repository.academicProgress() }) { envelope ->
        val data = envelope.data
        item {
            InfoCard("学分概览", subtitle = "完成率 ${data.summary.completionRate}%") {
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
    var selectedCourses by remember { mutableStateOf<List<CourseEntry>>(emptyList()) }
    var homeworkStates by remember { mutableStateOf<Map<String, LoadState<List<HomeworkItem>>>>(emptyMap()) }
    var resourceStates by remember { mutableStateOf<Map<String, LoadState<ModuleEnvelope<CourseResourcesData>>>>(emptyMap()) }
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

    fun loadDetail(entries: List<CourseEntry>) {
        val nextSelection = entries.ifEmpty { return }
        selectedCourses = nextSelection
        val selectionKey = courseSelectionKey(nextSelection)
        homeworkStates = nextSelection.associate { courseEntryKey(it) to LoadState.Loading }
        resourceStates = nextSelection.associate { courseEntryKey(it) to LoadState.Loading }
        scope.launch {
            runCatching {
                repository.homework("all").data.items
            }.onSuccess { homework ->
                if (courseSelectionKey(selectedCourses) == selectionKey) {
                    homeworkStates = nextSelection.associate { entry ->
                        courseEntryKey(entry) to LoadState.Data(
                            homework.filter { matchesCourse(entry, it) }
                                .sortedWith(compareBy<HomeworkItem> { it.dueAt ?: "9999" }.thenBy { it.title })
                        )
                    }
                }
            }.onFailure { error ->
                if (courseSelectionKey(selectedCourses) == selectionKey) {
                    homeworkStates = nextSelection.associate { entry ->
                        courseEntryKey(entry) to LoadState.Error(error.message ?: "作业加载失败")
                    }
                }
            }
        }
        nextSelection.forEach { entry ->
            val entryKey = courseEntryKey(entry)
            scope.launch {
                runCatching {
                    courseResourceRepository.listing(courseId = courseLookupKey(entry), folderId = "0")
                }.onSuccess {
                    if (courseSelectionKey(selectedCourses) == selectionKey) {
                        resourceStates = resourceStates + (entryKey to LoadState.Data(it))
                    }
                }.onFailure {
                    if (courseSelectionKey(selectedCourses) == selectionKey) {
                        resourceStates = resourceStates + (entryKey to LoadState.Error(it.message ?: "资源加载失败"))
                    }
                }
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
                        selectedCourses = selectedCourses,
                        onSelect = ::loadDetail,
                        modifier = Modifier.weight(1f),
                    )
                    if (selectedCourses.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight(),
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            CourseDetailPanel(
                                entries = selectedCourses,
                                currentWeek = currentWeek,
                                homeworkStates = homeworkStates,
                                resourceStates = resourceStates,
                                courseResourceRepository = courseResourceRepository,
                            )
                        }
                    }
                }
            } else {
                TimetableList(
                    envelope = envelope,
                    currentWeek = currentWeek,
                    selectedCourses = selectedCourses,
                    onSelect = ::loadDetail,
                    modifier = Modifier.fillMaxSize(),
                )
                if (selectedCourses.isNotEmpty()) {
                    ModalBottomSheet(
                        onDismissRequest = { selectedCourses = emptyList() },
                        sheetState = sheetState,
                    ) {
                        CourseDetailPanel(
                            entries = selectedCourses,
                            currentWeek = currentWeek,
                            homeworkStates = homeworkStates,
                            resourceStates = resourceStates,
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
        val currentTerm = data.currentTerm
        if (!currentTerm.isNullOrBlank()) {
            item {
                AssistChip(onClick = {}, label = { Text(currentTerm) })
            }
        }
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
    var requestedTerm by remember(history) { mutableStateOf(if (history) HISTORY_ALL_TERMS else "") }
    val title = if (history) "历史成绩" else "主修成绩"
    val selectedHistoryTerm = requestedTerm.takeIf { history && it != HISTORY_ALL_TERMS }
    DataScreen(
        title = title,
        refreshKey = if (history) requestedTerm else Unit,
        loader = { if (history) repository.historyScores(selectedHistoryTerm) else repository.scores(ctype = "lr") },
    ) { envelope ->
        val data = envelope.data
        val currentTerm = data.currentTerm
        if (history) {
            item {
                TermSelector(
                    terms = data.availableTerms,
                    value = requestedTerm,
                    onValueChange = { requestedTerm = it },
                    includeAllOption = true,
                )
            }
        }
        if (history && requestedTerm == HISTORY_ALL_TERMS) {
            item {
                AssistChip(onClick = {}, label = { Text("全部学期") })
            }
        } else if (!currentTerm.isNullOrBlank()) {
            item {
                AssistChip(onClick = {}, label = { Text(currentTerm) })
            }
        }
        if (data.items.isEmpty()) {
            item {
                InfoCard("暂无$title") {
                    Text("当前没有可展示的${title}记录。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            items(data.items, key = { it.courseName + it.term.orEmpty() + it.score.orEmpty() }) { score ->
                InfoCard(score.courseName, subtitle = score.term) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("学分", score.credit, Modifier.weight(1f))
                        KeyValue("成绩", score.score, Modifier.weight(1f))
                        if (history) {
                            KeyValue("加分成绩", score.bonusScore, Modifier.weight(1f))
                        } else {
                            KeyValue("教师", score.teacher, Modifier.weight(1f))
                        }
                    }
                    if (history) {
                        KeyValue("教师", score.teacher)
                    }
                    KeyValue("详情", score.detail)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermSelector(
    terms: List<TermOption>,
    value: String?,
    onValueChange: (String) -> Unit,
    includeAllOption: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = if (includeAllOption && value == HISTORY_ALL_TERMS) {
        "全部学期"
    } else {
        terms.firstOrNull { it.value == value }?.label ?: value.orEmpty()
    }
    val enabled = terms.isNotEmpty() || includeAllOption

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (enabled) {
                expanded = !expanded
            }
        },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("选择学期") },
            placeholder = { Text("暂无可选学期") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (includeAllOption) {
                DropdownMenuItem(
                    text = { Text("全部学期") },
                    onClick = {
                        expanded = false
                        onValueChange(HISTORY_ALL_TERMS)
                    },
                )
            }
            terms.forEach { term ->
                DropdownMenuItem(
                    text = { Text(term.label.ifBlank { term.value }) },
                    onClick = {
                        expanded = false
                        onValueChange(term.value)
                    },
                )
            }
        }
    }
}

@Composable
fun CalendarScreen(repository: ModuleRepository) {
    DataScreen(title = "学年日历", loader = { repository.calendar() }) { envelope ->
        val data = envelope.data
        val month = data.month
        val currentWeek = data.currentWeek
        if (!month.isNullOrBlank() || !currentWeek.isNullOrBlank()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!month.isNullOrBlank()) {
                        AssistChip(onClick = {}, label = { Text(month) })
                    }
                    if (!currentWeek.isNullOrBlank()) {
                        AssistChip(onClick = {}, label = { Text("第 $currentWeek 周") })
                    }
                }
            }
        }
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
        val currentTerm = data.currentTerm
        if (!currentTerm.isNullOrBlank()) {
            item {
                AssistChip(onClick = {}, label = { Text(currentTerm) })
            }
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
    var page by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf<LoadState<ModuleEnvelope<EmptyRoomData>>>(LoadState.Loading) }

    fun load() {
        scope.launch {
            state = LoadState.Loading
            runCatching { repository.emptyRooms(week = week, building = building.ifBlank { null }, room = room.ifBlank { null }) }
                .onSuccess {
                    page = 0
                    state = LoadState.Data(it)
                }
                .onFailure { state = LoadState.Error(it.message ?: "加载失败") }
        }
    }

    LaunchedEffect(Unit) { load() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                item {
                    EmptyRoomsMatrix(
                        data = envelope.data,
                        page = page,
                        onPageChange = { page = it },
                    )
                }
            }
        }
    }
}

private const val EmptyRoomsPageSize = 20
private const val EmptyRoomStateFree = "free"
private const val EmptyRoomStateBusy = "busy"
private const val EmptyRoomStateNotice = "notice"

@Composable
private fun EmptyRoomsMatrix(
    data: EmptyRoomData,
    page: Int,
    onPageChange: (Int) -> Unit,
) {
    val rooms = data.rooms
    val slotColumns = remember(data.slots, rooms) { emptyRoomSlotColumns(data) }
    if (rooms.isEmpty() || slotColumns.isEmpty()) {
        InfoCard("暂无空教室矩阵") {
            Text("当前查询没有返回可显示的空教室数据。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val pageCount = ((rooms.size + EmptyRoomsPageSize - 1) / EmptyRoomsPageSize).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pageCount - 1)
    LaunchedEffect(page, pageCount) {
        if (page != currentPage) onPageChange(currentPage)
    }

    val pageRooms = remember(rooms, currentPage) {
        rooms.drop(currentPage * EmptyRoomsPageSize).take(EmptyRoomsPageSize)
    }
    val dayGroups = remember(slotColumns) { emptyRoomDayGroups(slotColumns) }
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EmptyRoomLegend()
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val leftColumnWidth = 92.dp
                val minSlotWidth = 38.dp
                val availableSlotWidth = (maxWidth - leftColumnWidth) / slotColumns.size
                val slotWidth = if (availableSlotWidth > minSlotWidth) availableSlotWidth else minSlotWidth
                val dayHeaderHeight = 48.dp
                val periodHeaderHeight = 40.dp
                val roomRowHeight = 56.dp

                Column(Modifier.fillMaxWidth()) {
                    Row {
                        EmptyRoomCornerHeader(
                            width = leftColumnWidth,
                            dayHeaderHeight = dayHeaderHeight,
                            periodHeaderHeight = periodHeaderHeight,
                        )
                        Column(Modifier.horizontalScroll(horizontalScrollState)) {
                            EmptyRoomDayHeaderRow(
                                groups = dayGroups,
                                slotWidth = slotWidth,
                                height = dayHeaderHeight,
                            )
                            EmptyRoomPeriodHeaderRow(
                                slots = slotColumns,
                                slotWidth = slotWidth,
                                height = periodHeaderHeight,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .heightIn(max = 560.dp)
                            .verticalScroll(verticalScrollState),
                    ) {
                        Column(Modifier.width(leftColumnWidth)) {
                            pageRooms.forEach { row ->
                                EmptyRoomRoomCell(row = row, height = roomRowHeight)
                            }
                        }
                        Column(Modifier.horizontalScroll(horizontalScrollState)) {
                            pageRooms.forEach { row ->
                                EmptyRoomAvailabilityRow(
                                    row = row,
                                    slotCount = slotColumns.size,
                                    slotWidth = slotWidth,
                                    height = roomRowHeight,
                                )
                            }
                        }
                    }
                }
            }
        }
        EmptyRoomPager(
            currentPage = currentPage,
            pageCount = pageCount,
            totalRooms = rooms.size,
            onPageChange = onPageChange,
        )
    }
}

@Composable
private fun EmptyRoomLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EmptyRoomLegendItem(state = EmptyRoomStateFree, label = "空闲")
        EmptyRoomLegendItem(state = EmptyRoomStateBusy, label = "占用")
        EmptyRoomLegendItem(state = EmptyRoomStateNotice, label = "特殊")
    }
}

@Composable
private fun EmptyRoomLegendItem(state: String, label: String) {
    val colors = emptyRoomCellColors(state)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(18.dp)
                .height(18.dp)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                .background(colors.first),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyRoomCornerHeader(
    width: Dp,
    dayHeaderHeight: Dp,
    periodHeaderHeight: Dp,
) {
    Column(Modifier.width(width)) {
        EmptyRoomHeaderCell(
            text = "星期",
            modifier = Modifier
                .width(width)
                .height(dayHeaderHeight),
            fontWeight = FontWeight.Bold,
        )
        EmptyRoomHeaderCell(
            text = "教室/\n节次",
            modifier = Modifier
                .width(width)
                .height(periodHeaderHeight),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EmptyRoomDayHeaderRow(
    groups: List<EmptyRoomDayGroup>,
    slotWidth: Dp,
    height: Dp,
) {
    Row(Modifier.height(height)) {
        groups.forEach { group ->
            EmptyRoomHeaderCell(
                text = group.label,
                modifier = Modifier.width(slotWidth * group.span.toFloat()),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EmptyRoomPeriodHeaderRow(
    slots: List<EmptyRoomSlotHeader>,
    slotWidth: Dp,
    height: Dp,
) {
    Row(Modifier.height(height)) {
        slots.forEach { slot ->
            EmptyRoomHeaderCell(
                text = slot.period.toString(),
                modifier = Modifier.width(slotWidth),
            )
        }
    }
}

@Composable
private fun EmptyRoomHeaderCell(
    text: String,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    Box(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyRoomRoomCell(row: EmptyRoomRow, height: Dp) {
    Column(
        modifier = Modifier
            .width(92.dp)
            .height(height)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = row.room,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!row.seatLabel.isNullOrBlank()) {
            Text(
                text = "(${row.seatLabel})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyRoomAvailabilityRow(
    row: EmptyRoomRow,
    slotCount: Int,
    slotWidth: Dp,
    height: Dp,
) {
    Row(Modifier.height(height)) {
        repeat(slotCount) { index ->
            val state = emptyRoomCellState(row, index)
            val colors = emptyRoomCellColors(state)
            Box(
                modifier = Modifier
                    .width(slotWidth)
                    .fillMaxHeight()
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                    .background(colors.first),
                contentAlignment = Alignment.Center,
            ) {
                if (state == EmptyRoomStateNotice) {
                    Text(
                        text = "!",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.second,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyRoomPager(
    currentPage: Int,
    pageCount: Int,
    totalRooms: Int,
    onPageChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { onPageChange(currentPage - 1) },
            enabled = currentPage > 0,
        ) {
            Text("上一页")
        }
        Text(
            text = "第 ${currentPage + 1} / $pageCount 页 · 共 $totalRooms 间",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { onPageChange(currentPage + 1) },
            enabled = currentPage < pageCount - 1,
        ) {
            Text("下一页")
        }
    }
}

@Composable
private fun emptyRoomCellColors(state: String): Pair<Color, Color> {
    val colorScheme = MaterialTheme.colorScheme
    return when (state) {
        EmptyRoomStateBusy -> Color(0xFFE56666) to Color.White
        EmptyRoomStateNotice -> Color(0xFFD8CF4D) to Color(0xFF2F2B12)
        else -> Color.White to colorScheme.onSurface
    }
}

private data class EmptyRoomDayGroup(
    val label: String,
    val span: Int,
)

private fun emptyRoomSlotColumns(data: EmptyRoomData): List<EmptyRoomSlotHeader> {
    val maxRoomCells = data.rooms.maxOfOrNull { maxOf(it.cellStates.size, it.availability.size) } ?: 0
    if (data.slots.size >= maxRoomCells) return data.slots
    return data.slots + (data.slots.size until maxRoomCells).map { index ->
        EmptyRoomSlotHeader(day = "", date = null, period = index + 1)
    }
}

private fun emptyRoomDayGroups(slots: List<EmptyRoomSlotHeader>): List<EmptyRoomDayGroup> {
    if (slots.isEmpty()) return emptyList()
    val groups = mutableListOf<EmptyRoomDayGroup>()
    var currentLabel = emptyRoomSlotDayLabel(slots.first())
    var currentSpan = 0
    slots.forEach { slot ->
        val label = emptyRoomSlotDayLabel(slot)
        if (label != currentLabel && currentSpan > 0) {
            groups += EmptyRoomDayGroup(label = currentLabel, span = currentSpan)
            currentLabel = label
            currentSpan = 0
        }
        currentSpan += 1
    }
    groups += EmptyRoomDayGroup(label = currentLabel, span = currentSpan)
    return groups
}

private fun emptyRoomSlotDayLabel(slot: EmptyRoomSlotHeader): String =
    listOf(slot.day, slot.date)
        .filter { !it.isNullOrBlank() }
        .joinToString(" ")
        .ifBlank { "日期" }

private fun emptyRoomCellState(row: EmptyRoomRow, index: Int): String =
    row.cellStates.getOrNull(index)
        ?: row.availability.getOrNull(index)?.let { if (it) EmptyRoomStateFree else EmptyRoomStateBusy }
        ?: EmptyRoomStateFree

@Composable
private fun TimetableList(
    envelope: ModuleEnvelope<TimetableData>,
    currentWeek: Int?,
    selectedCourses: List<CourseEntry>,
    onSelect: (List<CourseEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val data = envelope.data
    val selectedKeys = selectedCourses.map(::courseEntryKey).toSet()
    val entries = data.entries.sortedWith(
        compareBy<CourseEntry> { timetableWeekdayIndex(data.days, it.weekday) }
            .thenBy { timetablePeriodIndex(data.periods, it.period) }
            .thenBy { it.courseName },
    )
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            val currentTerm = data.currentTerm
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!currentTerm.isNullOrBlank() || currentWeek != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!currentTerm.isNullOrBlank()) {
                            AssistChip(onClick = {}, label = { Text(currentTerm) })
                        }
                        if (currentWeek != null) {
                            AssistChip(onClick = {}, label = { Text("第 $currentWeek 周") })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("${entries.size} 门课程") })
                    if (data.days.isNotEmpty()) {
                        AssistChip(onClick = {}, label = { Text("${data.days.size} 天") })
                    }
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
            item {
                TimetableWeekCalendar(
                    data = data,
                    entries = entries,
                    currentWeek = currentWeek,
                    selectedKeys = selectedKeys,
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun TimetableWeekCalendar(
    data: TimetableData,
    entries: List<CourseEntry>,
    currentWeek: Int?,
    selectedKeys: Set<String>,
    onSelect: (List<CourseEntry>) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val dayColumns = remember(today) { timetableWeekColumns(today) }
    val slots = remember(data.periods, entries) { timetablePeriodSlots(data, entries) }
    val entriesByCell = remember(data.days, entries, currentWeek) {
        entries.groupBy { entry ->
            timetableCellKey(timetableWeekdayIndex(data.days, entry.weekday), entry.period)
        }.mapValues { (_, cellEntries) ->
            timetableVisibleCellEntries(cellEntries, currentWeek)
        }
    }
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val leftColumnWidth = 64.dp
            val minDayWidth = 108.dp
            val availableDayWidth = (maxWidth - leftColumnWidth) / dayColumns.size
            val dayWidth = if (availableDayWidth > minDayWidth) availableDayWidth else minDayWidth
            val contentWidth = leftColumnWidth + dayWidth * dayColumns.size.toFloat()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
            ) {
                Column(Modifier.width(contentWidth)) {
                    TimetableHeaderRow(
                        dayColumns = dayColumns,
                        today = today,
                        leftColumnWidth = leftColumnWidth,
                        dayWidth = dayWidth,
                    )
                    slots.forEach { slot ->
                        TimetablePeriodRow(
                            slot = slot,
                            dayColumns = dayColumns,
                            dayWidth = dayWidth,
                            leftColumnWidth = leftColumnWidth,
                            entriesByCell = entriesByCell,
                            selectedKeys = selectedKeys,
                            onSelect = onSelect,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimetableHeaderRow(
    dayColumns: List<TimetableDayColumn>,
    today: LocalDate,
    leftColumnWidth: androidx.compose.ui.unit.Dp,
    dayWidth: androidx.compose.ui.unit.Dp,
) {
    val lineColor = MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.height(64.dp)) {
        Box(
            modifier = Modifier
                .width(leftColumnWidth)
                .fillMaxHeight()
                .border(0.5.dp, lineColor)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("节次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        dayColumns.forEach { day ->
            val isToday = day.date == today
            Column(
                modifier = Modifier
                    .width(dayWidth)
                    .fillMaxHeight()
                    .border(0.5.dp, lineColor)
                    .background(if (isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = day.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = day.date.format(DateTimeFormatter.ofPattern("M/d")),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TimetablePeriodRow(
    slot: TimetablePeriodSlot,
    dayColumns: List<TimetableDayColumn>,
    dayWidth: androidx.compose.ui.unit.Dp,
    leftColumnWidth: androidx.compose.ui.unit.Dp,
    entriesByCell: Map<String, List<TimetableDisplayEntry>>,
    selectedKeys: Set<String>,
    onSelect: (List<CourseEntry>) -> Unit,
) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        TimetablePeriodCell(
            slot = slot,
            modifier = Modifier
                .width(leftColumnWidth)
                .fillMaxHeight()
                .heightIn(min = 112.dp),
        )
        dayColumns.forEach { day ->
            TimetableCourseCell(
                entries = entriesByCell[timetableCellKey(day.index, slot.period)].orEmpty(),
                selectedKeys = selectedKeys,
                onSelect = onSelect,
                modifier = Modifier
                    .width(dayWidth)
                    .fillMaxHeight()
                    .heightIn(min = 112.dp),
            )
        }
    }
}

@Composable
private fun TimetablePeriodCell(
    slot: TimetablePeriodSlot,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.surfaceVariant)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = timetablePeriodNumber(slot.period),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (!slot.timeRange.isNullOrBlank()) {
            Text(
                text = slot.timeRange.replace("-", "\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun TimetableCourseCell(
    entries: List<TimetableDisplayEntry>,
    selectedKeys: Set<String>,
    onSelect: (List<CourseEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.surfaceVariant)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val selectableEntries = entries.map { it.entry }
        entries.forEach { displayEntry ->
            TimetableCourseBlock(
                entry = displayEntry.entry,
                activeInCurrentWeek = displayEntry.activeInCurrentWeek,
                selected = courseEntryKey(displayEntry.entry) in selectedKeys,
                onSelect = { onSelect(selectableEntries) },
            )
        }
    }
}

@Composable
private fun TimetableCourseBlock(
    entry: CourseEntry,
    activeInCurrentWeek: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colors = timetableCourseColors(entry)
    val contentAlpha = if (activeInCurrentWeek) 1f else 0.42f
    val container = if (activeInCurrentWeek) colors.container else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val content = if (activeInCurrentWeek) colors.content else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(contentAlpha)
            .clickable { onSelect() },
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else content.copy(alpha = 0.18f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = entry.courseName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.teacher.isNullOrBlank()) {
                Text(
                    text = entry.teacher,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            entry.locationLabel()?.let { location ->
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CourseDetailPanel(
    entries: List<CourseEntry>,
    currentWeek: Int?,
    homeworkStates: Map<String, LoadState<List<HomeworkItem>>>,
    resourceStates: Map<String, LoadState<ModuleEnvelope<CourseResourcesData>>>,
    courseResourceRepository: CourseResourceRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val panelKey = courseSelectionKey(entries)
    var downloading by remember(panelKey) { mutableStateOf<String?>(null) }
    var downloadError by remember(panelKey) { mutableStateOf<String?>(null) }

    LazyColumn(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        entries.forEachIndexed { index, entry ->
            val entryKey = courseEntryKey(entry)
            val homeworkState = homeworkStates[entryKey] ?: LoadState.Loading
            val resourceState = resourceStates[entryKey] ?: LoadState.Loading

            if (index > 0) {
                item(key = "$entryKey-divider") { HorizontalDivider() }
            }
            item(key = "$entryKey-title") {
                SectionTitle(
                    title = entry.courseName,
                    subtitle = listOfNotNull(
                        "${index + 1}/${entries.size}".takeIf { entries.size > 1 },
                        entry.courseCode,
                        entry.teacher,
                    ).joinToString(" · "),
                )
            }
            item(key = "$entryKey-info") {
                InfoCard("课程信息") {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("星期", entry.weekday, Modifier.weight(1f))
                        KeyValue("节次", entry.period, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("当前周", currentWeek?.toString(), Modifier.weight(1f))
                        KeyValue("上课时间", entry.timeRange, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue(
                            "本周状态",
                            currentWeek?.let { if (timetableEntryMatchesWeek(entry, it)) "本周上课" else "本周不上课" },
                            Modifier.weight(1f),
                        )
                    }
                    KeyValue("周次", entry.weeks)
                    KeyValue("地点", entry.locationLabel())
                }
            }
            item(key = "$entryKey-homework-divider") { HorizontalDivider() }
            item(key = "$entryKey-homework-title") { Text("作业", style = MaterialTheme.typography.titleMedium) }
            when (val current = homeworkState) {
                LoadState.Loading, is LoadState.Error -> item(key = "$entryKey-homework-state") { LoadingOrError(current) }
                is LoadState.Data -> {
                    if (current.value.isEmpty()) {
                        item(key = "$entryKey-homework-empty") { Text("暂无匹配作业。", style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        items(
                            current.value,
                            key = { "${entryKey}-homework-${it.homeworkId ?: "${it.courseId}-${it.title}".hashCode()}" },
                        ) { homework ->
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
            item(key = "$entryKey-resource-divider") { HorizontalDivider() }
            item(key = "$entryKey-resource-title") { Text("课程资源", style = MaterialTheme.typography.titleMedium) }
            if (!downloadError.isNullOrBlank()) {
                item(key = "$entryKey-download-error") { Text(downloadError.orEmpty(), color = MaterialTheme.colorScheme.error) }
            }
            when (val current = resourceState) {
                LoadState.Loading, is LoadState.Error -> item(key = "$entryKey-resource-state") { LoadingOrError(current) }
                is LoadState.Data -> {
                    val data = current.value.data
                    if (data.folders.isEmpty() && data.resources.isEmpty()) {
                        item(key = "$entryKey-resource-empty") { Text("暂无匹配资源。", style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        items(data.folders, key = { "$entryKey-folder-${it.folderId}" }) { folder ->
                            InfoCard(folder.name, subtitle = "目录 ${folder.folderId}") {
                                Text("请到课程资源页继续浏览该目录。", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        items(data.resources, key = { "$entryKey-resource-${it.rpId}" }) { resource ->
                            val downloadKey = "$entryKey|${resource.rpId}"
                            InfoCard(
                                title = resource.name,
                                subtitle = resource.uploadedAt,
                                trailing = {
                                    if (resource.canDownload) {
                                        Button(
                                            enabled = downloading == null,
                                            onClick = {
                                                scope.launch {
                                                    downloading = downloadKey
                                                    downloadError = null
                                                    runCatching { courseResourceRepository.download(resource.rpId, resource.name, resource.extension) }
                                                        .onSuccess { openFile(context, it) }
                                                        .onFailure { downloadError = it.message ?: "下载失败" }
                                                    downloading = null
                                                }
                                            },
                                        ) {
                                            Text(if (downloading == downloadKey) "下载中" else "下载")
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
}

private data class TimetableDayColumn(
    val index: Int,
    val label: String,
    val date: LocalDate,
)

private data class TimetablePeriodSlot(
    val period: String,
    val timeRange: String?,
)

private data class TimetableCourseColors(
    val container: Color,
    val content: Color,
)

private data class TimetableDisplayEntry(
    val entry: CourseEntry,
    val activeInCurrentWeek: Boolean,
)

private fun timetableWeekColumns(today: LocalDate): List<TimetableDayColumn> {
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    return labels.mapIndexed { index, label ->
        TimetableDayColumn(
            index = index,
            label = label,
            date = monday.plusDays(index.toLong()),
        )
    }
}

private fun timetablePeriodSlots(data: TimetableData, entries: List<CourseEntry>): List<TimetablePeriodSlot> {
    val periods = (data.periods + entries.map { it.period })
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    return periods.map { period ->
        TimetablePeriodSlot(
            period = period,
            timeRange = entries.firstOrNull { it.period == period && !it.timeRange.isNullOrBlank() }?.timeRange,
        )
    }
}

private fun timetableVisibleCellEntries(entries: List<CourseEntry>, currentWeek: Int?): List<TimetableDisplayEntry> {
    val displayEntries = entries.map { entry ->
        TimetableDisplayEntry(
            entry = entry,
            activeInCurrentWeek = timetableEntryMatchesWeek(entry, currentWeek),
        )
    }
    if (currentWeek == null) return displayEntries
    return displayEntries.filter { it.activeInCurrentWeek }.ifEmpty { displayEntries }
}

private fun timetableCellKey(dayIndex: Int, period: String): String =
    "$dayIndex|$period"

private fun timetableWeekdayIndex(days: List<String>, weekday: String): Int {
    parseWeekdayIndex(weekday)?.let { return it }
    val sourceIndex = days.indexOf(weekday)
    return sourceIndex.takeIf { it in 0..6 } ?: Int.MAX_VALUE
}

private fun parseWeekdayIndex(value: String): Int? {
    val text = value.trim().lowercase()
    return when {
        text.contains("周一") || text.contains("星期一") || text.contains("mon") || text == "day 1" -> 0
        text.contains("周二") || text.contains("星期二") || text.contains("tue") || text == "day 2" -> 1
        text.contains("周三") || text.contains("星期三") || text.contains("wed") || text == "day 3" -> 2
        text.contains("周四") || text.contains("星期四") || text.contains("thu") || text == "day 4" -> 3
        text.contains("周五") || text.contains("星期五") || text.contains("fri") || text == "day 5" -> 4
        text.contains("周六") || text.contains("星期六") || text.contains("sat") || text == "day 6" -> 5
        text.contains("周日") || text.contains("星期日") || text.contains("星期天") || text.contains("sun") || text == "day 7" -> 6
        else -> Regex("""\d+""").find(text)?.value?.toIntOrNull()?.minus(1)?.takeIf { it in 0..6 }
    }
}

private fun timetablePeriodIndex(periods: List<String>, period: String): Int {
    val sourceIndex = periods.indexOf(period)
    if (sourceIndex >= 0) return sourceIndex
    return Regex("""\d+""").find(period)?.value?.toIntOrNull() ?: Int.MAX_VALUE
}

private fun timetablePeriodNumber(period: String): String =
    Regex("""\d+""").find(period)?.value ?: period

@Composable
private fun timetableCourseColors(entry: CourseEntry): TimetableCourseColors {
    val colorScheme = MaterialTheme.colorScheme
    val palette = listOf(
        colorScheme.primaryContainer to colorScheme.onPrimaryContainer,
        colorScheme.secondaryContainer to colorScheme.onSecondaryContainer,
        colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer,
        colorScheme.errorContainer to colorScheme.onErrorContainer,
        colorScheme.surfaceVariant to colorScheme.onSurfaceVariant,
    )
    val key = listOf(entry.courseCode, entry.section.orEmpty(), entry.courseName).joinToString("|")
    val index = (key.hashCode() and Int.MAX_VALUE) % palette.size
    val (container, content) = palette[index]
    return TimetableCourseColors(container = container, content = content)
}

private fun courseEntryKey(entry: CourseEntry): String =
    listOf(entry.courseCode, entry.section.orEmpty(), entry.courseName, entry.weekday, entry.period, entry.weeks.orEmpty(), entry.locationLabel().orEmpty())
        .joinToString("|")

private fun courseSelectionKey(entries: List<CourseEntry>): String =
    entries.joinToString("||", transform = ::courseEntryKey)

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

private fun timetableEntryMatchesWeek(entry: CourseEntry, currentWeek: Int?): Boolean =
    currentWeek?.let { timetableEntryMatchesWeek(entry, it) } ?: true

private fun timetableEntryMatchesWeek(entry: CourseEntry, currentWeek: Int): Boolean =
    timetableWeeksContain(entry.weeks, currentWeek)

private fun timetableWeeksContain(weeks: String?, currentWeek: Int): Boolean {
    val text = weeks?.trim().orEmpty()
    if (text.isBlank()) return true

    val normalized = text
        .lowercase()
        .replace('（', '(')
        .replace('）', ')')
        .replace('，', ',')
        .replace('、', ',')
    val oddOnly = normalized.contains("单") || normalized.contains("odd")
    val evenOnly = normalized.contains("双") || normalized.contains("even")
    if (oddOnly && currentWeek % 2 == 0) return false
    if (evenOnly && currentWeek % 2 != 0) return false

    val weekRanges = Regex("""(\d{1,2})(?:\s*[-~－—]\s*(\d{1,2}))?""")
        .findAll(normalized)
        .mapNotNull { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val end = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: start
            minOf(start, end)..maxOf(start, end)
        }
        .toList()
    if (weekRanges.isEmpty()) return true
    return weekRanges.any { currentWeek in it }
}

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
