package cn.edu.bjtu.mis.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.update.AppUpdateInfo

data class AppUpdateDialogPreference(
    val ignoreThisVersion: Boolean,
    val disableAutoPrompts: Boolean,
)

@Composable
fun AppUpdateAvailableDialog(
    update: AppUpdateInfo,
    showAutoPromptRestore: Boolean = false,
    onRestoreAutoPrompts: (() -> Unit)? = null,
    onDismiss: (AppUpdateDialogPreference) -> Unit,
    onOpenUpdate: (AppUpdateDialogPreference) -> Unit,
) {
    var ignoreThisVersion by remember(update.latestVersion) { mutableStateOf(false) }
    var disableAutoPrompts by remember(update.latestVersion) { mutableStateOf(false) }

    fun preference() = AppUpdateDialogPreference(
        ignoreThisVersion = ignoreThisVersion,
        disableAutoPrompts = disableAutoPrompts,
    )

    AlertDialog(
        onDismissRequest = { onDismiss(preference()) },
        title = { Text("发现新版本") },
        text = {
            Column {
                Text("当前版本：${update.currentVersion}\n最新版本：${update.latestVersion}")
                UpdatePreferenceCheckboxRow(
                    checked = ignoreThisVersion,
                    text = "本次更新不再提示",
                    onCheckedChange = { ignoreThisVersion = it },
                )
                UpdatePreferenceCheckboxRow(
                    checked = disableAutoPrompts,
                    text = "永远不提示更新",
                    onCheckedChange = { disableAutoPrompts = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onOpenUpdate(preference()) }) {
                Text("查看更新")
            }
        },
        dismissButton = {
            Row {
                if (showAutoPromptRestore && onRestoreAutoPrompts != null) {
                    TextButton(onClick = onRestoreAutoPrompts) {
                        Text("恢复自动提示")
                    }
                }
                TextButton(onClick = { onDismiss(preference()) }) {
                    Text("稍后")
                }
            }
        },
    )
}

@Composable
private fun UpdatePreferenceCheckboxRow(
    checked: Boolean,
    text: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
