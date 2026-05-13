package cn.edu.bjtu.mis.ui.screens

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import cn.edu.bjtu.mis.data.repository.CourseResourceRepository
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseResourcesScreen(repository: CourseResourceRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedCourseId by remember { mutableStateOf("") }
    var folderId by remember { mutableStateOf("0") }
    var search by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<LoadState<ModuleEnvelope<CourseResourcesData>>>(LoadState.Loading) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(nextFolder: String = folderId, nextCourse: String = selectedCourseId) {
        scope.launch {
            state = LoadState.Loading
            error = null
            runCatching {
                repository.listing(courseId = nextCourse.ifBlank { null }, folderId = nextFolder, search = search.ifBlank { null })
            }.onSuccess {
                selectedCourseId = it.data.selectedCourseId?.toString().orEmpty()
                folderId = it.data.folderId
                state = LoadState.Data(it)
            }.onFailure {
                state = LoadState.Error(it.message ?: "加载失败")
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            val data = (state as? LoadState.Data)?.value?.data
            CourseSelector(
                courses = data?.courses.orEmpty().map { it.courseId.toString() to listOfNotNull(it.courseName, it.teacherName).joinToString(" · ") },
                value = selectedCourseId,
                onValueChange = {
                    selectedCourseId = it
                    folderId = "0"
                    load("0", it)
                },
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("搜索课件名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { load() }) { Text("搜索") }
                    OutlinedButton(onClick = {
                        search = ""
                        load()
                    }) { Text("清空") }
                    OutlinedButton(onClick = {
                        folderId = "0"
                        load("0")
                    }) { Text("回到根目录") }
                }
            }
        }
        if (!error.isNullOrBlank()) {
            item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error) }
        }
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
            is LoadState.Data -> {
                val data = current.value.data
                item {
                    val currentTerm = data.currentTerm
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!currentTerm.isNullOrBlank()) {
                            AssistChip(onClick = {}, label = { Text(currentTerm) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {}, label = { Text("${data.resources.size} 个文件") })
                            AssistChip(onClick = {}, label = { Text("${data.folders.size} 个目录") })
                        }
                    }
                }
                items(data.folders, key = { it.folderId }) { folder ->
                    InfoCard(
                        title = folder.name,
                        subtitle = "目录 ${folder.folderId}",
                        modifier = Modifier.clickable {
                            folderId = folder.folderId
                            load(folder.folderId)
                        },
                    ) {
                        Text("点击进入目录")
                    }
                }
                items(data.resources, key = { it.rpId }) { resource ->
                    InfoCard(resource.name, subtitle = resource.uploadedAt) {
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                            KeyValue("类型", resource.extension, Modifier.weight(1f))
                            KeyValue("大小", resource.size?.let { "$it MB" }, Modifier.weight(1f))
                            KeyValue("下载", resource.downloadCount?.toString(), Modifier.weight(1f))
                        }
                        KeyValue("教师", resource.teacherName)
                        Button(
                            enabled = resource.canDownload && downloading == null,
                            onClick = {
                                scope.launch {
                                    downloading = resource.rpId
                                    error = null
                                    runCatching { repository.download(resource.rpId, resource.name, resource.extension) }
                                        .onSuccess { openFile(context, it) }
                                        .onFailure { error = it.message ?: "下载失败" }
                                    downloading = null
                                }
                            },
                        ) {
                            Text(if (downloading == resource.rpId) "下载中" else "下载")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseSelector(
    courses: List<Pair<String, String>>,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = courses.firstOrNull { it.first == value }?.second.orEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("选择课程") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            courses.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onValueChange(id)
                    },
                )
            }
        }
    }
}

internal fun openFile(context: android.content.Context, file: File): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val extension = file.extension.lowercase()
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching { context.startActivity(Intent.createChooser(intent, "打开文件")) }.isSuccess
}
