package cn.edu.bjtu.mis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.db.ModuleSnapshotEntity
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.data.repository.SyncRepository
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.SyncRun
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.SectionTitle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
    NavigationTarget(ModuleKeys.Exams, "考务"),
    NavigationTarget(ModuleKeys.Scores, "主修成绩"),
    NavigationTarget(ModuleKeys.Calendar, "学年日历"),
    NavigationTarget(ModuleKeys.Homework, "作业"),
    NavigationTarget(ModuleKeys.CourseResources, "课程资源"),
    NavigationTarget(ModuleKeys.EmptyRooms, "空教室"),
)

@Composable
fun OverviewScreen(
    moduleRepository: ModuleRepository,
    syncRepository: SyncRepository,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<LoadState<Pair<List<ModuleSnapshotEntity>, SyncRun>>>(LoadState.Loading) }
    var syncing by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            state = LoadState.Loading
            runCatching { moduleRepository.snapshots() to syncRepository.latestStatus() }
                .onSuccess { state = LoadState.Data(it) }
                .onFailure { state = LoadState.Error(it.message ?: "加载失败") }
        }
    }

    LaunchedEffect(Unit) { reload() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionTitle(
                title = "模块总览",
                subtitle = "点击模块进入详情；没有网络时会优先显示本地缓存。",
                trailing = {
                    Button(
                        enabled = !syncing,
                        onClick = {
                            scope.launch {
                                syncing = true
                                runCatching { syncRepository.runSync() }
                                syncing = false
                                reload()
                            }
                        },
                    ) {
                        Text(if (syncing) "同步中" else "立即同步")
                    }
                },
            )
        }
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
            is LoadState.Data -> {
                val snapshots = current.value.first.associateBy { it.moduleKey }
                items(navigationTargets.drop(1), key = { it.key }) { target ->
                    val snapshot = snapshots[target.key]
                    InfoCard(
                        title = target.label,
                        subtitle = snapshot?.syncedAt ?: "尚未同步",
                        modifier = Modifier.clickable { onNavigate(target.key) },
                        trailing = {
                            AssistChip(
                                onClick = { onNavigate(target.key) },
                                label = { Text("${countItems(snapshot)} 条") },
                            )
                        },
                    ) {
                        Text(
                            text = snapshot?.coverage ?: "pending",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    val sync = current.value.second
                    InfoCard("同步摘要", subtitle = sync.finishedAt ?: sync.startedAt) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {}, label = { Text(sync.status) })
                            sync.moduleSummary.forEach { (key, value) ->
                                AssistChip(onClick = {}, label = { Text("$key ${value.items ?: "-"}") })
                            }
                        }
                        if (!sync.errorText.isNullOrBlank()) {
                            Text(sync.errorText, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

private fun countItems(snapshot: ModuleSnapshotEntity?): Int {
    if (snapshot == null) return 0
    val root = runCatching { AppJson.parseToJsonElement(snapshot.payloadJson).jsonObject }.getOrNull() ?: return 0
    val data = root["data"] as? JsonObject ?: return 0
    return listOf("items", "entries", "rooms", "resources", "courses", "buckets", "fields")
        .firstNotNullOfOrNull { key -> (data[key] as? JsonArray)?.size }
        ?: 0
}
