package cn.edu.bjtu.mis.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.provider.ProviderConstants
import cn.edu.bjtu.mis.data.repository.EmploymentConsultationRepository
import cn.edu.bjtu.mis.model.EmploymentArticleDetail
import cn.edu.bjtu.mis.model.EmploymentArticleSummary
import cn.edu.bjtu.mis.model.EmploymentCompanyInfo
import cn.edu.bjtu.mis.model.EmploymentConsultationData
import cn.edu.bjtu.mis.model.EmploymentContactInfo
import cn.edu.bjtu.mis.model.EmploymentFilterOption
import cn.edu.bjtu.mis.model.EmploymentFilterOptions
import cn.edu.bjtu.mis.model.EmploymentInfoDetail
import cn.edu.bjtu.mis.model.EmploymentInfoPage
import cn.edu.bjtu.mis.model.EmploymentInfoQuery
import cn.edu.bjtu.mis.model.EmploymentInfoSection
import cn.edu.bjtu.mis.model.EmploymentInfoSummary
import cn.edu.bjtu.mis.model.EmploymentPositionInfo
import cn.edu.bjtu.mis.model.EmploymentSectionType
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.SectionTitle
import kotlinx.coroutines.launch

private const val EmploymentPageSize = 15

@Composable
fun EmploymentConsultationScreen(repository: EmploymentConsultationRepository) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var homeState by remember { mutableStateOf<LoadState<ModuleEnvelope<EmploymentConsultationData>>>(LoadState.Loading) }
    var filterState by remember { mutableStateOf<LoadState<ModuleEnvelope<EmploymentFilterOptions>>>(LoadState.Loading) }
    var selectedType by remember { mutableStateOf(EmploymentSectionType.CareerTalk) }
    var pageStates by remember { mutableStateOf<Map<EmploymentSectionType, EmploymentPageUiState>>(emptyMap()) }
    var selectedProvinceByType by remember { mutableStateOf<Map<EmploymentSectionType, EmploymentFilterOption?>>(emptyMap()) }
    var cityOptionsByProvince by remember { mutableStateOf<Map<String, List<EmploymentFilterOption>>>(emptyMap()) }
    var selectedInfo by remember { mutableStateOf<EmploymentInfoSummary?>(null) }
    var detailState by remember { mutableStateOf<LoadState<ModuleEnvelope<EmploymentInfoDetail>>?>(null) }
    var selectedArticle by remember { mutableStateOf<EmploymentArticleSummary?>(null) }
    var articleState by remember { mutableStateOf<LoadState<ModuleEnvelope<EmploymentArticleDetail>>?>(null) }

    fun openUri(url: String) {
        runCatching { uriHandler.openUri(url) }
    }

    fun updatePageState(type: EmploymentSectionType, transform: (EmploymentPageUiState) -> EmploymentPageUiState) {
        val current = pageStates[type] ?: EmploymentPageUiState(defaultEmploymentQuery(type))
        pageStates = pageStates + (type to transform(current))
    }

    fun loadPage(
        type: EmploymentSectionType,
        reset: Boolean,
        queryOverride: EmploymentInfoQuery? = null,
    ) {
        val current = pageStates[type] ?: EmploymentPageUiState(defaultEmploymentQuery(type))
        val baseQuery = queryOverride ?: current.query
        val nextPageNo = if (reset) 1 else ((current.page?.pageNo ?: baseQuery.pageNo) + 1)
        val nextQuery = baseQuery.copy(pageNo = nextPageNo, pageSize = EmploymentPageSize)
        pageStates = pageStates + (type to current.copy(
            query = nextQuery,
            loading = reset,
            loadingMore = !reset,
            error = null,
        ))
        scope.launch {
            runCatching { repository.infoPage(nextQuery, forceRefresh = reset) }
                .onSuccess { envelope ->
                    val existing = if (reset) emptyList() else pageStates[type]?.page?.items.orEmpty()
                    val merged = (existing + envelope.data.items).distinctBy { it.id }
                    val mergedPage = envelope.data.copy(items = merged, query = nextQuery)
                    pageStates = pageStates + (type to EmploymentPageUiState(
                        query = nextQuery,
                        page = mergedPage,
                        loading = false,
                        loadingMore = false,
                        error = null,
                    ))
                }
                .onFailure { error ->
                    updatePageState(type) {
                        it.copy(
                            loading = false,
                            loadingMore = false,
                            error = error.message ?: "加载就业信息失败",
                        )
                    }
                }
        }
    }

    fun loadHome(forceRefresh: Boolean = false) {
        scope.launch {
            homeState = LoadState.Loading
            runCatching { repository.home(forceRefresh = forceRefresh) }
                .onSuccess { homeState = LoadState.Data(it) }
                .onFailure { homeState = LoadState.Error(it.message ?: "加载就业咨询失败") }
        }
    }

    fun loadFilters(forceRefresh: Boolean = false) {
        scope.launch {
            filterState = LoadState.Loading
            runCatching { repository.filterOptions(forceRefresh = forceRefresh) }
                .onSuccess { filterState = LoadState.Data(it) }
                .onFailure { filterState = LoadState.Error(it.message ?: "加载筛选项失败") }
        }
    }

    fun loadCities(parent: EmploymentFilterOption) {
        if (cityOptionsByProvince.containsKey(parent.value)) return
        scope.launch {
            runCatching { repository.cityOptions(parent.value) }
                .onSuccess { cityOptionsByProvince = cityOptionsByProvince + (parent.value to it.data) }
        }
    }

    fun openInfo(info: EmploymentInfoSummary) {
        selectedInfo = info
        detailState = LoadState.Loading
        scope.launch {
            runCatching { repository.infoDetail(info.type, info.id) }
                .onSuccess { detailState = LoadState.Data(it) }
                .onFailure { detailState = LoadState.Error(it.message ?: "加载详情失败") }
        }
    }

    fun openArticle(article: EmploymentArticleSummary) {
        selectedArticle = article
        articleState = LoadState.Loading
        scope.launch {
            runCatching { repository.article(article.id) }
                .onSuccess { articleState = LoadState.Data(it) }
                .onFailure { articleState = LoadState.Error(it.message ?: "加载指导详情失败") }
        }
    }

    fun closeDetail() {
        selectedInfo = null
        detailState = null
    }

    fun closeArticle() {
        selectedArticle = null
        articleState = null
    }

    LaunchedEffect(Unit) {
        loadHome(forceRefresh = true)
        loadFilters(forceRefresh = true)
        loadPage(EmploymentSectionType.CareerTalk, reset = true)
    }

    LaunchedEffect(selectedType) {
        val state = pageStates[selectedType]
        if (state == null || (state.page == null && !state.loading)) {
            loadPage(selectedType, reset = true)
        }
    }

    val activeArticleState = articleState
    val articleFallback = selectedArticle
    if (activeArticleState != null && articleFallback != null) {
        BackHandler(onBack = ::closeArticle)
        EmploymentArticleDetailScreen(
            state = activeArticleState,
            fallback = articleFallback,
            onBack = ::closeArticle,
            onOpenUrl = ::openUri,
        )
        return
    }

    val activeDetail = detailState
    val fallback = selectedInfo
    if (activeDetail != null && fallback != null) {
        BackHandler(onBack = ::closeDetail)
        EmploymentInfoDetailScreen(
            state = activeDetail,
            fallback = fallback,
            onBack = ::closeDetail,
            onOpenUrl = ::openUri,
        )
        return
    }

    val homeData = (homeState as? LoadState.Data)?.value?.data
    val sections = homeData?.sections?.takeIf { it.isNotEmpty() } ?: defaultEmploymentSections()
    val activeSection = sections.firstOrNull { it.type == selectedType } ?: sections.first()
    val activePageState = pageStates[activeSection.type] ?: EmploymentPageUiState(defaultEmploymentQuery(activeSection.type))
    val filterOptions = (filterState as? LoadState.Data)?.value?.data
        ?: homeData?.filters
        ?: EmploymentFilterOptions()

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
    ) {
        item {
            SectionTitle(
                title = "就业咨询",
                subtitle = "宣讲会、双选会、招聘信息与实习信息",
                trailing = {
                    OutlinedButton(
                        onClick = {
                            loadHome(forceRefresh = true)
                            loadFilters(forceRefresh = true)
                            loadPage(selectedType, reset = true)
                        },
                    ) {
                        Text("刷新")
                    }
                },
            )
        }
        item {
            EmploymentSectionTabs(
                sections = sections,
                selectedType = activeSection.type,
                onSelect = { selectedType = it },
            )
        }
        item {
            EmploymentFilterCard(
                type = activeSection.type,
                query = activePageState.query,
                options = filterOptions,
                selectedProvince = selectedProvinceByType[activeSection.type],
                cityOptions = employmentCityOptions(
                    selectedProvinceByType[activeSection.type],
                    cityOptionsByProvince,
                ),
                onQueryChange = { updated ->
                    updatePageState(activeSection.type) { it.copy(query = updated) }
                },
                onProvinceChange = { province ->
                    selectedProvinceByType = selectedProvinceByType + (activeSection.type to province)
                    if (province != null) loadCities(province)
                    val updated = activePageState.query.copy(
                        city = province?.value.orEmpty(),
                        cityName = province?.label.orEmpty(),
                    )
                    updatePageState(activeSection.type) { it.copy(query = updated) }
                },
                onApply = { loadPage(activeSection.type, reset = true, queryOverride = activePageState.query) },
                onClear = {
                    selectedProvinceByType = selectedProvinceByType + (activeSection.type to null)
                    val resetQuery = defaultEmploymentQuery(activeSection.type)
                    updatePageState(activeSection.type) { it.copy(query = resetQuery) }
                    loadPage(activeSection.type, reset = true, queryOverride = resetQuery)
                },
            )
        }
        item {
            EmploymentInfoPageCard(
                title = activeSection.title,
                state = activePageState,
                onItemClick = ::openInfo,
                onLoadMore = { loadPage(activeSection.type, reset = false) },
            )
        }
        when (val state = homeState) {
            LoadState.Loading -> item { LoadingOrError(state) }
            is LoadState.Error -> item { LoadingOrError(state) }
            is LoadState.Data -> {
                val data = state.value.data
                item {
                    EmploymentServiceCard(
                        data = data,
                        onOpenUrl = ::openUri,
                        onArticleClick = ::openArticle,
                    )
                }
                item {
                    EmploymentContactsCard(data.contacts)
                }
            }
        }
    }
}

@Composable
private fun EmploymentSectionTabs(
    sections: List<EmploymentInfoSection>,
    selectedType: EmploymentSectionType,
    onSelect: (EmploymentSectionType) -> Unit,
) {
    val selectedIndex = sections.indexOfFirst { it.type == selectedType }.coerceAtLeast(0)
    ScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 0.dp) {
        sections.forEach { section ->
            Tab(
                selected = section.type == selectedType,
                onClick = { onSelect(section.type) },
                text = { Text(section.title) },
            )
        }
    }
}

@Composable
private fun EmploymentFilterCard(
    type: EmploymentSectionType,
    query: EmploymentInfoQuery,
    options: EmploymentFilterOptions,
    selectedProvince: EmploymentFilterOption?,
    cityOptions: List<EmploymentFilterOption>,
    onQueryChange: (EmploymentInfoQuery) -> Unit,
    onProvinceChange: (EmploymentFilterOption?) -> Unit,
    onApply: () -> Unit,
    onClear: () -> Unit,
) {
    InfoCard(title = "筛选", subtitle = filterSubtitle(type, query)) {
        if (type != EmploymentSectionType.JobFair) {
            OutlinedTextField(
                value = query.title,
                onValueChange = { onQueryChange(query.copy(title = it)) },
                label = { Text(if (type == EmploymentSectionType.CareerTalk) "宣讲会关键词" else "职位或公司关键词") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        if (type == EmploymentSectionType.Recruitment || type == EmploymentSectionType.Internship) {
            EmploymentDropdown(
                label = "省份/热门城市",
                options = listOf(allEmploymentOption()) + employmentProvinceOptions(),
                selectedValue = selectedProvince?.value.orEmpty(),
                onSelect = { option ->
                    onProvinceChange(option.takeIf { it.value.isNotBlank() })
                },
            )
            EmploymentDropdown(
                label = "城市",
                options = listOf(allEmploymentOption()) + employmentHotCityOptions() + cityOptions,
                selectedValue = query.city,
                onSelect = { option ->
                    onQueryChange(query.copy(city = option.value, cityName = option.label.takeIf { option.value.isNotBlank() }.orEmpty()))
                },
            )
            EmploymentDropdown(
                label = "单位性质",
                options = listOf(allEmploymentOption()) + options.corporationNatures,
                selectedValue = query.corporationNature,
                onSelect = { option ->
                    onQueryChange(
                        query.copy(
                            corporationNature = option.value,
                            corporationNatureLabel = option.label.takeIf { option.value.isNotBlank() }.orEmpty(),
                        ),
                    )
                },
            )
            EmploymentDropdown(
                label = "所属行业",
                options = listOf(allEmploymentOption()) + options.industries,
                selectedValue = query.industry,
                onSelect = { option ->
                    onQueryChange(
                        query.copy(
                            industry = option.value,
                            industryLabel = option.label.takeIf { option.value.isNotBlank() }.orEmpty(),
                        ),
                    )
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onApply) { Text("搜索") }
            OutlinedButton(onClick = onClear) { Text("重置") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmploymentDropdown(
    label: String,
    options: List<EmploymentFilterOption>,
    selectedValue: String,
    onSelect: (EmploymentFilterOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label.orEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.distinctBy { it.value }.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun EmploymentInfoPageCard(
    title: String,
    state: EmploymentPageUiState,
    onItemClick: (EmploymentInfoSummary) -> Unit,
    onLoadMore: () -> Unit,
) {
    val page = state.page
    val countText = page?.let {
        if (it.totalCount > 0) "已加载 ${it.items.size}/${it.totalCount} 条" else "已加载 ${it.items.size} 条"
    } ?: "本地分页加载"
    InfoCard(
        title = title,
        subtitle = countText,
    ) {
        when {
            state.loading -> LoadingOrError(LoadState.Loading)
            state.error != null && page == null -> LoadingOrError(LoadState.Error(state.error))
            page == null || page.items.isEmpty() -> Text(
                "暂无$title",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                page.items.forEachIndexed { index, item ->
                    EmploymentInfoRow(item = item, onClick = { onItemClick(item) })
                    if (index != page.items.lastIndex) HorizontalDivider()
                }
                if (state.error != null) {
                    Text(
                        state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (page.hasNext) {
                    OutlinedButton(
                        onClick = onLoadMore,
                        enabled = !state.loadingMore,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.loadingMore) "加载中" else "加载更多")
                    }
                } else {
                    AssistChip(onClick = {}, label = { Text("已全部加载") })
                }
            }
        }
    }
}

@Composable
private fun EmploymentInfoRow(item: EmploymentInfoSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            item.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        listOfNotNull(item.organization, item.location)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .takeIf { it.isNotBlank() }
            ?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        val meta = when (item.type) {
            EmploymentSectionType.CareerTalk,
            EmploymentSectionType.JobFair -> listOfNotNull(
                item.startTime?.let { "时间：${shortDateTime(it)}" },
                item.statusLabel,
                item.browseNumber?.let { "浏览 $it" },
            )
            EmploymentSectionType.Recruitment,
            EmploymentSectionType.Internship -> listOfNotNull(
                item.education?.let { "学历：$it" },
                item.positionCount?.let { "${it}个职位" },
                item.endTime?.let { "截止：${shortDate(it)}" },
            )
        }.filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!item.majorName.isNullOrBlank()) {
            Text(
                item.majorName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmploymentServiceCard(
    data: EmploymentConsultationData,
    onOpenUrl: (String) -> Unit,
    onArticleClick: (EmploymentArticleSummary) -> Unit,
) {
    val guide = data.consultationGuide
    InfoCard(
        title = "就业咨询服务",
        subtitle = "个性化咨询、联系方式与指导贴士",
        trailing = {
            Button(onClick = { onOpenUrl(data.appointmentUrl) }) { Text("预约") }
        },
    ) {
        val text = guide?.contentText
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.length > 220) it.take(220).trimEnd() + "..." else it }
            ?: "就业指导中心提供就业、创业及职业生涯规划相关的一对一咨询服务。"
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EmploymentArticleList(data.articles, onArticleClick)
    }
}

@Composable
private fun EmploymentArticleList(
    articles: List<EmploymentArticleSummary>,
    onArticleClick: (EmploymentArticleSummary) -> Unit,
) {
    if (articles.isEmpty()) return
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    articles.forEachIndexed { index, article ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onArticleClick(article) }
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                article.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(article.releaseDate, article.publisher)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (index != articles.lastIndex) HorizontalDivider()
    }
}

@Composable
private fun EmploymentContactsCard(contacts: List<EmploymentContactInfo>) {
    InfoCard(title = "咨询方式", subtitle = "就业与创业指导中心公开联系方式") {
        contacts.forEachIndexed { index, contact ->
            ContactRow(contact)
            if (index != contacts.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun ContactRow(contact: EmploymentContactInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(contact.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        listOfNotNull(contact.description, contact.location, contact.phone, contact.email)
            .filter { it.isNotBlank() }
            .forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

@Composable
private fun EmploymentArticleDetailScreen(
    state: LoadState<ModuleEnvelope<EmploymentArticleDetail>>,
    fallback: EmploymentArticleSummary,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
    ) {
        item {
            SectionTitle(
                title = "指导详情",
                subtitle = fallback.title,
                trailing = { OutlinedButton(onClick = onBack) { Text("返回") } },
            )
        }
        when (state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(state) }
            is LoadState.Data -> {
                val detail = state.value.data
                item {
                    InfoCard(title = detail.title, subtitle = listOfNotNull(detail.releaseDate, detail.publisher).joinToString(" · ").ifBlank { null }) {
                        Text(
                            detail.contentText.ifBlank { detail.description.orEmpty() },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (detail.attachments.isNotEmpty()) {
                    item {
                        InfoCard(title = "链接与附件", subtitle = null) {
                            detail.attachments.forEach { attachment ->
                                OutlinedButton(onClick = { onOpenUrl(attachment.url) }) {
                                    Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmploymentInfoDetailScreen(
    state: LoadState<ModuleEnvelope<EmploymentInfoDetail>>,
    fallback: EmploymentInfoSummary,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
    ) {
        item {
            SectionTitle(
                title = "详情",
                subtitle = fallback.title,
                trailing = { OutlinedButton(onClick = onBack) { Text("返回") } },
            )
        }
        when (state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(state) }
            is LoadState.Data -> {
                val detail = state.value.data
                item { EmploymentDetailHeader(detail = detail, onOpenUrl = onOpenUrl) }
                if (detail.positions.isNotEmpty()) {
                    item { EmploymentPositionsCard(detail.type, detail.positions) }
                }
                if (detail.contentText.isNotBlank()) {
                    item {
                        InfoCard(title = detailContentTitle(detail.type), subtitle = null) {
                            Text(
                                detail.contentText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                detail.company?.let { company ->
                    item { EmploymentCompanyCard(company, onOpenUrl) }
                }
                if (detail.attachments.isNotEmpty()) {
                    item {
                        InfoCard(title = "链接与附件", subtitle = null) {
                            detail.attachments.forEach { attachment ->
                                OutlinedButton(onClick = { onOpenUrl(attachment.url) }) {
                                    Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmploymentDetailHeader(
    detail: EmploymentInfoDetail,
    onOpenUrl: (String) -> Unit,
) {
    InfoCard(
        title = detail.title,
        subtitle = detail.organization ?: detailTypeTitle(detail.type),
    ) {
        listOfNotNull(
            detail.startTime?.let { "开始：${shortDateTime(it)}" },
            detail.endTime?.let { "结束：${shortDateTime(it)}" },
            detail.location?.let { "地点：$it" },
            detail.browseNumber?.let { "浏览：$it" },
            detail.statusLabel,
        ).forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        listOfNotNull(
            detail.resumeReceiveEmail?.let { "简历接收邮箱：$it" },
            detail.onlineApplicationUrl?.let { "网申地址：$it" },
            detail.contactsName?.let { "联系人：$it" },
            detail.telephone?.let { "电话：$it" },
            detail.email?.let { "邮箱：$it" },
            detail.phoneNumber?.let { "手机：$it" },
        ).forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        detail.onlineApplicationUrl?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { url ->
            OutlinedButton(onClick = { onOpenUrl(url) }) { Text("打开网申地址") }
        }
    }
}

@Composable
private fun EmploymentPositionsCard(
    type: EmploymentSectionType,
    positions: List<EmploymentPositionInfo>,
) {
    InfoCard(
        title = if (type == EmploymentSectionType.JobFair) "参会单位" else "职位信息",
        subtitle = "${positions.size} 条",
    ) {
        positions.forEachIndexed { index, position ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(position.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                listOfNotNull(
                    position.education?.let { "学历：$it" },
                    position.demandNumber?.let { "需求：$it" },
                    position.majorName?.let { "专业：$it" },
                    position.cityName?.let { "地点：$it" },
                ).forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                position.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (index != positions.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun EmploymentCompanyCard(
    company: EmploymentCompanyInfo,
    onOpenUrl: (String) -> Unit,
) {
    InfoCard(
        title = company.name ?: "单位信息",
        subtitle = listOfNotNull(company.nature, company.scale).joinToString(" · ").ifBlank { null },
    ) {
        listOfNotNull(company.address, company.website).forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        company.website?.takeIf { it.startsWith("http", ignoreCase = true) }?.let { website ->
            OutlinedButton(onClick = { onOpenUrl(website) }) { Text("官网") }
        }
        company.introduction?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class EmploymentPageUiState(
    val query: EmploymentInfoQuery,
    val page: EmploymentInfoPage? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
)

private fun defaultEmploymentQuery(type: EmploymentSectionType): EmploymentInfoQuery =
    EmploymentInfoQuery(type = type, pageSize = EmploymentPageSize)

private fun defaultEmploymentSections(): List<EmploymentInfoSection> = listOf(
    EmploymentInfoSection(EmploymentSectionType.CareerTalk, "宣讲会", "${ProviderConstants.JOB_BASE_URL}/frontpage/bjtu/html/recruitmentFairList.html?type=3"),
    EmploymentInfoSection(EmploymentSectionType.JobFair, "双选会", "${ProviderConstants.JOB_BASE_URL}/frontpage/bjtu/html/bilateralchosefairList.html?type=4"),
    EmploymentInfoSection(EmploymentSectionType.Recruitment, "招聘信息", "${ProviderConstants.JOB_BASE_URL}/frontpage/bjtu/html/recruitmentinfoList.html?type=1"),
    EmploymentInfoSection(EmploymentSectionType.Internship, "实习信息", "${ProviderConstants.JOB_BASE_URL}/frontpage/bjtu/html/recruitmentinfoList.html?type=2"),
)

private fun filterSubtitle(type: EmploymentSectionType, query: EmploymentInfoQuery): String =
    when (type) {
        EmploymentSectionType.JobFair -> "双选会支持分页加载"
        EmploymentSectionType.CareerTalk -> query.title.takeIf { it.isNotBlank() }?.let { "关键词：$it" } ?: "按关键词搜索宣讲会"
        EmploymentSectionType.Recruitment,
        EmploymentSectionType.Internship -> listOfNotNull(
            query.title.takeIf { it.isNotBlank() }?.let { "关键词：$it" },
            query.cityName.takeIf { it.isNotBlank() }?.let { "城市：$it" },
            query.corporationNatureLabel.takeIf { it.isNotBlank() }?.let { "性质：$it" },
            query.industryLabel.takeIf { it.isNotBlank() }?.let { "行业：$it" },
        ).joinToString(" · ").ifBlank { "按城市、单位性质、行业和关键词筛选" }
    }

private fun employmentCityOptions(
    selectedProvince: EmploymentFilterOption?,
    cityOptionsByProvince: Map<String, List<EmploymentFilterOption>>,
): List<EmploymentFilterOption> =
    selectedProvince?.let { cityOptionsByProvince[it.value] }.orEmpty()

private fun allEmploymentOption(): EmploymentFilterOption =
    EmploymentFilterOption(value = "", label = "全部")

private fun employmentHotCityOptions(): List<EmploymentFilterOption> = listOf(
    EmploymentFilterOption("110000", "北京市"),
    EmploymentFilterOption("310000", "上海市"),
    EmploymentFilterOption("440100", "广州市"),
    EmploymentFilterOption("440300", "深圳市"),
    EmploymentFilterOption("330100", "杭州市"),
    EmploymentFilterOption("510100", "成都市"),
    EmploymentFilterOption("120000", "天津市"),
    EmploymentFilterOption("420100", "武汉市"),
    EmploymentFilterOption("610100", "西安市"),
)

private fun employmentProvinceOptions(): List<EmploymentFilterOption> = listOf(
    EmploymentFilterOption("340000", "安徽"),
    EmploymentFilterOption("110000", "北京"),
    EmploymentFilterOption("500000", "重庆"),
    EmploymentFilterOption("350000", "福建"),
    EmploymentFilterOption("620000", "甘肃"),
    EmploymentFilterOption("440000", "广东"),
    EmploymentFilterOption("450000", "广西"),
    EmploymentFilterOption("520000", "贵州"),
    EmploymentFilterOption("460000", "海南"),
    EmploymentFilterOption("230000", "黑龙江"),
    EmploymentFilterOption("130000", "河北"),
    EmploymentFilterOption("410000", "河南"),
    EmploymentFilterOption("420000", "湖北"),
    EmploymentFilterOption("430000", "湖南"),
    EmploymentFilterOption("220000", "吉林"),
    EmploymentFilterOption("320000", "江苏"),
    EmploymentFilterOption("360000", "江西"),
    EmploymentFilterOption("210000", "辽宁"),
    EmploymentFilterOption("150000", "内蒙古"),
    EmploymentFilterOption("640000", "宁夏"),
    EmploymentFilterOption("630000", "青海"),
    EmploymentFilterOption("370000", "山东"),
    EmploymentFilterOption("140000", "山西"),
    EmploymentFilterOption("610000", "陕西"),
    EmploymentFilterOption("310000", "上海"),
    EmploymentFilterOption("510000", "四川"),
    EmploymentFilterOption("120000", "天津"),
    EmploymentFilterOption("540000", "西藏"),
    EmploymentFilterOption("650000", "新疆"),
    EmploymentFilterOption("530000", "云南"),
    EmploymentFilterOption("330000", "浙江"),
)

private fun detailTypeTitle(type: EmploymentSectionType): String =
    when (type) {
        EmploymentSectionType.CareerTalk -> "宣讲会"
        EmploymentSectionType.JobFair -> "双选会"
        EmploymentSectionType.Recruitment -> "招聘信息"
        EmploymentSectionType.Internship -> "实习信息"
    }

private fun detailContentTitle(type: EmploymentSectionType): String =
    when (type) {
        EmploymentSectionType.CareerTalk -> "招聘简章"
        EmploymentSectionType.JobFair -> "双选会描述"
        EmploymentSectionType.Recruitment -> "招聘简章"
        EmploymentSectionType.Internship -> "实习说明"
    }

private fun shortDateTime(value: String): String =
    value.trim().removeSuffix(":00")

private fun shortDate(value: String): String =
    value.trim().takeIf { it.length >= 10 }?.take(10) ?: value.trim()
