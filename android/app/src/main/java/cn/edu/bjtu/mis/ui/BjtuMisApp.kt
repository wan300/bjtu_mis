package cn.edu.bjtu.mis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.di.AppContainer
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.SessionState
import cn.edu.bjtu.mis.ui.screens.AcademicProgressScreen
import cn.edu.bjtu.mis.ui.screens.CalendarScreen
import cn.edu.bjtu.mis.ui.screens.CourseResourcesScreen
import cn.edu.bjtu.mis.ui.screens.EmptyRoomsScreen
import cn.edu.bjtu.mis.ui.screens.ExamsScreen
import cn.edu.bjtu.mis.ui.screens.HomeworkScreen
import cn.edu.bjtu.mis.ui.screens.LoginScreen
import cn.edu.bjtu.mis.ui.screens.OverviewScreen
import cn.edu.bjtu.mis.ui.screens.ProfileScreen
import cn.edu.bjtu.mis.ui.screens.ScoresScreen
import cn.edu.bjtu.mis.ui.screens.TimetableScreen
import cn.edu.bjtu.mis.ui.screens.navigationTargets
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BjtuMisApp(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var current by remember { mutableStateOf("overview") }
    var ready by remember { mutableStateOf<Boolean?>(null) }
    var sessionDetail by remember { mutableStateOf("") }

    fun refreshSession() {
        scope.launch {
            val status = container.sessionRepository.status()
            ready = status.state == SessionState.Ready
            sessionDetail = status.detail.orEmpty()
        }
    }

    LaunchedEffect(Unit) { refreshSession() }

    when (ready) {
        null -> Splash()
        false -> LoginScreen(container.sessionRepository) {
            ready = true
            current = "overview"
        }
        true -> ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("BJTU MIS", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Text("原生 Android 端", style = MaterialTheme.typography.bodySmall)
                    }
                    navigationTargets.forEach { target ->
                        NavigationDrawerItem(
                            label = { Text(target.label) },
                            selected = current == target.key,
                            onClick = {
                                current = target.key
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(navigationTargets.firstOrNull { it.key == current }?.label ?: "BJTU MIS")
                                if (sessionDetail.isNotBlank()) {
                                    Text(sessionDetail, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        actions = {
                            Button(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                onClick = { scope.launch { drawerState.open() } },
                            ) { Text("菜单") }
                            Button(
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                onClick = {
                                    container.sessionRepository.logout()
                                    ready = false
                                },
                            ) { Text("退出") }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    when (current) {
                        "overview" -> OverviewScreen(
                            moduleRepository = container.moduleRepository,
                            syncRepository = container.syncRepository,
                            onNavigate = { current = it },
                        )
                        ModuleKeys.Profile -> ProfileScreen(container.moduleRepository)
                        ModuleKeys.AcademicProgress -> AcademicProgressScreen(container.moduleRepository)
                        ModuleKeys.HistoryScores -> ScoresScreen(container.moduleRepository, history = true)
                        ModuleKeys.Timetable -> TimetableScreen(container.moduleRepository, container.courseResourceRepository)
                        ModuleKeys.Exams -> ExamsScreen(container.moduleRepository)
                        ModuleKeys.Scores -> ScoresScreen(container.moduleRepository)
                        ModuleKeys.Calendar -> CalendarScreen(container.moduleRepository)
                        ModuleKeys.Homework -> HomeworkScreen(container.moduleRepository)
                        ModuleKeys.CourseResources -> CourseResourcesScreen(container.courseResourceRepository)
                        ModuleKeys.EmptyRooms -> EmptyRoomsScreen(container.moduleRepository)
                    }
                }
            }
        }
    }
}

@Composable
private fun Splash() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text("正在检查本地会话…", style = MaterialTheme.typography.titleMedium)
        }
    }
}
