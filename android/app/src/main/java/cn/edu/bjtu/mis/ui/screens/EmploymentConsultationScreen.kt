package cn.edu.bjtu.mis.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import cn.edu.bjtu.mis.model.EmploymentArticleSummary
import cn.edu.bjtu.mis.model.EmploymentCompanyInfo
import cn.edu.bjtu.mis.model.EmploymentConsultationData
import cn.edu.bjtu.mis.model.EmploymentContactInfo
import cn.edu.bjtu.mis.model.EmploymentInfoDetail
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

@Composable
fun EmploymentConsultationScreen(repository: EmploymentConsultationRepository) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var homeState by remember { mutableStateOf<LoadState<ModuleEnvelope<EmploymentConsultationData>>>(LoadState.Loading) }
    var selectedType by remember { mutableStateOf(EmploymentSectionType.CareerTalk) }
    var selectedInfo by remember { mutableStateOf<EmploymentInfoSummary?>(null) }
    var detailState by remember { mutableStateOf<LoadState<ModuleEnvelope<EmploymentInfoDetail>>?>(null) }

    fun openUri(url: String) {
        runCatching { uriHandler.openUri(url) }
    }

    fun closeDetail() {
        selectedInfo = null
        detailState = null
    }

    fun loadHome(forceRefresh: Boolean = false) {
        scope.launch {
            homeState = LoadState.Loading
            runCatching { repository.home(forceRefresh = forceRefresh) }
                .onSuccess { homeState = LoadState.Data(it) }
                .onFailure { homeState = LoadState.Error(it.message ?: "加载就业咨询失败") }
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

    LaunchedEffect(Unit) {
        loadHome(forceRefresh = true)
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

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
    ) {
        item {
            SectionTitle(
                title = "就业咨询",
                subtitle = "宣讲会、双选会、招聘信息与实习信息",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { loadHome(forceRefresh = true) }) { Text("刷新") }
                        Button(onClick = { openUri(ProviderConstants.JOB_HOME_URL) }) { Text("原站") }
                    }
                },
            )
        }
        when (val state = homeState) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(state) }
            is LoadState.Data -> {
                val data = state.value.data
                val sections = data.sections
                val activeSection = sections.firstOrNull { it.type == selectedType }
                    ?: sections.firstOrNull()
                if (sections.isNotEmpty()) {
                    item {
                        EmploymentSectionTabs(
                            sections = sections,
                            selectedType = activeSection?.type ?: selectedType,
                            onSelect = { selectedType = it },
                        )
                    }
                    if (activeSection != null) {
                        item {
                            EmploymentInfoListCard(
                                section = activeSection,
                                onItemClick = ::openInfo,
                                onOpenMore = ::openUri,
                            )
                        }
                    }
                } else {
                    item {
                        InfoCard(title = "就业信息", subtitle = "暂无可展示内容") {
                            Text(
                                "暂未获取到宣讲会、双选会、招聘信息或实习信息。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    EmploymentServiceCard(data = data, onOpenUrl = ::openUri)
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
private fun EmploymentInfoListCard(
    section: EmploymentInfoSection,
    onItemClick: (EmploymentInfoSummary) -> Unit,
    onOpenMore: (String) -> Unit,
) {
    InfoCard(
        title = section.title,
        subtitle = "来自北京交通大学就业网",
        trailing = {
            OutlinedButton(onClick = { onOpenMore(section.listUrl) }) { Text("更多") }
        },
    ) {
        if (section.items.isEmpty()) {
            Text(
                "暂无${section.title}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@InfoCard
        }
        section.items.forEachIndexed { index, item ->
            EmploymentInfoRow(item = item, onClick = { onItemClick(item) })
            if (index != section.items.lastIndex) HorizontalDivider()
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onOpenUrl(data.sourceUrl) }) {
                Text("就业网")
            }
            OutlinedButton(
                onClick = {
                    onOpenUrl("${ProviderConstants.JOB_BASE_URL}/frontpage/bjtu/html/newsList.html?id=${ProviderConstants.JOB_GUIDANCE_CATEGORY_ID}")
                },
            ) {
                Text("指导贴士")
            }
        }
        EmploymentArticlePreview(data.articles)
    }
}

@Composable
private fun EmploymentArticlePreview(articles: List<EmploymentArticleSummary>) {
    val previews = articles.take(3)
    if (previews.isEmpty()) return
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    previews.forEach { article ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                article.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
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
                trailing = {
                    OutlinedButton(onClick = onBack) { Text("返回") }
                },
            )
        }
        when (state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(state) }
            is LoadState.Data -> {
                val detail = state.value.data
                item {
                    EmploymentDetailHeader(detail = detail, onOpenUrl = onOpenUrl)
                }
                if (detail.positions.isNotEmpty()) {
                    item {
                        EmploymentPositionsCard(detail.type, detail.positions)
                    }
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
                                    Text(
                                        attachment.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
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
        trailing = {
            OutlinedButton(onClick = { onOpenUrl(detail.url) }) { Text("原文") }
        },
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
        trailing = {
            company.url?.let { url ->
                OutlinedButton(onClick = { onOpenUrl(url) }) { Text("单位") }
            }
        },
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
