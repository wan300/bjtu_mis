package cn.edu.bjtu.mis.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.homework.HomeworkReminderConfig
import cn.edu.bjtu.mis.data.homework.HomeworkReminderPreferenceStore
import cn.edu.bjtu.mis.ui.components.InfoCard
import java.time.Duration

@Composable
fun HomeworkReminderSettingsScreen(store: HomeworkReminderPreferenceStore) {
    val initial = remember { store.config() }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var normalHours by remember { mutableStateOf(initial.normalWindow.toHours().toString()) }
    var urgentHours by remember { mutableStateOf(initial.urgentWindow.toHours().toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val normal = normalHours.toLongOrNull()
        val urgent = urgentHours.toLongOrNull()
        when {
            normal == null || normal <= 0L -> error = "普通提醒阈值必须为正整数小时"
            urgent == null || urgent < 0L -> error = "紧急提醒阈值必须为非负整数小时"
            normal <= urgent -> error = "普通提醒阈值必须大于紧急提醒阈值"
            else -> {
                store.saveConfig(
                    HomeworkReminderConfig(
                        enabled = enabled,
                        normalWindow = Duration.ofHours(normal),
                        urgentWindow = Duration.ofHours(urgent),
                    )
                )
                error = null
                message = "已保存"
            }
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            InfoCard("作业提醒", subtitle = "后台每日检查未提交且即将截止的作业") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("启用提醒", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(
                    value = normalHours,
                    onValueChange = {
                        normalHours = it.filter(Char::isDigit)
                        error = null
                        message = null
                    },
                    label = { Text("普通提醒阈值（小时）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = urgentHours,
                    onValueChange = {
                        urgentHours = it.filter(Char::isDigit)
                        error = null
                        message = null
                    },
                    label = { Text("紧急提醒阈值（小时）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) {
                    Text("保存")
                }
            }
        }
    }
}
