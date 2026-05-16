package cn.edu.bjtu.mis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.NavigationBarDefaults
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
import cn.edu.bjtu.mis.ui.screens.OpenWebUiAgentScreen
import cn.edu.bjtu.mis.ui.screens.OverviewScreen
import cn.edu.bjtu.mis.ui.screens.ProfileScreen
import cn.edu.bjtu.mis.ui.screens.ScoresScreen
import cn.edu.bjtu.mis.ui.screens.ServicesScreen
import cn.edu.bjtu.mis.ui.screens.TimetableScreen
import cn.edu.bjtu.mis.ui.screens.navigationTargets
import cn.edu.bjtu.mis.ui.theme.AppThemeOption
import cn.edu.bjtu.mis.ui.theme.BjtuMisSystemBars
import kotlinx.coroutines.launch

private const val RouteHome = "overview"
private const val RouteServices = "services"
private const val RouteOpenWebUiAgent = "openwebui_agent"
private val MainRoutes = setOf(RouteHome, RouteServices, RouteOpenWebUiAgent, ModuleKeys.Profile)

private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ShellIcon,
)

private enum class ShellIcon {
    Home,
    Grid,
    Agent,
    Person,
    Back,
}

private val BottomTabs = listOf(
    BottomTab(RouteHome, "首页", ShellIcon.Home),
    BottomTab(RouteServices, "服务", ShellIcon.Grid),
    BottomTab(RouteOpenWebUiAgent, "Agent", ShellIcon.Agent),
    BottomTab(ModuleKeys.Profile, "我的", ShellIcon.Person),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BjtuMisApp(
    container: AppContainer,
    themeOption: AppThemeOption = AppThemeOption.Default,
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
    var openWebUiBackHandler by remember { mutableStateOf<(() -> Boolean)?>(null) }

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

            val session = container.sessionRepository.recoverSession(SessionValidationPolicy.UseRecentOrValidate)
            val isReady = session.status == AutoLoginStatus.Ready
            val autoLoggedIn = isReady && session.attempts > 0
            sessionDetail = session.session?.detail ?: session.message.orEmpty().ifBlank { sessionDetail }
            if (isReady) {
                showAutoLoginFailedDialog = false
            } else if (session.status == AutoLoginStatus.AutoFailed && session.attempts > 0) {
                autoLoginFailedMessage = session.message ?: "自动重新登录失败。"
                showAutoLoginFailedDialog = true
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
            val result = container.sessionRepository.recoverSession(SessionValidationPolicy.Fresh)
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
                RouteHome, RouteServices, RouteOpenWebUiAgent, ModuleKeys.Profile -> route
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
                    current == RouteOpenWebUiAgent && openWebUiBackHandler?.invoke() == true -> Unit
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

            val extendHomeIntoStatusBar = themeOption == AppThemeOption.MascotGold && current == RouteHome
            val isOpenWebUiAgent = current == RouteOpenWebUiAgent
            BjtuMisSystemBars(
                statusBarColor = when {
                    extendHomeIntoStatusBar -> Color.Transparent
                    isOpenWebUiAgent -> Color(0xFF171717)
                    else -> MaterialTheme.colorScheme.primary
                },
                navigationBarColor = MaterialTheme.colorScheme.surface,
                useDarkStatusBarIcons = !isOpenWebUiAgent && themeOption != AppThemeOption.MascotGold,
                useDarkNavigationBarIcons = themeOption != AppThemeOption.MascotGold,
                decorFitsSystemWindows = !extendHomeIntoStatusBar,
            )

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0.dp),
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
                        AppBottomBar(
                            current = current,
                            useWindowInsets = extendHomeIntoStatusBar,
                            onSelect = ::navigateMain,
                        )
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
                            extendIntoStatusBar = extendHomeIntoStatusBar,
                        )
                        RouteServices -> ServicesScreen(
                            moduleRepository = container.moduleRepository,
                            onNavigate = ::navigateModule,
                        )
                        RouteOpenWebUiAgent -> OpenWebUiAgentScreen(
                            repository = container.moduleRepository,
                            onBackHandlerChanged = { openWebUiBackHandler = it },
                        )
                        ModuleKeys.Profile -> MainScreenPadding {
                            ProfileScreen(
                                repository = container.moduleRepository,
                                selectedTheme = themeOption,
                                onThemeSelected = { nextTheme ->
                                    scope.launch { container.themeStore.save(nextTheme) }
                                },
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
    val colorScheme = MaterialTheme.colorScheme
    CenterAlignedTopAppBar(
        title = {
            Text(title, color = colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
        },
        windowInsets = WindowInsets(0.dp),
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = colorScheme.primary,
            titleContentColor = colorScheme.onPrimary,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTitleBar(title: String, onBack: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    CenterAlignedTopAppBar(
        title = {
            Text(title, color = colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                ShellLineIcon(ShellIcon.Back, color = colorScheme.onPrimary)
            }
        },
        windowInsets = WindowInsets(0.dp),
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = colorScheme.primary,
            titleContentColor = colorScheme.onPrimary,
            navigationIconContentColor = colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun AppBottomBar(
    current: String,
    useWindowInsets: Boolean,
    onSelect: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    NavigationBar(
        containerColor = colorScheme.surface,
        tonalElevation = 8.dp,
        windowInsets = if (useWindowInsets) NavigationBarDefaults.windowInsets else WindowInsets(0.dp),
    ) {
        BottomTabs.forEach { tab ->
            val selected = current == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab.route) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colorScheme.primary,
                    selectedTextColor = colorScheme.primary,
                    indicatorColor = colorScheme.primaryContainer,
                    unselectedIconColor = colorScheme.onSurfaceVariant,
                    unselectedTextColor = colorScheme.onSurfaceVariant,
                ),
                icon = {
                    ShellLineIcon(
                        icon = tab.icon,
                        color = if (selected) colorScheme.primary else colorScheme.onSurfaceVariant,
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
            ShellIcon.Agent -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.2f, size.height * 0.28f),
                    size = Size(size.width * 0.6f, size.height * 0.46f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx(), 7.dp.toPx()),
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.5f, size.height * 0.28f),
                    end = Offset(size.width * 0.5f, size.height * 0.14f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
                drawCircle(color, radius = size.minDimension * 0.045f, center = Offset(size.width * 0.5f, size.height * 0.11f))
                drawCircle(color, radius = size.minDimension * 0.035f, center = Offset(size.width * 0.39f, size.height * 0.48f))
                drawCircle(color, radius = size.minDimension * 0.035f, center = Offset(size.width * 0.61f, size.height * 0.48f))
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.4f, size.height * 0.62f),
                    end = Offset(size.width * 0.6f, size.height * 0.62f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
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
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        colorScheme.background,
                        colorScheme.primaryContainer,
                        colorScheme.background,
                    ),
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
                color = colorScheme.onBackground,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            CircularProgressIndicator(color = colorScheme.primary)
            Text(
                text = "正在检查本地会话…",
                color = colorScheme.onBackground.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
