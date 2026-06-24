package cn.edu.bjtu.mis.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Grade
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.R
import cn.edu.bjtu.mis.data.repository.DashboardHighlight
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.OverviewDashboard
import cn.edu.bjtu.mis.data.repository.OverviewRepository
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.data.repository.SyncRepository
import cn.edu.bjtu.mis.data.thirdparty.THIRD_PARTY_SERVICES_ROUTE
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyService
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceRepository
import cn.edu.bjtu.mis.data.thirdparty.thirdPartyServiceRoute
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.CalendarItem
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class NavigationTarget(
    val key: String,
    val label: String,
)

val navigationTargets = listOf(
    NavigationTarget("overview", "总览"),
    NavigationTarget(ModuleKeys.Profile, "我的信息"),
    NavigationTarget(ModuleKeys.AcademicProgress, "学业进度"),
    NavigationTarget(ModuleKeys.HistoryScores, "历史成绩"),
    NavigationTarget(ModuleKeys.Timetable, "课表"),
    NavigationTarget(ModuleKeys.CourseSelection, "抢课"),
    NavigationTarget(ModuleKeys.Exams, "考务"),
    NavigationTarget(ModuleKeys.Scores, "主修成绩"),
    NavigationTarget(ModuleKeys.Calendar, "学年日历"),
    NavigationTarget(ModuleKeys.Homework, "作业"),
    NavigationTarget(ModuleKeys.TeachingAssessment, "评教"),
    NavigationTarget(ModuleKeys.Mail, "邮箱"),
    NavigationTarget(ModuleKeys.Zhixing, "知行"),
    NavigationTarget(ModuleKeys.EmploymentConsultation, "就业咨询"),
    NavigationTarget(ModuleKeys.OpenWebUiAgent, "作业助手"),
    NavigationTarget(ModuleKeys.CourseResources, "课程资源"),
    NavigationTarget(ModuleKeys.CourseReplay, "课程回放"),
    NavigationTarget(ModuleKeys.EmptyRooms, "空教室"),
)

private val DefaultPrimaryTint = Color(0xFF0B74F6)
private val AppCyan = Color(0xFF18B7D8)

private data class DashboardAction(
    val label: String,
    val route: String?,
    val icon: ImageVector,
    val tint: Color,
    @DrawableRes val imageRes: Int? = null,
)

private data class DashboardTodo(
    val title: String,
    val subtitle: String,
    val route: String,
    val tag: String,
)

data class ServiceEntry(
    val route: String,
    val label: String,
    val description: String,
    val icon: ImageVector,
    val tint: Color,
    @DrawableRes val imageRes: Int? = null,
)

data class ServiceGroup(
    val title: String,
    val subtitle: String,
    val entries: List<ServiceEntry>,
)

private val ServiceGroups = listOf(
    ServiceGroup(
        title = "教学学习",
        subtitle = "课程、作业与学习资源",
        entries = listOf(
            ServiceEntry(ModuleKeys.Timetable, "课表", "本周课程", Icons.Filled.Schedule, DefaultPrimaryTint, R.drawable.icon_timetable),
            ServiceEntry(ModuleKeys.Homework, "作业", "作业提交", Icons.Filled.Assignment, Color(0xFF2AA876), R.drawable.icon_homework),
            ServiceEntry(ModuleKeys.CourseResources, "课程资源", "资料下载", Icons.Filled.LibraryBooks, Color(0xFF7C58C2), R.drawable.icon_course_resources),
            ServiceEntry(ModuleKeys.CourseReplay, "课程回放", "课堂回看", Icons.Filled.PlayCircle, Color(0xFFFF8A00), R.drawable.icon_course_replay),
            ServiceEntry(ModuleKeys.CourseSelection, "抢课", "课程选择", Icons.Filled.AutoStories, Color(0xFF0E9D9D), R.drawable.icon_course_selection),
            ServiceEntry(ModuleKeys.TeachingAssessment, "评教", "课程评价", Icons.Filled.Grade, Color(0xFFD64B6B), R.drawable.icon_teaching_assessment),
        ),
    ),
    ServiceGroup(
        title = "成绩考务",
        subtitle = "成绩、考试与培养进度",
        entries = listOf(
            ServiceEntry(ModuleKeys.AcademicProgress, "学业进度", "培养完成情况", Icons.Filled.TrendingUp, DefaultPrimaryTint, R.drawable.icon_academic_progress),
            ServiceEntry(ModuleKeys.HistoryScores, "历史成绩", "历年成绩", Icons.Filled.School, Color(0xFF6E62D6), R.drawable.icon_history_scores),
            ServiceEntry(ModuleKeys.Scores, "主修成绩", "本学期成绩", Icons.Filled.Grade, Color(0xFFE46B2D), R.drawable.icon_scores),
            ServiceEntry(ModuleKeys.Exams, "考务", "考试安排", Icons.Filled.EventAvailable, Color(0xFFD64B6B), R.drawable.icon_exams),
        ),
    ),
    ServiceGroup(
        title = "信息工具",
        subtitle = "日程、邮箱与空间查询",
        entries = listOf(
            ServiceEntry(ModuleKeys.Calendar, "学年日历", "校历安排", Icons.Filled.CalendarMonth, DefaultPrimaryTint, R.drawable.icon_calendar),
            ServiceEntry(ModuleKeys.Mail, "邮箱", "校内邮件", Icons.Filled.Email, Color(0xFF2F8DD8), R.drawable.icon_mail),
            ServiceEntry(ModuleKeys.Zhixing, "知行", "校园论坛", Icons.Filled.Forum, Color(0xFF4A8B57), R.drawable.icon_zhixing),
            ServiceEntry(ModuleKeys.EmploymentConsultation, "就业咨询", "指导预约", Icons.Filled.Psychology, Color(0xFFE0673D), R.drawable.icon_employment_consultation),
            ServiceEntry(ModuleKeys.EmptyRooms, "空教室", "教室余量", Icons.Filled.MeetingRoom, Color(0xFF00A6A6), R.drawable.icon_empty_rooms),
        ),
    ),
    ServiceGroup(
        title = "智能助手",
        subtitle = "OpenWebUI 作业辅助能力",
        entries = listOf(
            ServiceEntry(ModuleKeys.OpenWebUiAgent, "作业助手", "OpenWebUI Agent", Icons.Filled.Psychology, Color(0xFF5A6FE8), R.drawable.icon_homework_agent),
        ),
    ),
)

@Composable
fun OverviewScreen(
    overviewRepository: OverviewRepository,
    syncRepository: SyncRepository,
    sessionDetail: String,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    onNavigate: (String) -> Unit,
    onOpenServices: () -> Unit,
    extendIntoStatusBar: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LoadState<OverviewDashboard>>(LoadState.Loading) }
    var syncing by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val dateLabel = remember(today) {
        today.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA))
    }

    fun loadCached(showLoading: Boolean) {
        scope.launch {
            if (showLoading) state = LoadState.Loading
            runCatching { overviewRepository.loadCached() }.onSuccess { dashboard ->
                if (dashboard.hasCache || !showLoading) {
                    state = LoadState.Data(dashboard)
                }
            }.onFailure {
                state = LoadState.Error(it.message ?: "总览加载失败")
            }
        }
    }

    fun refreshQuick() {
        scope.launch {
            val hadData = state is LoadState.Data
            syncing = true
            val result = runCatching { syncRepository.runQuickSync() }
            syncing = false
            runCatching { overviewRepository.loadCached() }
                .onSuccess { dashboard ->
                    if (dashboard.hasCache || result.isSuccess) {
                        state = LoadState.Data(dashboard)
                    } else if (!hadData) {
                        state = LoadState.Error(result.exceptionOrNull()?.message ?: "鎬昏鍔犺浇澶辫触")
                    }
                }
                .onFailure {
                    if (!hadData) state = LoadState.Error(it.message ?: "鎬昏鍔犺浇澶辫触")
                }
        }
    }

    LaunchedEffect(initialLoadStrategy) {
        val cached = runCatching { overviewRepository.loadCached() }.getOrNull()
        if (cached?.hasCache == true) {
            state = LoadState.Data(cached)
        }
        if (initialLoadStrategy == ModuleLoadStrategy.CacheOnly) {
            if (cached?.hasCache != true) {
                state = LoadState.Error("暂无本地缓存，请手动同步。")
            }
        } else {
            refreshQuick()
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 18.dp),
    ) {
        item {
            OverviewHero(
                sessionDetail = sessionDetail,
                dateLabel = dateLabel,
                syncing = syncing,
                extendIntoStatusBar = extendIntoStatusBar,
                onSync = {
                    scope.launch {
                        syncing = true
                        runCatching { syncRepository.runSync() }
                        syncing = false
                        loadCached(showLoading = state !is LoadState.Data)
                    }
                },
            )
        }
        item {
            QuickActionsGrid(
                actions = dashboardActions(),
                onNavigate = onNavigate,
                onOpenServices = onOpenServices,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }
        (state as? LoadState.Data)?.value?.let { dashboard ->
            item {
                PriorityReminderCard(
                    highlights = dashboard.highlights,
                    hiddenCount = dashboard.hiddenHighlightCount,
                    onNavigate = onNavigate,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
        }
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> item {
                Box(Modifier.padding(horizontal = 14.dp)) {
                    LoadingOrError(current)
                }
            }
            is LoadState.Data -> {
                val dashboard = current.value
                item {
                    DashboardStatusCard(
                        dashboard = dashboard,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
                item {
                    ScheduleCard(
                        calendar = dashboard.calendar,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
                item {
                    TeachingBanner(Modifier.padding(horizontal = 14.dp))
                }
            }
        }
    }
}

@Composable
fun ServicesScreen(
    moduleRepository: ModuleRepository,
    thirdPartyServiceRepository: ThirdPartyServiceRepository,
    onNavigate: (String) -> Unit,
) {
    var thirdPartyServices by remember { mutableStateOf<List<ThirdPartyService>>(emptyList()) }
    LaunchedEffect(Unit) {
        thirdPartyServices = runCatching { thirdPartyServiceRepository.listServices() }.getOrDefault(emptyList())
    }
    moduleRepository.hashCode()
    val groups = remember(thirdPartyServices) {
        ServiceGroups + thirdPartyServiceGroup(thirdPartyServices)
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
    ) {
        items(groups, key = { it.title }) { group ->
            InfoCard(title = group.title, subtitle = group.subtitle) {
                ServiceGrid(entries = group.entries, onNavigate = onNavigate)
            }
        }
    }
}

private fun thirdPartyServiceGroup(services: List<ThirdPartyService>): ServiceGroup =
    ServiceGroup(
        title = "第三方服务",
        subtitle = "从公开 GitHub 仓库导入并在应用内打开",
        entries = listOf(
            ServiceEntry(THIRD_PARTY_SERVICES_ROUTE, "导入服务", "GitHub 仓库", Icons.Filled.Add, Color(0xFF0E9D9D)),
        ) + services.map { service ->
            ServiceEntry(
                route = thirdPartyServiceRoute(service.serviceId),
                label = service.manifest.name,
                description = if (service.needsReview || !service.enabled) "待授权" else service.manifest.version,
                icon = Icons.Filled.Psychology,
                tint = Color(0xFF5A6FE8),
            )
        } + ServiceEntry(THIRD_PARTY_SERVICES_ROUTE, "服务管理", "权限与更新", Icons.Filled.Security, Color(0xFF7C58C2)),
    )

private fun dashboardActions(): List<DashboardAction> = listOf(
    DashboardAction("作业", ModuleKeys.Homework, Icons.Filled.Assignment, Color(0xFF2AA876), R.drawable.icon_homework),
    DashboardAction("课表", ModuleKeys.Timetable, Icons.Filled.Schedule, DefaultPrimaryTint, R.drawable.icon_timetable),
    DashboardAction("邮箱", ModuleKeys.Mail, Icons.Filled.Email, Color(0xFF2F8DD8), R.drawable.icon_mail),
    DashboardAction("成绩", ModuleKeys.Scores, Icons.Filled.Grade, Color(0xFFE46B2D), R.drawable.icon_scores),
    DashboardAction("校历", ModuleKeys.Calendar, Icons.Filled.CalendarMonth, Color(0xFF7C58C2), R.drawable.icon_calendar),
    DashboardAction("更多服务", null, Icons.Filled.GridView, Color(0xFF0E9D9D), R.drawable.icon_services),
)

@Composable
private fun OverviewHero(
    sessionDetail: String,
    dateLabel: String,
    syncing: Boolean,
    extendIntoStatusBar: Boolean,
    onSync: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(colorScheme.primaryContainer, colorScheme.surfaceVariant),
                ),
            )
            .then(if (extendIntoStatusBar) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "BJTU MIS",
                        color = colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "你好，今天是 $dateLabel",
                        color = colorScheme.onPrimaryContainer.copy(alpha = 0.88f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Button(
                    enabled = !syncing,
                    onClick = onSync,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.primary,
                        contentColor = colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(if (syncing) "同步中" else "同步")
                }
            }
            Text(
                text = sessionDetail.ifBlank { "校园信息本地采集与离线查看" },
                color = colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PriorityReminderCard(
    highlights: List<DashboardHighlight>,
    hiddenCount: Int,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    InfoCard(title = "重点提醒", subtitle = "临期作业与最近同步更新", modifier = modifier) {
        if (highlights.isEmpty()) {
            Text("暂无临期作业或新更新", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                highlights.forEach { highlight ->
                    PriorityReminderRow(highlight = highlight, onNavigate = onNavigate)
                }
                if (hiddenCount > 0) {
                    Text(
                        "还有 $hiddenCount 条更新，可进入对应模块查看",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PriorityReminderRow(
    highlight: DashboardHighlight,
    onNavigate: (String) -> Unit,
) {
    val tint = when {
        highlight.urgent -> MaterialTheme.colorScheme.error
        highlight.route == ModuleKeys.Homework -> Color(0xFF2AA876)
        highlight.route == ModuleKeys.Exams -> Color(0xFFD64B6B)
        highlight.route == ModuleKeys.Scores -> Color(0xFFE46B2D)
        highlight.route == ModuleKeys.CourseResources -> Color(0xFF7C58C2)
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), MaterialTheme.shapes.medium)
            .clickable { onNavigate(highlight.route) }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(72.dp)
                .background(tint.copy(alpha = 0.12f), MaterialTheme.shapes.small)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                highlight.tag,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                highlight.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = if (highlight.updated && highlight.route == ModuleKeys.Homework && highlight.tag.contains("作业")) {
                listOf("有更新", highlight.subtitle).filter { it.isNotBlank() }.joinToString(" · ")
            } else {
                highlight.subtitle
            }
            Text(
                subtitle.ifBlank { "点击查看详情" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickActionsGrid(
    actions: List<DashboardAction>,
    onNavigate: (String) -> Unit,
    onOpenServices: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InfoCard(title = "快捷入口", modifier = modifier, subtitle = "常用教学服务直达") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            actions.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { action ->
                        QuickActionItem(
                            action = action,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                action.route?.let(onNavigate) ?: onOpenServices()
                            },
                        )
                    }
                    repeat(3 - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionItem(
    action: DashboardAction,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = themedTint(action.tint)
    Column(
        modifier = modifier
            .height(86.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(tint.copy(alpha = 0.12f), MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            if (action.imageRes != null) {
                Image(
                    painter = painterResource(action.imageRes),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            action.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DashboardStatusCard(
    dashboard: OverviewDashboard,
    modifier: Modifier = Modifier,
) {
    val status = statusLabel(dashboard.latest.status)
    InfoCard(
        title = "同步状态",
        subtitle = dashboard.latest.finishedAt ?: dashboard.latest.startedAt ?: "尚未同步",
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            StatusPill(status, MaterialTheme.colorScheme.primary)
            StatusPill("${dashboard.snapshots.size} 个模块快照", AppCyan)
        }
        dashboard.latest.errorText?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
private fun TodoCard(
    todos: List<DashboardTodo>,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    InfoCard(title = "待办事项", subtitle = "优先展示未完成作业与近期考试", modifier = modifier) {
        if (todos.isEmpty()) {
            Text("暂无待处理事项", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                todos.forEach { todo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), MaterialTheme.shapes.medium)
                            .clickable { onNavigate(todo.route) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(52.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), MaterialTheme.shapes.small)
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(todo.tag, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                todo.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                todo.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleCard(calendar: CalendarData?, modifier: Modifier = Modifier) {
    val items = calendar?.items.orEmpty().take(3)
    InfoCard(
        title = "近期日程",
        subtitle = listOfNotNull(calendar?.currentTerm, calendar?.currentWeek, calendar?.month).firstOrNull(),
        modifier = modifier,
    ) {
        if (items.isEmpty()) {
            Text("暂无校历摘要", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { item ->
                    ScheduleRow(item)
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(item: CalendarItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier
                .width(62.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), MaterialTheme.shapes.small)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(item.date.takeLast(5), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            item.week?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = item.note?.takeIf { it.isNotBlank() } ?: "校历安排",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TeachingBanner(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    InfoCard(title = "教学服务", subtitle = "课表、资源、作业、考务都在同一处聚合", modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "本地保留最近同步结果，离线也能查看关键教学信息。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {}, enabled = false) {
                    Text("教学服务已就绪")
                }
            }
            Spacer(Modifier.width(12.dp))
            Canvas(modifier = Modifier.size(width = 104.dp, height = 88.dp)) {
                val strokeWidth = 4.dp.toPx()
                drawRoundRect(
                    color = colorScheme.primaryContainer,
                    topLeft = Offset(size.width * 0.08f, size.height * 0.1f),
                    size = Size(size.width * 0.72f, size.height * 0.62f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                )
                drawCircle(colorScheme.surface, radius = size.minDimension * 0.22f, center = Offset(size.width * 0.68f, size.height * 0.38f))
                drawCircle(AppCyan, radius = size.minDimension * 0.14f, center = Offset(size.width * 0.68f, size.height * 0.38f))
                drawLine(
                    color = colorScheme.primary,
                    start = Offset(size.width * 0.18f, size.height * 0.28f),
                    end = Offset(size.width * 0.48f, size.height * 0.28f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = colorScheme.primary.copy(alpha = 0.72f),
                    start = Offset(size.width * 0.18f, size.height * 0.45f),
                    end = Offset(size.width * 0.42f, size.height * 0.45f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
                drawRoundRect(
                    color = colorScheme.secondaryContainer,
                    topLeft = Offset(size.width * 0.18f, size.height * 0.72f),
                    size = Size(size.width * 0.64f, size.height * 0.12f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun ServiceGrid(entries: List<ServiceEntry>, onNavigate: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { entry ->
                    ServiceTile(
                        entry = entry,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(entry.route) },
                    )
                }
                repeat(4 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ServiceTile(entry: ServiceEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint = themedTint(entry.tint)
    Column(
        modifier = modifier
            .height(92.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(tint.copy(alpha = 0.12f), MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.imageRes != null) {
                Image(
                    painter = painterResource(entry.imageRes),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Icon(entry.icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = entry.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = entry.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun themedTint(tint: Color): Color =
    if (tint == DefaultPrimaryTint) MaterialTheme.colorScheme.primary else tint

private fun dashboardTodos(dashboard: OverviewDashboard): List<DashboardTodo> {
    val homeworkTodos = dashboard.homework
        .filter { it.isPendingHomework() }
        .sortedBy { it.dueAt ?: "9999" }
        .take(3)
        .map {
            DashboardTodo(
                title = it.title,
                subtitle = listOfNotNull(it.course, it.dueAt?.let { due -> "截止 $due" }).joinToString(" · "),
                route = ModuleKeys.Homework,
                tag = "作业",
            )
        }
    val examTodos = dashboard.exams
        .filter { !it.schedule.isNullOrBlank() }
        .take(2)
        .map {
            DashboardTodo(
                title = it.courseName,
                subtitle = listOfNotNull(it.schedule, it.examMode, it.status).joinToString(" · "),
                route = ModuleKeys.Exams,
                tag = "考试",
            )
        }
    return (homeworkTodos + examTodos).take(5)
}

private fun HomeworkItem.isPendingHomework(): Boolean {
    val text = listOf(status, submissionStatus, submittedAt).joinToString(" ")
    return submittedAt.isNullOrBlank() &&
        !text.contains("已提交") &&
        !text.contains("已完成") &&
        !text.contains("已批阅")
}

private fun statusLabel(status: String): String = when (status) {
    "success" -> "同步成功"
    "partial_failure" -> "部分成功"
    "failed" -> "同步失败"
    "running" -> "同步中"
    "session_expired" -> "会话过期"
    "idle" -> "等待同步"
    else -> status
}
