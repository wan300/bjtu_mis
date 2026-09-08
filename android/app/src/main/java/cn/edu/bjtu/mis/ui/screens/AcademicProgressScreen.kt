package cn.edu.bjtu.mis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue

@Composable
fun AcademicProgressScreen(
    repository: ModuleRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    DataScreen(
        title = "学业进度",
        initialLoadStrategy = initialLoadStrategy,
        loader = { strategy -> repository.academicProgress(strategy) },
    ) { envelope ->
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
