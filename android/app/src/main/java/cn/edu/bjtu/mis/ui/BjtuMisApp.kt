package cn.edu.bjtu.mis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.provider.SessionValidationPolicy
import cn.edu.bjtu.mis.data.sync.SessionKeepAliveForegroundService
import cn.edu.bjtu.mis.di.AppContainer
import cn.edu.bjtu.mis.model.AutoLoginStatus
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.SessionState
import cn.edu.bjtu.mis.ui.screens.AcademicProgressScreen
import cn.edu.bjtu.mis.ui.screens.AgentScreen
import cn.edu.bjtu.mis.ui.screens.CalendarScreen
import cn.edu.bjtu.mis.ui.screens.CourseReplayScreen
import cn.edu.bjtu.mis.ui.screens.CourseResourcesScreen
import cn.edu.bjtu.mis.ui.screens.CourseSelectionScreen
import cn.edu.bjtu.mis.ui.screens.EmptyRoomsScreen
import cn.edu.bjtu.mis.ui.screens.ExamsScreen
import cn.edu.bjtu.mis.ui.screens.HomeworkScreen
import cn.edu.bjtu.mis.ui.screens.LoginScreen
import cn.edu.bjtu.mis.ui.screens.MailScreen
import cn.edu.bjtu.mis.ui.screens.OverviewScreen
import cn.edu.bjtu.mis.ui.screens.ProfileScreen
import cn.edu.bjtu.mis.ui.screens.ScoresScreen
import cn.edu.bjtu.mis.ui.screens.ServicesScreen
import cn.edu.bjtu.mis.ui.screens.TimetableScreen
import cn.edu.bjtu.mis.ui.screens.navigationTargets
import kotlinx.coroutines.launch

private const val RouteHome = "overview"
private const val RouteServices = "services"
private val MainRoutes = setOf(RouteHome, RouteServices, ModuleKeys.Profile)
private val AppBlue = Color(0xFF0B74F6)

private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ShellIcon,
)

private enum class ShellIcon {
    Home,
    Grid,
    Person,
    Back,
}

private val BottomTabs = listOf(
    BottomTab(RouteHome, "首页", ShellIcon.Home),
    BottomTab(RouteServices, "服务", ShellIcon.Grid),
    BottomTab(ModuleKeys.Profile, "我的", ShellIcon.Person),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BjtuMisApp(
    container: AppContainer,
    requestedRoute: String? = null,
    onRouteHandled: () -> Unit = {},
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(RouteHome) }
    var mainTab by remember { mutableStateOf(RouteHome) }
    var ready by remember { mutableStateOf<Boolean?>(null) }
    var sessionDetail by remember { mutableStateOf("") }
    var showExitDialog by remember { mutableStateOf(false) }
    var showAutoLoginFailedDialog by remember { mutableStateOf(false) }
    var autoLoginFailedMessage by remember { mutableStateOf("") }
    var autoLoginRetrying by remember { mutableStateOf(false) }

    fun startSessionKeepAlive() {
        runCatching { SessionKeepAliveForegroundService.start(context) }
    }

    fun startBackgroundQuickSync() {
        scope.launch {
            runCatching { container.syncRepository.runQuickSync() }
        }
    }

    fun navigateMain(route: String) {
        current = route
        mainTab = route
        showExitDialog = false
    }

    fun navigateModule(route: String) {
        if (route in MainRoutes) {
            navigateMain(route)
        } else {
            current = route
            showExitDialog = false
        }
    }

    fun refreshSession() {
        scope.launch {
            val cached = runCatching { container.sessionRepository.cachedStatus() }.getOrNull()
            val hadCachedSession = cached?.state == SessionState.Ready
            if (hadCachedSession) {
                sessionDetail = cached?.detail.orEmpty()
                ready = true
            }

            val status = container.sessionRepository.status(SessionValidationPolicy.UseRecentOrValidate)
            var isReady = status.state == SessionState.Ready
            var autoLoggedIn = false
            sessionDetail = status.detail.orEmpty().ifBlank { sessionDetail }
            if (!isReady) {
                val autoLogin = container.sessionRepository.loginAuto()
                if (autoLogin.status == AutoLoginStatus.Ready) {
                    isReady = true
                    autoLoggedIn = true
                    sessionDetail = autoLogin.session?.detail ?: autoLogin.message.orEmpty()
                    showAutoLoginFailedDialog = false
                } else if (autoLogin.status == AutoLoginStatus.AutoFailed && autoLogin.attempts > 0) {
                    autoLoginFailedMessage = autoLogin.message ?: "自动重新登录失败。"
                    showAutoLoginFailedDialog = true
                }
            }
            if (isReady) {
                ready = true
                startSessionKeepAlive()
                if (autoLoggedIn) startBackgroundQuickSync()
            } else if (!hadCachedSession) {
                ready = false
            }
        }
    }

    fun continueAutoLoginRetry() {
        scope.launch {
            autoLoginRetrying = true
            val result = container.sessionRepository.loginAuto()
            if (result.status == AutoLoginStatus.Ready) {
                ready = true
                sessionDetail = result.session?.detail ?: result.message.orEmpty()
                showAutoLoginFailedDialog = false
                startSessionKeepAlive()
                startBackgroundQuickSync()
            } else {
                autoLoginFailedMessage = result.message ?: "自动重新登录失败。"
                showAutoLoginFailedDialog = true
            }
            autoLoginRetrying = false
        }
    }

    LaunchedEffect(Unit) { refreshSession() }
    LaunchedEffect(requestedRoute, ready) {
        val route = requestedRoute
        if (ready == true && !route.isNullOrBlank()) {
            current = route
            mainTab = when (route) {
                RouteHome, RouteServices, ModuleKeys.Profile -> route
                else -> RouteServices
            }
            showExitDialog = false
            onRouteHandled()
        }
    }

    if (showAutoLoginFailedDialog) {
        AlertDialog(
            onDismissRequest = { showAutoLoginFailedDialog = false },
            title = { Text("自动重新登录失败") },
            text = { Text(autoLoginFailedMessage.ifBlank { "已连续重试 3 次，当前会话仍不可用。" }) },
            confirmButton = {
                TextButton(
                    enabled = !autoLoginRetrying,
                    onClick = { continueAutoLoginRetry() },
                ) {
                    Text(if (autoLoginRetrying) "重试中" else "继续重试")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showAutoLoginFailedDialog = false }) {
                        Text("稍后处理")
                    }
                    TextButton(
                        onClick = {
                            showAutoLoginFailedDialog = false
                            ready = false
                        },
                    ) {
                        Text("重新登录")
                    }
                }
            },
        )
    }

    when (ready) {
        null -> Splash()
        false -> LoginScreen(container.sessionRepository) {
            ready = true
            current = requestedRoute ?: RouteHome
            mainTab = if (current in MainRoutes) current else RouteServices
            if (!requestedRoute.isNullOrBlank()) onRouteHandled()
            startSessionKeepAlive()
            startBackgroundQuickSync()
            refreshSession()
        }
        true -> {
            BackHandler {
                when {
                    current !in MainRoutes -> current = mainTab
                    current != RouteHome -> navigateMain(RouteHome)
                    else -> showExitDialog = true
                }
            }

            if (showExitDialog) {
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = { Text("退出应用") },
                    text = { Text("确定要退出 BJTU MIS 吗？") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showExitDialog = false
                                onExit()
                            },
                        ) {
                            Text("是")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitDialog = false }) {
                            Text("否")
                        }
                    },
                )
            }

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    when {
                        current == RouteServices -> MainTitleBar("服务")
                        current == ModuleKeys.Profile -> MainTitleBar("我的")
                        current !in MainRoutes -> DetailTitleBar(
                            title = navigationTargets.firstOrNull { it.key == current }?.label ?: "服务详情",
                            onBack = { current = mainTab },
                        )
                    }
                },
                bottomBar = {
                    if (current in MainRoutes) {
                        AppBottomBar(current = current, onSelect = ::navigateMain)
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    when (current) {
                        RouteHome -> OverviewScreen(
                            overviewRepository = container.overviewRepository,
                            syncRepository = container.syncRepository,
                            sessionDetail = sessionDetail,
                            onNavigate = ::navigateModule,
                            onOpenServices = { navigateMain(RouteServices) },
                        )
                        RouteServices -> ServicesScreen(
                            moduleRepository = container.moduleRepository,
                            onNavigate = ::navigateModule,
                        )
                        ModuleKeys.Profile -> MainScreenPadding {
                            ProfileScreen(
                                repository = container.moduleRepository,
                                onLogout = {
                                    container.sessionRepository.logout()
                                    SessionKeepAliveForegroundService.stop(context)
                                    ready = false
                                },
                                onNavigate = ::navigateModule,
                            )
                        }
                        else -> MainScreenPadding {
                            ModuleRoute(
                                route = current,
                                container = container,
                                onNavigate = ::navigateModule,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScreenPadding(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        content()
    }
}

@Composable
private fun ModuleRoute(
    route: String,
    container: AppContainer,
    onNavigate: (String) -> Unit,
) {
    when (route) {
        ModuleKeys.AcademicProgress -> AcademicProgressScreen(container.moduleRepository)
        ModuleKeys.HistoryScores -> ScoresScreen(container.moduleRepository, history = true)
        ModuleKeys.Timetable -> TimetableScreen(container.moduleRepository, container.courseResourceRepository)
        ModuleKeys.CourseSelection -> CourseSelectionScreen(
            repository = container.courseSelectionRepository,
            runner = container.courseSelectionRunner,
        )
        ModuleKeys.Exams -> ExamsScreen(
            repository = container.moduleRepository,
            onNavigate = onNavigate,
        )
        ModuleKeys.Scores -> ScoresScreen(container.moduleRepository)
        ModuleKeys.Calendar -> CalendarScreen(container.moduleRepository)
        ModuleKeys.Homework -> HomeworkScreen(
            repository = container.moduleRepository,
            attachmentRepository = container.homeworkAttachmentRepository,
            agentRepository = container.agentRepository,
            onNavigate = onNavigate,
        )
        ModuleKeys.Mail -> MailScreen(container.mailRepository)
        ModuleKeys.Agent -> AgentScreen(
            repository = container.agentRepository,
            settingsStore = container.agentSettingsStore,
            secretStore = container.agentSecretStore,
            runtimeManager = container.agentRuntimeManager,
        )
        ModuleKeys.CourseResources -> CourseResourcesScreen(container.courseResourceRepository)
        ModuleKeys.CourseReplay -> CourseReplayScreen(container.courseReplayRepository, container.httpClient.client)
        ModuleKeys.EmptyRooms -> EmptyRoomsScreen(container.moduleRepository)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTitleBar(title: String) {
    CenterAlignedTopAppBar(
        title = {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = AppBlue,
            titleContentColor = Color.White,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTitleBar(title: String, onBack: () -> Unit) {
    CenterAlignedTopAppBar(
        title = {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                ShellLineIcon(ShellIcon.Back, color = Color.White)
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = AppBlue,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
        ),
    )
}

@Composable
private fun AppBottomBar(current: String, onSelect: (String) -> Unit) {
    NavigationBar(
        containerColor = Color.White,
        tonalElevation = 8.dp,
    ) {
        BottomTabs.forEach { tab ->
            val selected = current == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab.route) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = AppBlue,
                    selectedTextColor = AppBlue,
                    indicatorColor = Color(0xFFE4F0FF),
                    unselectedIconColor = Color(0xFF8A94A6),
                    unselectedTextColor = Color(0xFF5E6470),
                ),
                icon = {
                    ShellLineIcon(
                        icon = tab.icon,
                        color = if (selected) AppBlue else Color(0xFF8A94A6),
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}

@Composable
private fun ShellLineIcon(icon: ShellIcon, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        when (icon) {
            ShellIcon.Home -> {
                val roof = Path().apply {
                    moveTo(size.width * 0.16f, size.height * 0.48f)
                    lineTo(size.width * 0.5f, size.height * 0.18f)
                    lineTo(size.width * 0.84f, size.height * 0.48f)
                }
                drawPath(roof, color, style = stroke)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.25f, size.height * 0.44f),
                    size = Size(size.width * 0.5f, size.height * 0.42f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                    style = stroke,
                )
            }
            ShellIcon.Grid -> {
                val cell = size.width * 0.26f
                listOf(0.2f to 0.2f, 0.54f to 0.2f, 0.2f to 0.54f, 0.54f to 0.54f).forEach { (x, y) ->
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(size.width * x, size.height * y),
                        size = Size(cell, cell),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    )
                }
            }
            ShellIcon.Person -> {
                drawCircle(color, radius = size.minDimension * 0.16f, center = Offset(size.width * 0.5f, size.height * 0.34f), style = stroke)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.26f, size.height * 0.58f),
                    size = Size(size.width * 0.48f, size.height * 0.24f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx(), 9.dp.toPx()),
                    style = stroke,
                )
            }
            ShellIcon.Back -> {
                drawLine(color, Offset(size.width * 0.62f, size.height * 0.2f), Offset(size.width * 0.34f, size.height * 0.5f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.34f, size.height * 0.5f), Offset(size.width * 0.62f, size.height * 0.8f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun Splash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF045BC8), AppBlue, Color(0xFF34A1FF)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "MIS 教学服务系统",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            CircularProgressIndicator(color = Color.White)
            Text(
                text = "正在检查本地会话…",
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
