package cn.edu.bjtu.mis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue

@Composable
fun ExamsScreen(
    repository: ModuleRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    onNavigate: (String) -> Unit,
) {
    DataScreen(
        title = "考务",
        initialLoadStrategy = initialLoadStrategy,
        loader = { strategy -> repository.exams(strategy = strategy) },
    ) { envelope ->
        val data = envelope.data
        val currentTerm = data.currentTerm
        item {
            SecondaryModuleLinks(
                title = "考务相关",
                links = listOf(
                    ModuleKeys.AcademicProgress to "学业进度",
                    ModuleKeys.HistoryScores to "历史成绩",
                    ModuleKeys.Scores to "主修成绩",
                ),
                onNavigate = onNavigate,
            )
        }
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
