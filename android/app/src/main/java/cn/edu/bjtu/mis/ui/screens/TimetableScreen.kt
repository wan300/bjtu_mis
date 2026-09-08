package cn.edu.bjtu.mis.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.repository.CourseResourceRepository
import cn.edu.bjtu.mis.data.exporting.ScheduleExportContentBuilder
import cn.edu.bjtu.mis.data.exporting.ScheduleExportFormat
import cn.edu.bjtu.mis.data.repository.DocumentPreview
import cn.edu.bjtu.mis.data.repository.HomeworkAttachmentRepository
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.CourseResourceItem
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.DEFAULT_USER_COURSE_MAX_WEEK
import cn.edu.bjtu.mis.model.HomeworkAttachment
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.TimetableData
import cn.edu.bjtu.mis.model.UserCourseDraft
import cn.edu.bjtu.mis.model.UserCourseDurationType
import cn.edu.bjtu.mis.model.formatUserCourseWeeks
import cn.edu.bjtu.mis.model.normalizedTimetablePeriodNumber
import cn.edu.bjtu.mis.model.parseUserCourseWeeks
import cn.edu.bjtu.mis.model.timetableEntriesConflict
import cn.edu.bjtu.mis.model.userCourseWeekdayLabel
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.SectionTitle
import cn.edu.bjtu.mis.widget.TimetableWidgetProvider
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val UserCourseColors = listOf(
    Color(0xFFE57373),
    Color(0xFF9575CD),
    Color(0xFF64B5F6),
    Color(0xFF4DB6AC),
    Color(0xFFFFB74D),
    Color(0xFF81C784),
    Color(0xFFF06292),
    Color(0xFF7986CB),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    repository: ModuleRepository,
    courseResourceRepository: CourseResourceRepository,
    homeworkAttachmentRepository: HomeworkAttachmentRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    onOpenHomeworkDetail: (HomeworkItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val exportSchedule = rememberScheduleExportLauncher()
    val isWide = configuration.screenWidthDp >= 840
    var state by remember { mutableStateOf<LoadState<ModuleEnvelope<TimetableData>>>(LoadState.Loading) }
    var selectedCourses by remember { mutableStateOf<List<CourseEntry>>(emptyList()) }
    var homeworkStates by remember { mutableStateOf<Map<String, LoadState<List<HomeworkItem>>>>(emptyMap()) }
    var resourceStates by remember { mutableStateOf<Map<String, LoadState<ModuleEnvelope<CourseResourcesData>>>>(emptyMap()) }
    var currentWeek by remember { mutableStateOf<Int?>(null) }
    var courseEditorSeed by remember { mutableStateOf<UserCourseEditorSeed?>(null) }
    var courseEditorError by remember { mutableStateOf<String?>(null) }
    var pendingDeleteCourse by remember { mutableStateOf<CourseEntry?>(null) }
    var timetableExportTarget by remember { mutableStateOf<TimetableData?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val editorSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun loadTimetable(strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst) {
        scope.launch {
            state = LoadState.Loading
            runCatching { repository.timetable(strategy) }
                .onSuccess {
                    state = LoadState.Data(it)
                    if (strategy != ModuleLoadStrategy.CacheOnly) {
                        TimetableWidgetProvider.refreshAll(context)
                    }
                }
                .onFailure { state = LoadState.Error(it.message ?: "加载失败") }
        }
    }

    fun addTimetableWidget() {
        val supported = TimetableWidgetProvider.requestPin(context)
        if (!supported) {
            Toast.makeText(
                context,
                "当前桌面不支持自动添加，请长按桌面从小组件列表添加",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun loadDetail(entries: List<CourseEntry>) {
        val nextSelection = entries.ifEmpty { return }
        selectedCourses = nextSelection
        val selectionKey = courseSelectionKey(nextSelection)
        val remoteEntries = nextSelection.filterNot { it.isUserCreated }
        homeworkStates = nextSelection.associate { entry ->
            courseEntryKey(entry) to if (entry.isUserCreated) LoadState.Data(emptyList()) else LoadState.Loading
        }
        resourceStates = nextSelection.associate { entry ->
            courseEntryKey(entry) to if (entry.isUserCreated) {
                LoadState.Data(
                    ModuleEnvelope(
                        module = "course_resources",
                        sourceSystem = "local",
                        coverage = CoverageLevel.Provisional,
                        data = CourseResourcesData(),
                    )
                )
            } else {
                LoadState.Loading
            }
        }
        if (remoteEntries.isNotEmpty()) scope.launch {
            runCatching {
                repository.homework("all").data.items
            }.onSuccess { homework ->
                if (courseSelectionKey(selectedCourses) == selectionKey) {
                    homeworkStates = nextSelection.associate { entry ->
                        courseEntryKey(entry) to if (entry.isUserCreated) {
                            LoadState.Data(emptyList())
                        } else {
                            LoadState.Data(
                                homework.filter { matchesCourse(entry, it) }
                                    .sortedWith(compareBy<HomeworkItem> { it.dueAt ?: "9999" }.thenBy { it.title })
                            )
                        }
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
        remoteEntries.forEach { entry ->
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

    fun saveUserCourse(draft: UserCourseDraft) {
        courseEditorError = null
        scope.launch {
            runCatching { repository.saveUserCourse(draft) }
                .onSuccess {
                    TimetableWidgetProvider.refreshAll(context)
                    courseEditorSeed = null
                    selectedCourses = emptyList()
                    loadTimetable()
                }
                .onFailure { courseEditorError = it.message ?: "保存课程失败" }
        }
    }

    fun deleteUserCourse(entry: CourseEntry) {
        val localId = entry.localId ?: return
        scope.launch {
            runCatching { repository.deleteUserCourse(localId) }
                .onSuccess {
                    TimetableWidgetProvider.refreshAll(context)
                    pendingDeleteCourse = null
                    selectedCourses = emptyList()
                    loadTimetable()
                }
                .onFailure { courseEditorError = it.message ?: "删除课程失败" }
        }
    }

    fun exportTimetable(data: TimetableData, format: ScheduleExportFormat) {
        if (data.entries.isEmpty()) {
            Toast.makeText(context, "当前课表为空", Toast.LENGTH_SHORT).show()
        }
        val document = ScheduleExportContentBuilder.buildTimetable(
            data = data,
            currentWeek = currentWeek,
        )
        exportSchedule(document, format)
    }

    LaunchedEffect(Unit) {
        loadTimetable(initialLoadStrategy)
        runCatching { repository.calendar(strategy = initialLoadStrategy) }
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
                        onAddToHomeWidget = ::addTimetableWidget,
                        onExport = { timetableExportTarget = envelope.data },
                        onAddUserCourse = { day, slot ->
                            courseEditorError = null
                            selectedCourses = emptyList()
                            courseEditorSeed = userCourseEditorSeedForSlot(
                                day = day,
                                slot = slot,
                                currentWeek = currentWeek,
                                maxWeek = timetableMaxWeek(envelope.data.entries),
                            )
                        },
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
                                homeworkAttachmentRepository = homeworkAttachmentRepository,
                                onOpenHomeworkDetail = onOpenHomeworkDetail,
                                onEditUserCourse = {
                                    courseEditorError = null
                                    selectedCourses = emptyList()
                                    courseEditorSeed = userCourseEditorSeedForEntry(
                                        entry = it,
                                        currentWeek = currentWeek,
                                        maxWeek = timetableMaxWeek(envelope.data.entries),
                                    )
                                },
                                onDeleteUserCourse = { pendingDeleteCourse = it },
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
                    onAddToHomeWidget = ::addTimetableWidget,
                    onExport = { timetableExportTarget = envelope.data },
                    onAddUserCourse = { day, slot ->
                        courseEditorError = null
                        selectedCourses = emptyList()
                        courseEditorSeed = userCourseEditorSeedForSlot(
                            day = day,
                            slot = slot,
                            currentWeek = currentWeek,
                            maxWeek = timetableMaxWeek(envelope.data.entries),
                        )
                    },
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
                            homeworkAttachmentRepository = homeworkAttachmentRepository,
                            onOpenHomeworkDetail = onOpenHomeworkDetail,
                            onEditUserCourse = {
                                courseEditorError = null
                                selectedCourses = emptyList()
                                courseEditorSeed = userCourseEditorSeedForEntry(
                                    entry = it,
                                    currentWeek = currentWeek,
                                    maxWeek = timetableMaxWeek(envelope.data.entries),
                                )
                            },
                            onDeleteUserCourse = { pendingDeleteCourse = it },
                            modifier = Modifier.heightIn(max = 720.dp),
                        )
                    }
                }
            }
        }
    }

    timetableExportTarget?.let { data ->
        TimetableExportDialog(
            onDismiss = { timetableExportTarget = null },
            onExport = { format ->
                timetableExportTarget = null
                exportTimetable(data, format)
            },
        )
    }

    courseEditorSeed?.let { seed ->
        val currentState = state
        val timetableData = if (currentState is LoadState.Data) currentState.value.data else TimetableData()
        ModalBottomSheet(
            onDismissRequest = {
                courseEditorSeed = null
                courseEditorError = null
            },
            sheetState = editorSheetState,
            modifier = Modifier.fillMaxHeight(0.96f),
        ) {
            UserCourseEditorSheet(
                seed = seed,
                data = timetableData,
                currentWeek = currentWeek,
                saveError = courseEditorError,
                onDismiss = {
                    courseEditorSeed = null
                    courseEditorError = null
                },
                onSave = ::saveUserCourse,
            )
        }
    }

    pendingDeleteCourse?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDeleteCourse = null },
            title = { Text("删除课程") },
            text = { Text("确定删除“${entry.courseName}”吗？") },
            confirmButton = {
                TextButton(onClick = { deleteUserCourse(entry) }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteCourse = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun TimetableList(
    envelope: ModuleEnvelope<TimetableData>,
    currentWeek: Int?,
    selectedCourses: List<CourseEntry>,
    onSelect: (List<CourseEntry>) -> Unit,
    onAddToHomeWidget: () -> Unit,
    onExport: () -> Unit,
    onAddUserCourse: (TimetableDayColumn, TimetablePeriodSlot) -> Unit,
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
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(onClick = {}, label = { Text("${entries.size} 门课程") })
                    if (data.days.isNotEmpty()) {
                        AssistChip(onClick = {}, label = { Text("${data.days.size} 天") })
                    }
                    AssistChip(
                        onClick = onAddToHomeWidget,
                        label = { Text("添加到桌面") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.AddToHomeScreen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                    AssistChip(
                        onClick = onExport,
                        label = { Text("导出") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                InfoCard("暂无课表") {
                    Text("当前没有可显示的课程。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            TimetableWeekCalendar(
                data = data,
                entries = entries,
                currentWeek = currentWeek,
                selectedKeys = selectedKeys,
                onSelect = onSelect,
                onAddUserCourse = onAddUserCourse,
            )
        }
    }
}

@Composable
private fun TimetableExportDialog(
    onDismiss: () -> Unit,
    onExport: (ScheduleExportFormat) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出课表") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("选择导出格式。")
                OutlinedButton(
                    onClick = { onExport(ScheduleExportFormat.Pdf) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导出 PDF")
                }
                OutlinedButton(
                    onClick = { onExport(ScheduleExportFormat.Png) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导出 PNG")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun TimetableWeekCalendar(
    data: TimetableData,
    entries: List<CourseEntry>,
    currentWeek: Int?,
    selectedKeys: Set<String>,
    onSelect: (List<CourseEntry>) -> Unit,
    onAddUserCourse: (TimetableDayColumn, TimetablePeriodSlot) -> Unit,
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
                            onAddUserCourse = onAddUserCourse,
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
    onAddUserCourse: (TimetableDayColumn, TimetablePeriodSlot) -> Unit,
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
                onAdd = { onAddUserCourse(day, slot) },
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
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(0.5.dp, MaterialTheme.colorScheme.surfaceVariant)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val selectableEntries = entries.map { it.entry }
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onAdd() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
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

private data class UserCourseEditorSeed(
    val id: Long? = null,
    val courseName: String = "",
    val weekdayIndex: Int,
    val period: String,
    val periodNumber: Int,
    val timeRange: String? = null,
    val startWeek: Int,
    val endWeek: Int,
    val weeksText: String? = null,
    val durationType: UserCourseDurationType,
    val teacher: String = "",
    val locationText: String = "",
    val remark: String = "",
    val colorIndex: Int = 0,
)

private data class TimetablePeriodChoice(
    val period: String,
    val periodNumber: Int,
    val timeRange: String?,
)

@Composable
private fun UserCourseEditorSheet(
    seed: UserCourseEditorSeed,
    data: TimetableData,
    currentWeek: Int?,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (UserCourseDraft) -> Unit,
) {
    var courseName by remember(seed) { mutableStateOf(seed.courseName) }
    var weekdayIndex by remember(seed) { mutableStateOf(seed.weekdayIndex.coerceIn(0, 6)) }
    var period by remember(seed) { mutableStateOf(seed.period) }
    var periodNumber by remember(seed) { mutableStateOf(seed.periodNumber.coerceAtLeast(1)) }
    var timeRange by remember(seed) { mutableStateOf(seed.timeRange) }
    var durationType by remember(seed) { mutableStateOf(seed.durationType) }
    var weekText by remember(seed) { mutableStateOf(seed.startWeek.toString()) }
    val weekPickerMaxWeek = remember(data.entries, seed.endWeek) {
        maxOf(DEFAULT_USER_COURSE_MAX_WEEK, seed.endWeek, timetableMaxWeek(data.entries))
    }
    var longTermWeeks by remember(seed, weekPickerMaxWeek) {
        mutableStateOf(parseUserCourseWeeks(seed.weeksText, seed.startWeek, seed.endWeek, weekPickerMaxWeek))
    }
    var showWeekPicker by remember(seed) { mutableStateOf(false) }
    var teacher by remember(seed) { mutableStateOf(seed.teacher) }
    var locationText by remember(seed) { mutableStateOf(seed.locationText) }
    var remark by remember(seed) { mutableStateOf(seed.remark) }
    var colorIndex by remember(seed) { mutableStateOf(seed.colorIndex.coerceIn(0, UserCourseColors.lastIndex)) }

    val periodChoices = remember(data.periods, data.entries, seed) {
        val choices = timetablePeriodSlots(data, data.entries).map {
            TimetablePeriodChoice(
                period = it.period,
                periodNumber = normalizedTimetablePeriodNumber(it.period) ?: 1,
                timeRange = it.timeRange,
            )
        }
        (choices + TimetablePeriodChoice(seed.period, seed.periodNumber, seed.timeRange))
            .distinctBy { it.periodNumber }
            .sortedBy { it.periodNumber }
    }
    val longTermWeekText = remember(longTermWeeks, weekPickerMaxWeek) {
        formatUserCourseWeeks(longTermWeeks, weekPickerMaxWeek)
    }
    val temporaryWeek = weekText.toIntOrNull()
    val chosenStartWeek = if (durationType == UserCourseDurationType.Temporary) {
        temporaryWeek
    } else {
        longTermWeeks.minOrNull()
    }
    val chosenEndWeek = if (durationType == UserCourseDurationType.Temporary) {
        temporaryWeek
    } else {
        longTermWeeks.maxOrNull()
    }
    val weekError = if (durationType == UserCourseDurationType.Temporary) {
        temporaryWeek == null || temporaryWeek < 1
    } else {
        longTermWeeks.isEmpty()
    }
    val candidateWeeksText = if (durationType == UserCourseDurationType.Temporary) {
        chosenStartWeek?.let { formatUserCourseWeeks(it, it) }
    } else {
        longTermWeekText
    }
    val candidateEntry = CourseEntry(
        weekday = userCourseWeekdayLabel(weekdayIndex),
        period = period,
        timeRange = timeRange,
        courseCode = "LOCAL-${seed.id ?: "new"}",
        section = durationType.name,
        courseName = courseName.ifBlank { "未命名课程" },
        teacher = teacher.takeIf { it.isNotBlank() },
        weeks = if (!weekError) candidateWeeksText else null,
        locationText = locationText.takeIf { it.isNotBlank() },
        localId = seed.id,
        remark = remark.takeIf { it.isNotBlank() },
        colorIndex = colorIndex,
        isUserCreated = true,
    )
    val conflicts = if (courseName.isBlank() || weekError) {
        emptyList()
    } else {
        data.entries.filter { timetableEntriesConflict(candidateEntry, it) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (seed.id == null) "添加课程" else "编辑课程", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
        OutlinedTextField(
            value = courseName,
            onValueChange = { courseName = it },
            label = { Text("课程名称") },
            singleLine = true,
            isError = courseName.isBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("星期", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (0..6).forEach { index ->
                FilterChip(
                    selected = weekdayIndex == index,
                    onClick = { weekdayIndex = index },
                    label = { Text(userCourseWeekdayLabel(index)) },
                )
            }
        }
        Text("节次", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            periodChoices.forEach { choice ->
                FilterChip(
                    selected = periodNumber == choice.periodNumber,
                    onClick = {
                        period = choice.period
                        periodNumber = choice.periodNumber
                        timeRange = choice.timeRange
                    },
                    label = { Text(timetablePeriodNumber(choice.period)) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = durationType == UserCourseDurationType.Temporary,
                onClick = {
                    durationType = UserCourseDurationType.Temporary
                    val fallbackWeek = currentWeek ?: seed.startWeek.coerceAtLeast(1)
                    weekText = fallbackWeek.toString()
                },
                label = { Text("临时") },
            )
            FilterChip(
                selected = durationType == UserCourseDurationType.LongTerm,
                onClick = {
                    val wasLongTerm = durationType == UserCourseDurationType.LongTerm
                    durationType = UserCourseDurationType.LongTerm
                    if (!wasLongTerm) {
                        longTermWeeks = (1..DEFAULT_USER_COURSE_MAX_WEEK).toSet()
                    }
                },
                label = { Text("长期") },
            )
        }
        if (durationType == UserCourseDurationType.Temporary) {
            OutlinedTextField(
                value = weekText,
                onValueChange = { weekText = it.filter(Char::isDigit) },
                label = { Text("周次") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = weekError,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            WeekSelectionField(
                weekText = longTermWeekText,
                isError = weekError,
                onClick = { showWeekPicker = true },
            )
            if (showWeekPicker) {
                WeekSelectionDialog(
                    maxWeek = weekPickerMaxWeek,
                    selectedWeeks = longTermWeeks,
                    onDismiss = { showWeekPicker = false },
                    onConfirm = { weeks ->
                        longTermWeeks = weeks
                        showWeekPicker = false
                    },
                )
            }
        }
        OutlinedTextField(
            value = teacher,
            onValueChange = { teacher = it },
            label = { Text("授课老师（可不填）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = locationText,
            onValueChange = { locationText = it },
            label = { Text("上课地点（可不填）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = remark,
            onValueChange = { remark = it },
            label = { Text("备注（可不填）") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("颜色", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UserCourseColors.forEachIndexed { index, color ->
                FilterChip(
                    selected = colorIndex == index,
                    onClick = { colorIndex = index },
                    label = { Text("颜色 ${index + 1}") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = color.copy(alpha = 0.28f),
                    ),
                )
            }
        }
        if (conflicts.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = "该时段与 ${conflicts.take(3).joinToString("、") { it.courseName }} 冲突，仍可保存。",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (!saveError.isNullOrBlank()) {
            Text(saveError, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("取消")
            }
            Button(
                enabled = courseName.isNotBlank() && !weekError,
                onClick = {
                    val start = chosenStartWeek ?: return@Button
                    val end = chosenEndWeek ?: return@Button
                    val first = minOf(start, end).coerceAtLeast(1)
                    val last = maxOf(start, end).coerceAtLeast(1)
                    onSave(
                        UserCourseDraft(
                            id = seed.id,
                            courseName = courseName,
                            weekday = userCourseWeekdayLabel(weekdayIndex),
                            weekdayIndex = weekdayIndex,
                            period = period,
                            periodNumber = periodNumber,
                            timeRange = timeRange,
                            startWeek = first,
                            endWeek = if (durationType == UserCourseDurationType.Temporary) first else last,
                            weeksText = candidateWeeksText,
                            durationType = durationType,
                            teacher = teacher,
                            locationText = locationText,
                            remark = remark,
                            colorIndex = colorIndex,
                        )
                    )
                },
                modifier = Modifier.weight(1f),
            ) {
                Text("保存")
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun WeekSelectionField(
    weekText: String,
    isError: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            1.dp,
            if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "周次",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = weekText,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "选择",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun WeekSelectionDialog(
    maxWeek: Int,
    selectedWeeks: Set<Int>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
) {
    val safeMaxWeek = maxWeek.coerceIn(DEFAULT_USER_COURSE_MAX_WEEK, 60)
    var draftWeeks by remember(selectedWeeks, safeMaxWeek) {
        mutableStateOf(selectedWeeks.filter { it in 1..safeMaxWeek }.toSet())
    }

    fun applyWeeks(weeks: Iterable<Int>) {
        draftWeeks = weeks.filter { it in 1..safeMaxWeek }.toSet()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("请选择周次") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val weekRows = (1..safeMaxWeek).chunked(6)
                weekRows.forEach { rowWeeks ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowWeeks.forEach { week ->
                            WeekNumberButton(
                                week = week,
                                selected = week in draftWeeks,
                                onClick = {
                                    draftWeeks = if (week in draftWeeks) {
                                        draftWeeks - week
                                    } else {
                                        draftWeeks + week
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(6 - rowWeeks.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = draftWeeks == (1..safeMaxWeek).toSet(),
                        onClick = { applyWeeks(1..safeMaxWeek) },
                        label = { Text("全周") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = draftWeeks == (1..safeMaxWeek).filter { it % 2 == 1 }.toSet(),
                        onClick = { applyWeeks((1..safeMaxWeek).filter { it % 2 == 1 }) },
                        label = { Text("单周") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = draftWeeks == (1..safeMaxWeek).filter { it % 2 == 0 }.toSet(),
                        onClick = { applyWeeks((1..safeMaxWeek).filter { it % 2 == 0 }) },
                        label = { Text("双周") },
                        modifier = Modifier.weight(1f),
                    )
                }

                val draftSummary = draftWeeks
                    .takeIf { it.isNotEmpty() }
                    ?.let { formatUserCourseWeeks(it, safeMaxWeek) }
                    ?: "未选择"
                Text(
                    text = "已选：$draftSummary",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        confirmButton = {
            TextButton(
                enabled = draftWeeks.isNotEmpty(),
                onClick = { onConfirm(draftWeeks) },
            ) {
                Text("确定")
            }
        },
    )
}

@Composable
private fun WeekNumberButton(
    week: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(48.dp)
                .height(48.dp)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = containerColor,
            contentColor = contentColor,
            border = if (selected) {
                null
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = week.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
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
    homeworkAttachmentRepository: HomeworkAttachmentRepository,
    onOpenHomeworkDetail: (HomeworkItem) -> Unit,
    onEditUserCourse: (CourseEntry) -> Unit,
    onDeleteUserCourse: (CourseEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val panelKey = courseSelectionKey(entries)
    var downloading by remember(panelKey) { mutableStateOf<String?>(null) }
    var previewing by remember(panelKey) { mutableStateOf<String?>(null) }
    var previewTarget by remember(panelKey) { mutableStateOf<TimetableResourcePreviewTarget?>(null) }
    var downloadError by remember(panelKey) { mutableStateOf<String?>(null) }
    var attachmentBusyKey by remember(panelKey) { mutableStateOf<String?>(null) }
    var attachmentError by remember(panelKey) { mutableStateOf<String?>(null) }
    var attachmentPreviewTarget by remember(panelKey) { mutableStateOf<HomeworkAttachmentPreviewTarget?>(null) }

    fun downloadResource(resource: CourseResourceItem, actionKey: String) {
        scope.launch {
            downloading = actionKey
            downloadError = null
            runCatching { courseResourceRepository.download(resource.rpId, resource.name, resource.extension) }
                .onSuccess {
                    if (!openFile(context, it)) {
                        downloadError = "已下载，但未找到可打开该文件的应用"
                    }
                }
                .onFailure { downloadError = it.message ?: "下载失败" }
            downloading = null
        }
    }

    fun previewResource(entry: CourseEntry, resource: CourseResourceItem, actionKey: String) {
        scope.launch {
            previewing = actionKey
            downloadError = null
            runCatching { courseResourceRepository.preview(resource) }
                .onSuccess { preview ->
                    previewTarget = TimetableResourcePreviewTarget(entry.courseName, resource, actionKey, preview)
                }
                .onFailure { downloadError = it.message ?: "预览失败" }
            previewing = null
        }
    }

    fun previewAttachment(item: HomeworkItem, attachment: HomeworkAttachment) {
        val homeworkId = item.homeworkId ?: return
        val busyKey = homeworkAttachmentActionKey("preview", attachment)
        scope.launch {
            attachmentBusyKey = busyKey
            attachmentError = null
            runCatching {
                homeworkAttachmentRepository.preview(homeworkId, attachment.attachmentId, attachment.filename)
            }.onSuccess { preview ->
                attachmentPreviewTarget = HomeworkAttachmentPreviewTarget(item, attachment, preview)
            }.onFailure { error ->
                attachmentError = error.message ?: "预览附件失败"
            }
            attachmentBusyKey = null
        }
    }

    fun downloadAttachment(item: HomeworkItem, attachment: HomeworkAttachment) {
        val homeworkId = item.homeworkId ?: return
        val busyKey = homeworkAttachmentActionKey("download", attachment)
        scope.launch {
            attachmentBusyKey = busyKey
            attachmentError = null
            runCatching {
                homeworkAttachmentRepository.download(homeworkId, attachment.attachmentId, attachment.filename)
            }.onSuccess { file ->
                if (!openFile(context, file)) {
                    attachmentError = "已下载，但未找到可打开该文件的应用"
                }
            }.onFailure { error ->
                attachmentError = error.message ?: "下载附件失败"
            }
            attachmentBusyKey = null
        }
    }

    attachmentPreviewTarget?.let { target ->
        HomeworkAttachmentPreviewScreen(
            target = target,
            busyKey = attachmentBusyKey,
            error = attachmentError,
            onClose = {
                attachmentPreviewTarget = null
                attachmentError = null
            },
            onDownload = { downloadAttachment(target.homework, target.attachment) },
        )
        return
    }

    previewTarget?.let { target ->
        DocumentPreviewScreen(
            title = target.resource.name,
            subtitle = listOfNotNull(target.courseName, target.resource.teacherName).joinToString(" · "),
            preview = target.preview,
            downloadBusy = downloading == target.actionKey,
            error = downloadError,
            onClose = {
                previewTarget = null
                downloadError = null
            },
            onDownload = { downloadResource(target.resource, target.actionKey) },
        )
        return
    }

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
            if (entry.isUserCreated) {
                item(key = "$entryKey-user-actions") {
                    InfoCard("本地课程") {
                        KeyValue("备注", entry.remark)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { onEditUserCourse(entry) }, modifier = Modifier.weight(1f)) {
                                Text("编辑")
                            }
                            OutlinedButton(onClick = { onDeleteUserCourse(entry) }, modifier = Modifier.weight(1f)) {
                                Text("删除")
                            }
                        }
                    }
                }
            } else {
            item(key = "$entryKey-homework-divider") { HorizontalDivider() }
            item(key = "$entryKey-homework-title") { Text("作业", style = MaterialTheme.typography.titleMedium) }
            if (!attachmentError.isNullOrBlank()) {
                item(key = "$entryKey-attachment-error") {
                    Text(attachmentError.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }
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
                            InfoCard(
                                title = homework.title,
                                modifier = Modifier.clickable { onOpenHomeworkDetail(homework) },
                                subtitle = homework.course,
                            ) {
                                KeyValue("开始", homework.openedAt)
                                KeyValue("截止", homework.dueAt)
                                KeyValue("状态", homework.status)
                                KeyValue("内容", homework.contentExcerpt)
                                HomeworkAttachmentsSection(
                                    attachments = homework.attachments,
                                    busyKey = attachmentBusyKey,
                                    onPreview = { previewAttachment(homework, it) },
                                    onDownload = { downloadAttachment(homework, it) },
                                )
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
                        items(data.folders, key = { "$entryKey-folder-${it.categoryKey}-${it.folderId}" }) { folder ->
                            InfoCard(folder.name, subtitle = "${folder.categoryLabel} · 目录 ${folder.folderId}") {
                                Text("请到课程资源页继续浏览该目录。", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        items(data.resources, key = { "$entryKey-resource-${it.categoryKey}-${it.rpId}" }) { resource ->
                            val downloadKey = "$entryKey|${resource.categoryKey}|${resource.rpId}"
                            val subtitle = listOf(resource.categoryLabel, resource.uploadedAt)
                                .filter { !it.isNullOrBlank() }
                                .joinToString(" · ")
                            InfoCard(
                                title = resource.name,
                                subtitle = subtitle.ifBlank { null },
                                trailing = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            enabled = downloading == null && previewing == null,
                                            onClick = { previewResource(entry, resource, downloadKey) },
                                        ) {
                                            Text(if (previewing == downloadKey) "预览中" else "预览")
                                        }
                                        Button(
                                            enabled = resource.canDownload && downloading == null && previewing == null,
                                            onClick = { downloadResource(resource, downloadKey) },
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
}

private data class TimetableResourcePreviewTarget(
    val courseName: String,
    val resource: CourseResourceItem,
    val actionKey: String,
    val preview: DocumentPreview,
)

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

private fun userCourseEditorSeedForSlot(
    day: TimetableDayColumn,
    slot: TimetablePeriodSlot,
    currentWeek: Int?,
    maxWeek: Int,
): UserCourseEditorSeed {
    val week = currentWeek ?: 1
    return UserCourseEditorSeed(
        weekdayIndex = day.index,
        period = slot.period,
        periodNumber = normalizedTimetablePeriodNumber(slot.period) ?: 1,
        timeRange = slot.timeRange,
        startWeek = week,
        endWeek = week.coerceAtMost(maxWeek.coerceAtLeast(week)),
        durationType = UserCourseDurationType.Temporary,
    )
}

private fun userCourseEditorSeedForEntry(
    entry: CourseEntry,
    currentWeek: Int?,
    maxWeek: Int,
): UserCourseEditorSeed {
    val (startWeek, endWeek) = timetableEntryWeekBounds(entry.weeks, currentWeek, maxWeek)
    return UserCourseEditorSeed(
        id = entry.localId,
        courseName = entry.courseName,
        weekdayIndex = parseWeekdayIndex(entry.weekday) ?: 0,
        period = entry.period,
        periodNumber = normalizedTimetablePeriodNumber(entry.period) ?: 1,
        timeRange = entry.timeRange,
        startWeek = startWeek,
        endWeek = endWeek,
        weeksText = entry.weeks,
        durationType = if (startWeek == endWeek && entry.section != UserCourseDurationType.LongTerm.name) {
            UserCourseDurationType.Temporary
        } else {
            UserCourseDurationType.LongTerm
        },
        teacher = entry.teacher.orEmpty(),
        locationText = entry.locationLabel().orEmpty(),
        remark = entry.remark.orEmpty(),
        colorIndex = entry.colorIndex ?: 0,
    )
}

private fun timetableMaxWeek(entries: List<CourseEntry>): Int =
    entries
        .flatMap { entry ->
            Regex("""\d{1,2}""").findAll(entry.weeks.orEmpty()).mapNotNull { it.value.toIntOrNull() }.toList()
        }
        .filter { it > 0 }
        .maxOrNull()
        ?: 21

private fun timetableEntryWeekBounds(weeks: String?, currentWeek: Int?, maxWeek: Int): Pair<Int, Int> {
    val parsed = Regex("""\d{1,2}""").findAll(weeks.orEmpty()).mapNotNull { it.value.toIntOrNull() }.toList()
    if (parsed.isEmpty()) {
        val fallback = currentWeek ?: 1
        return fallback to fallback.coerceAtMost(maxWeek.coerceAtLeast(fallback))
    }
    return (parsed.minOrNull() ?: 1).coerceAtLeast(1) to (parsed.maxOrNull() ?: parsed.first()).coerceAtLeast(1)
}

private fun timetablePeriodSlots(data: TimetableData, entries: List<CourseEntry>): List<TimetablePeriodSlot> {
    val periods = (data.periods + entries.map { it.period })
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .ifEmpty { (1..5).map { "Period $it" } }
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
    if (entry.isUserCreated) {
        val container = UserCourseColors[entry.colorIndex?.coerceIn(0, UserCourseColors.lastIndex) ?: 0]
        return TimetableCourseColors(container = container, content = Color.White)
    }
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

internal fun parseWeekNumber(value: String?): Int? =
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
