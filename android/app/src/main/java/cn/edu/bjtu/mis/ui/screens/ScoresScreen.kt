package cn.edu.bjtu.mis.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ModuleRepository
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.ScoreDetailData
import cn.edu.bjtu.mis.model.ScoreDetailField
import cn.edu.bjtu.mis.model.ScoreDetailTable
import cn.edu.bjtu.mis.model.ScoreItem
import cn.edu.bjtu.mis.model.TermOption
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import kotlinx.coroutines.launch

private val ScoreTypeOptions = listOf(
    "lr" to "本学期成绩",
    "ln" to "历年成绩",
    "en" to "英语认定成绩",
    "rm" to "留级库成绩",
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoresScreen(
    repository: ModuleRepository,
    history: Boolean = false,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    val scope = rememberCoroutineScope()
    var requestedTerm by remember(history) { mutableStateOf(if (history) HISTORY_ALL_TERMS else "") }
    var requestedScoreType by remember(history) { mutableStateOf("lr") }
    var refreshNonce by remember(history) { mutableStateOf(0) }
    var selectedScore by remember { mutableStateOf<ScoreItem?>(null) }
    var scoreDetailState by remember { mutableStateOf<LoadState<ModuleEnvelope<ScoreDetailData>>?>(null) }
    val scoreDetailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title = if (history) "历史成绩" else "主修成绩"
    val selectedHistoryTerm = requestedTerm.takeIf { history && it != HISTORY_ALL_TERMS }
    val selectedScoreTerm = requestedTerm.takeIf { !history && it.isNotBlank() }

    fun openScoreDetail(score: ScoreItem) {
        selectedScore = score
        scoreDetailState = LoadState.Loading
        scope.launch {
            val detailPath = score.detailPath
            if (detailPath.isNullOrBlank()) {
                scoreDetailState = LoadState.Error("这条成绩没有可用的分数明细链接。")
                return@launch
            }
            runCatching { repository.scoreDetail(detailPath) }
                .onSuccess { scoreDetailState = LoadState.Data(it) }
                .onFailure { scoreDetailState = LoadState.Error(it.message ?: "分数详情加载失败") }
        }
    }

    fun androidx.compose.foundation.lazy.LazyListScope.scoreContent(
        envelope: ModuleEnvelope<ScoreData>,
        loading: Boolean,
    ) {
        val data = envelope.data
        val currentTerm = data.currentTerm
        if (history) {
            item {
                TermSelector(
                    terms = data.availableTerms,
                    value = requestedTerm,
                    onValueChange = { requestedTerm = it },
                    includeAllOption = true,
                )
            }
        } else {
            item {
                ScoreQueryCard(
                    terms = data.availableTerms,
                    selectedTerm = requestedTerm.ifBlank { data.currentTerm.orEmpty() },
                    scoreType = requestedScoreType,
                    onTermChange = {
                        requestedTerm = it
                        refreshNonce += 1
                    },
                    onScoreTypeChange = {
                        requestedScoreType = it
                        refreshNonce += 1
                    },
                )
            }
        }
        if (history && requestedTerm == HISTORY_ALL_TERMS) {
            item {
                AssistChip(onClick = {}, label = { Text("全部学期") })
            }
        } else if (!currentTerm.isNullOrBlank()) {
            item {
                AssistChip(onClick = {}, label = { Text(currentTerm) })
            }
        }
        if (data.items.isEmpty() && !loading) {
            item {
                InfoCard("暂无$title") {
                    Text("当前没有可展示的${title}记录。", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            items(data.items, key = { it.courseName + it.term.orEmpty() + it.score.orEmpty() }) { score ->
                InfoCard(score.courseName, subtitle = score.term) {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("学分", score.credit, Modifier.weight(1f))
                        KeyValue("成绩", score.score, Modifier.weight(1f))
                        if (history) {
                            KeyValue("加分成绩", score.bonusScore, Modifier.weight(1f))
                        } else {
                            KeyValue("教师", score.teacher, Modifier.weight(1f))
                        }
                    }
                    if (history) {
                        KeyValue("教师", score.teacher)
                    }
                    ScoreInlineDetailView(score.detailData)
                    if (score.detailData == null) {
                        KeyValue("详情", score.detail)
                    }
                    if (!score.detailPath.isNullOrBlank() && (history || score.detailData == null)) {
                        OutlinedButton(onClick = { openScoreDetail(score) }) {
                            Text("查看分数详情")
                        }
                    }
                }
            }
        }
    }

    if (history) {
        ProgressiveDataScreen(
            title = title,
            initialLoadStrategy = initialLoadStrategy,
            refreshKey = requestedTerm,
            loader = { strategy -> repository.historyScoresProgressive(selectedHistoryTerm, strategy) },
        ) { progressiveState, envelope ->
            scoreContent(envelope, progressiveState.loading)
        }
    } else {
        DataScreen(
            title = title,
            initialLoadStrategy = initialLoadStrategy,
            refreshKey = listOf(requestedTerm, requestedScoreType, refreshNonce),
            loader = { strategy -> repository.scores(term = selectedScoreTerm, ctype = requestedScoreType, strategy = strategy) },
        ) { envelope ->
            scoreContent(envelope, loading = false)
        }
    }

    selectedScore?.let { score ->
        ModalBottomSheet(
            onDismissRequest = {
                selectedScore = null
                scoreDetailState = null
            },
            sheetState = scoreDetailSheetState,
        ) {
            ScoreDetailSheetContent(
                score = score,
                state = scoreDetailState,
                onRetry = { openScoreDetail(score) },
                modifier = Modifier.heightIn(max = 720.dp),
            )
        }
    }
}

@Composable
private fun ScoreInlineDetailView(detail: ScoreDetailData?) {
    if (detail == null) return
    val tables = detail.tables.filter { table -> table.headers.isNotEmpty() || table.rows.isNotEmpty() }
    val extraFields = scoreDetailExtraFields(detail)
    if (tables.isEmpty() && extraFields.isEmpty()) {
        detail.rawText?.let { KeyValue("详情", it) }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tables.forEach { table -> ScoreDetailInlineTableView(table) }
        extraFields.forEach { field -> KeyValue(field.label, field.value) }
    }
}

@Composable
private fun ScoreDetailInlineTableView(table: ScoreDetailTable) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            table.title?.takeIf { it.isNotBlank() } ?: "分数构成",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (table.headers.isNotEmpty()) {
                ScoreDetailTableRow(table.headers, emphasized = true)
                HorizontalDivider()
            }
            table.rows.forEach { row -> ScoreDetailTableRow(row) }
        }
    }
}

private fun scoreDetailExtraFields(detail: ScoreDetailData): List<ScoreDetailField> {
    val hasComponentTable = detail.tables.any(::isScoreComponentTable)
    return detail.fields.filterNot { field ->
        (hasComponentTable && isScoreComponentLabel(field.label)) ||
            isScoreComponentField(field.label) ||
            isScoreSummaryField(field.label)
    }
}

private fun isScoreComponentTable(table: ScoreDetailTable): Boolean {
    val text = (table.headers + table.rows.flatten()).joinToString(" ")
    return listOf("平时", "期末", "期中", "比例", "占比", "权重").any { text.contains(it) } &&
        listOf("成绩", "得分", "分数", "比例", "占比", "权重").any { text.contains(it) }
}

private fun isScoreComponentLabel(label: String): Boolean {
    val compact = label.replace(Regex("""\s+"""), "")
    return listOf("平时", "期末", "期中", "实验", "上机", "作业", "课堂", "考勤", "出勤", "小测", "测验", "报告", "论文", "答辩", "实践", "项目")
        .any { compact.contains(it) }
}

private fun isScoreComponentField(label: String): Boolean {
    val compact = label.replace(Regex("""\s+"""), "")
    return isScoreComponentLabel(label) &&
        listOf("成绩", "得分", "分数", "比例", "占比", "权重", "比重", "百分比")
            .any { compact.contains(it) }
}

private fun isScoreSummaryField(label: String): Boolean {
    val compact = label.replace(Regex("""\s+"""), "")
    return listOf("课程", "总评", "最终成绩", "成绩").any { compact == it }
}

@Composable
private fun ScoreDetailSheetContent(
    score: ScoreItem,
    state: LoadState<ModuleEnvelope<ScoreDetailData>>?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(score.courseName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(score.term.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("成绩", score.score, Modifier.weight(1f))
            KeyValue("加分成绩", score.bonusScore, Modifier.weight(1f))
            KeyValue("学分", score.credit, Modifier.weight(1f))
        }

        when (state) {
            null -> Unit
            LoadState.Loading, is LoadState.Error -> {
                LoadingOrError(state)
                if (state is LoadState.Error) {
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text("重试")
                    }
                }
            }
            is LoadState.Data -> {
                val detail = state.value.data
                if (!detail.title.isNullOrBlank() && detail.title != "详情") {
                    Text(detail.title, style = MaterialTheme.typography.titleMedium)
                }
                if (detail.fields.isNotEmpty()) {
                    InfoCard("明细字段") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            detail.fields.forEach { field ->
                                KeyValue(field.label, field.value)
                            }
                        }
                    }
                }
                detail.tables.forEach { table ->
                    ScoreDetailTableView(table)
                }
                if (detail.fields.isEmpty() && detail.tables.isEmpty()) {
                    Text(
                        detail.rawText ?: "这条成绩没有可展示的明细内容。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreDetailTableView(table: ScoreDetailTable) {
    InfoCard(table.title ?: "分数构成") {
        Column(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (table.headers.isNotEmpty()) {
                ScoreDetailTableRow(table.headers, emphasized = true)
                HorizontalDivider()
            }
            table.rows.forEach { row ->
                ScoreDetailTableRow(row)
            }
        }
    }
}

@Composable
private fun ScoreDetailTableRow(row: List<String>, emphasized: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { cell ->
            Text(
                text = cell,
                modifier = Modifier.width(120.dp),
                style = if (emphasized) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ScoreQueryCard(
    terms: List<TermOption>,
    selectedTerm: String?,
    scoreType: String,
    onTermChange: (String) -> Unit,
    onScoreTypeChange: (String) -> Unit,
) {
    InfoCard("筛选") {
        TermSelector(
            terms = terms,
            value = selectedTerm,
            onValueChange = onTermChange,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ScoreTypeOptions.forEach { (key, label) ->
                FilterChip(
                    selected = scoreType == key,
                    onClick = { onScoreTypeChange(key) },
                    label = { Text(label) },
                )
            }
        }
    }
}

