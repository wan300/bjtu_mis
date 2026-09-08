package cn.edu.bjtu.mis.ui.screens

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedButton
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
import cn.edu.bjtu.mis.data.exporting.ScheduleExportDocument
import cn.edu.bjtu.mis.data.exporting.ScheduleExportFormat
import cn.edu.bjtu.mis.data.exporting.ScheduleExportStorage
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ProgressiveModuleState
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.ProgressiveStatus
import cn.edu.bjtu.mis.ui.theme.AppUiStyle
import cn.edu.bjtu.mis.ui.theme.LocalAppDesign
import cn.edu.bjtu.mis.ui.theme.LocalAppUiStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import cn.edu.bjtu.mis.model.TermOption
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color

private data class PendingScheduleExport(
    val document: ScheduleExportDocument,
    val format: ScheduleExportFormat,
)

@Composable
internal fun rememberScheduleExportLauncher(): (ScheduleExportDocument, ScheduleExportFormat) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingExport by remember { mutableStateOf<PendingScheduleExport?>(null) }

    fun saveExport(export: PendingScheduleExport) {
        scope.launch {
            runCatching {
                ScheduleExportStorage.save(context, export.document, export.format)
            }.onSuccess { saved ->
                if (export.format == ScheduleExportFormat.Pdf) {
                    runCatching { ScheduleExportStorage.sharePdf(context, saved) }
                }
                Toast.makeText(context, "已导出：${saved.displayName}", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Toast.makeText(context, error.message ?: "导出失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val export = pendingExport
        pendingExport = null
        if (granted && export != null) {
            saveExport(export)
        } else {
            Toast.makeText(context, "需要存储权限才能导出文件", Toast.LENGTH_LONG).show()
        }
    }

    return { document, format ->
        val export = PendingScheduleExport(document, format)
        if (ScheduleExportStorage.needsLegacyWritePermission(context)) {
            pendingExport = export
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveExport(export)
        }
    }
}

@Composable
internal fun SecondaryModuleLinks(
    title: String,
    links: List<Pair<String, String>>,
    onNavigate: (String) -> Unit,
) {
    InfoCard(title) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            links.forEach { (route, label) ->
                OutlinedButton(
                    onClick = { onNavigate(route) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
internal fun <T> DataScreen(
    title: String,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    refreshKey: Any? = Unit,
    loader: suspend (ModuleLoadStrategy) -> ModuleEnvelope<T>,
    leadingContent: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
    content: androidx.compose.foundation.lazy.LazyListScope.(ModuleEnvelope<T>) -> Unit,
) {
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    val design = LocalAppDesign.current
    var retryVersion by remember(refreshKey) { mutableStateOf(0) }
    var state by remember(refreshKey, retryVersion) {
        mutableStateOf<LoadState<ModuleEnvelope<T>>>(LoadState.Loading)
    }
    var initialLoadConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(refreshKey, retryVersion) {
        val strategy = if (initialLoadConsumed) ModuleLoadStrategy.NetworkFirst else initialLoadStrategy
        initialLoadConsumed = true
        state = LoadState.Loading
        runCatching { loader(strategy) }
            .onSuccess { state = LoadState.Data(it) }
            .onFailure { state = LoadState.Error(it.message ?: "加载失败") }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(
            if (isApple) design.itemSpacing else 14.dp,
        ),
    ) {
        leadingContent()
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> item {
                LoadingOrError(
                    state = current,
                    onRetry = if (current is LoadState.Error) {
                        { retryVersion += 1 }
                    } else {
                        null
                    },
                )
            }
            is LoadState.Data -> content(current.value)
        }
    }
}

@Composable
internal fun <T> ProgressiveDataScreen(
    title: String,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    refreshKey: Any? = Unit,
    loader: (ModuleLoadStrategy) -> Flow<ProgressiveModuleState<T>>,
    leadingContent: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
    content: androidx.compose.foundation.lazy.LazyListScope.(ProgressiveModuleState<T>, ModuleEnvelope<T>) -> Unit,
) {
    val isApple = LocalAppUiStyle.current == AppUiStyle.Apple
    val design = LocalAppDesign.current
    var retryVersion by remember(refreshKey) { mutableStateOf(0) }
    var state by remember(refreshKey, retryVersion) {
        mutableStateOf(ProgressiveModuleState<T>())
    }
    var initialLoadConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(refreshKey, retryVersion) {
        val strategy = if (initialLoadConsumed) ModuleLoadStrategy.NetworkFirst else initialLoadStrategy
        initialLoadConsumed = true
        state = ProgressiveModuleState<T>()
        runCatching {
            loader(strategy).collect { state = it }
        }.onFailure { error ->
            state = state.copy(
                loading = false,
                complete = true,
                errors = state.errors + (error.message ?: "加载失败"),
            )
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(
            if (isApple) design.itemSpacing else 14.dp,
        ),
    ) {
        leadingContent()
        val envelope = state.envelope
        if (envelope != null) {
            item(key = "$title-progressive-status") {
                ProgressiveStatus(state)
            }
            content(state, envelope)
        } else {
            when {
                state.loading -> item { LoadingOrError(LoadState.Loading) }
                state.errors.isNotEmpty() -> item {
                    LoadingOrError(
                        LoadState.Error(state.errors.joinToString("；")),
                        onRetry = { retryVersion += 1 },
                    )
                }
                else -> item {
                    LoadingOrError(
                        LoadState.Error("加载失败"),
                        onRetry = { retryVersion += 1 },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TermSelector(
    terms: List<TermOption>,
    value: String?,
    onValueChange: (String) -> Unit,
    includeAllOption: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = if (includeAllOption && value == HISTORY_ALL_TERMS) {
        "全部学期"
    } else {
        terms.firstOrNull { it.value == value }?.label ?: value.orEmpty()
    }
    val enabled = terms.isNotEmpty() || includeAllOption

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            if (enabled) {
                expanded = !expanded
            }
        },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("选择学期") },
            placeholder = { Text("暂无可选学期") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (includeAllOption) {
                DropdownMenuItem(
                    text = { Text("全部学期") },
                    onClick = {
                        expanded = false
                        onValueChange(HISTORY_ALL_TERMS)
                    },
                )
            }
            terms.forEach { term ->
                DropdownMenuItem(
                    text = { Text(term.label.ifBlank { term.value }) },
                    onClick = {
                        expanded = false
                        onValueChange(term.value)
                    },
                )
            }
        }
    }
}

internal const val HISTORY_ALL_TERMS = "all"

@Composable
internal fun ModuleStatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}
