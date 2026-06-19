package cn.edu.bjtu.mis.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.provider.ProviderConstants
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.data.repository.ZhixingRepository
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ZhixingAttachment
import cn.edu.bjtu.mis.model.ZhixingAuthState
import cn.edu.bjtu.mis.model.ZhixingContentBlock
import cn.edu.bjtu.mis.model.ZhixingContentBlockType
import cn.edu.bjtu.mis.model.ZhixingForumEntry
import cn.edu.bjtu.mis.model.ZhixingHomeData
import cn.edu.bjtu.mis.model.ZhixingLoginChallenge
import cn.edu.bjtu.mis.model.ZhixingLoginOutcome
import cn.edu.bjtu.mis.model.ZhixingLoginStatus
import cn.edu.bjtu.mis.model.ZhixingPostSummary
import cn.edu.bjtu.mis.model.ZhixingRankItem
import cn.edu.bjtu.mis.model.ZhixingSearchData
import cn.edu.bjtu.mis.model.ZhixingSearchResult
import cn.edu.bjtu.mis.model.ZhixingThreadDetail
import cn.edu.bjtu.mis.model.ZhixingThreadPost
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.SectionTitle
import kotlinx.coroutines.launch

@Composable
fun ZhixingScreen(
    repository: ZhixingRepository,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var authState by remember { mutableStateOf<LoadState<ZhixingAuthState>>(LoadState.Loading) }
    var homeState by remember { mutableStateOf<LoadState<ModuleEnvelope<ZhixingHomeData>>>(LoadState.Loading) }
    var showLogin by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchState by remember { mutableStateOf<LoadState<ModuleEnvelope<ZhixingSearchData>>?>(null) }
    var selectedThread by remember { mutableStateOf<ZhixingFeedItem?>(null) }
    val threadDetails = remember { mutableStateMapOf<String, LoadState<ModuleEnvelope<ZhixingThreadDetail>>>() }

    fun openUri(url: String) {
        runCatching { uriHandler.openUri(url) }
    }

    fun loadHome(
        forceRefresh: Boolean = false,
        strategy: ModuleLoadStrategy = if (forceRefresh) ModuleLoadStrategy.NetworkFirst else ModuleLoadStrategy.CacheFirst,
    ) {
        scope.launch {
            homeState = LoadState.Loading
            runCatching { repository.home(forceRefresh = forceRefresh, strategy = strategy) }
                .onSuccess {
                    authState = LoadState.Data(it.data.authState)
                    homeState = LoadState.Data(it)
                }
                .onFailure { homeState = LoadState.Error(it.message ?: "加载知行失败") }
        }
    }

    fun refreshAuthAndHome(strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst) {
        scope.launch {
            authState = LoadState.Loading
            homeState = LoadState.Loading
            runCatching { repository.authState(strategy) }
                .onSuccess { state ->
                    authState = LoadState.Data(state)
                    if (state.loggedIn) {
                        loadHome(
                            forceRefresh = strategy == ModuleLoadStrategy.NetworkFirst,
                            strategy = strategy,
                        )
                    }
                }
                .onFailure { authState = LoadState.Error(it.message ?: "知行登录状态校验失败") }
        }
    }

    fun loadThread(item: ZhixingFeedItem, forceRefresh: Boolean = false) {
        if (!forceRefresh && threadDetails[item.threadId] != null) return
        threadDetails[item.threadId] = LoadState.Loading
        scope.launch {
            runCatching { repository.thread(item.threadId, url = item.url) }
                .onSuccess { threadDetails[item.threadId] = LoadState.Data(it) }
                .onFailure { threadDetails[item.threadId] = LoadState.Error(it.message ?: "加载帖子失败") }
        }
    }

    fun search() {
        val keyword = searchQuery.trim()
        if (keyword.isBlank()) return
        scope.launch {
            searchState = LoadState.Loading
            runCatching { repository.search(keyword) }
                .onSuccess { searchState = LoadState.Data(it) }
                .onFailure { searchState = LoadState.Error(it.message ?: "搜索知行失败") }
        }
    }

    LaunchedEffect(Unit) {
        refreshAuthAndHome(initialLoadStrategy)
    }

    BackHandler(enabled = selectedThread != null) {
        selectedThread = null
    }

    if (showLogin) {
        ZhixingLoginDialog(
            onDismiss = { showLogin = false },
            onLogin = { username, password -> repository.login(username, password) },
            onSubmitCaptcha = { challengeId, answer -> repository.submitLoginCaptcha(challengeId, answer) },
            onSuccess = {
                authState = LoadState.Data(it)
                showLogin = false
                searchState = null
                threadDetails.clear()
                loadHome(forceRefresh = true)
            },
        )
    }

    val currentSelection = selectedThread
    if (currentSelection != null) {
        ZhixingThreadDetailScreen(
            item = currentSelection,
            state = threadDetails[currentSelection.threadId] ?: LoadState.Loading,
            repository = repository,
            onBack = { selectedThread = null },
            onReload = { loadThread(currentSelection, forceRefresh = true) },
            onOpenOriginal = { openUri(currentSelection.url) },
            onLogin = { showLogin = true },
        )
        return
    }

    when (val auth = authState) {
        LoadState.Loading -> ZhixingStatusScaffold { LoadingOrError(LoadState.Loading) }
        is LoadState.Error -> ZhixingStatusScaffold {
            InfoCard("需要登录知行", subtitle = auth.message) {
                Button(onClick = { showLogin = true }) { Text("登录") }
            }
        }
        is LoadState.Data -> {
            if (!auth.value.loggedIn) {
                ZhixingLoginGate(
                    authState = auth.value,
                    onLogin = { showLogin = true },
                    onOpenOriginal = { openUri(ProviderConstants.ZHIXING_BASE_URL) },
                )
            } else {
                ZhixingFeedScreen(
                    authState = auth.value,
                    homeState = homeState,
                    searchQuery = searchQuery,
                    searchState = searchState,
                    detailStates = threadDetails,
                    repository = repository,
                    onSearchQueryChange = { searchQuery = it },
                    onSearch = ::search,
                    onClearSearch = { searchState = null },
                    onRefresh = {
                        threadDetails.clear()
                        loadHome(forceRefresh = true)
                    },
                    onLogout = {
                        scope.launch {
                            runCatching { repository.logout() }
                            authState = LoadState.Data(ZhixingAuthState(loggedIn = false, message = "未登录"))
                            searchState = null
                            threadDetails.clear()
                            selectedThread = null
                        }
                    },
                    onOpenOriginal = { openUri(ProviderConstants.ZHIXING_BASE_URL) },
                    onOpenForum = ::openUri,
                    onVisibleThread = { loadThread(it) },
                    onOpenThread = {
                        selectedThread = it
                        loadThread(it)
                    },
                )
            }
        }
    }
}

@Composable
private fun ZhixingStatusScaffold(content: @Composable () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item {
            SectionTitle(
                title = "知行",
                subtitle = "知行信息交流平台",
            )
        }
        item { content() }
    }
}

@Composable
private fun ZhixingLoginGate(
    authState: ZhixingAuthState,
    onLogin: () -> Unit,
    onOpenOriginal: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item {
            SectionTitle(
                title = "知行",
                subtitle = "登录后浏览论坛内容",
                trailing = { AssistChip(onClick = onOpenOriginal, label = { Text("原站") }) },
            )
        }
        item {
            InfoCard("需要登录知行", subtitle = authState.message ?: "未登录") {
                Text(
                    "请先登录知行账号，登录成功后会加载帖子、图片和回复楼层。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onLogin) { Text("登录知行") }
            }
        }
    }
}

@Composable
private fun ZhixingFeedScreen(
    authState: ZhixingAuthState,
    homeState: LoadState<ModuleEnvelope<ZhixingHomeData>>,
    searchQuery: String,
    searchState: LoadState<ModuleEnvelope<ZhixingSearchData>>?,
    detailStates: Map<String, LoadState<ModuleEnvelope<ZhixingThreadDetail>>>,
    repository: ZhixingRepository,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onOpenOriginal: () -> Unit,
    onOpenForum: (String) -> Unit,
    onVisibleThread: (ZhixingFeedItem) -> Unit,
    onOpenThread: (ZhixingFeedItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item {
            SectionTitle(
                title = "知行",
                subtitle = authState.username ?: "知行信息交流平台",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRefresh) { Text("刷新") }
                        OutlinedButton(onClick = onLogout) { Text("退出") }
                    }
                },
            )
        }
        item {
            ZhixingSearchCard(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onSearch = onSearch,
                onClearSearch = onClearSearch,
                hasSearch = searchState != null,
            )
        }
        searchState?.let { state ->
            when (state) {
                LoadState.Loading, is LoadState.Error -> item { LoadingOrError(state) }
                is LoadState.Data -> {
                    val results = state.value.data.results.map { it.toFeedItem() }
                    item {
                        SectionTitle(
                            title = "搜索结果",
                            subtitle = state.value.data.keyword,
                        )
                    }
                    if (results.isEmpty()) {
                        item {
                            InfoCard("没有匹配的帖子") {}
                        }
                    } else {
                        items(results, key = { "search-${it.threadId}" }) { item ->
                            ZhixingFeedCard(
                                item = item,
                                state = detailStates[item.threadId],
                                repository = repository,
                                onVisible = { onVisibleThread(item) },
                                onClick = { onOpenThread(item) },
                            )
                        }
                    }
                }
            }
        }
        when (homeState) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(homeState) }
            is LoadState.Data -> {
                val data = homeState.value.data
                val feedItems = buildFeedItems(data)
                item {
                    SectionTitle(
                        title = "帖子流",
                        subtitle = "最新与排行去重合并",
                        trailing = { AssistChip(onClick = onOpenOriginal, label = { Text("原站") }) },
                    )
                }
                if (feedItems.isEmpty()) {
                    item { InfoCard("暂无帖子") {} }
                } else {
                    items(feedItems, key = { it.threadId }) { item ->
                        ZhixingFeedCard(
                            item = item,
                            state = detailStates[item.threadId],
                            repository = repository,
                            onVisible = { onVisibleThread(item) },
                            onClick = { onOpenThread(item) },
                        )
                    }
                }
                if (data.forums.isNotEmpty()) {
                    item {
                        ZhixingForumSection(data.forums, onOpenForum)
                    }
                }
            }
        }
    }
}

@Composable
private fun ZhixingSearchCard(
    query: String,
    hasSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
) {
    InfoCard("搜索帖子", subtitle = "按标题搜索知行") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("关键词") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = query.isNotBlank(),
                onClick = onSearch,
            ) { Text("搜索") }
        }
        if (hasSearch) {
            TextButton(onClick = onClearSearch) { Text("清除搜索结果") }
        }
    }
}

@Composable
private fun ZhixingFeedCard(
    item: ZhixingFeedItem,
    state: LoadState<ModuleEnvelope<ZhixingThreadDetail>>?,
    repository: ZhixingRepository,
    onVisible: () -> Unit,
    onClick: () -> Unit,
) {
    LaunchedEffect(item.threadId) {
        onVisible()
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = zhixingCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZhixingFeedHeader(item)
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when (state) {
                null, LoadState.Loading -> Text(
                    "正文加载中...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is LoadState.Error -> Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                is LoadState.Data -> {
                    val detail = state.value.data
                    if (detail.restricted) {
                        Text(
                            detail.message ?: "当前帖子需要登录或更高权限。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        val firstPost = detail.posts.firstOrNull()
                        if (firstPost != null) {
                            val text = firstTextBlock(firstPost).ifBlank { firstPost.content }
                            if (text.isNotBlank()) {
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            firstImageBlock(firstPost)?.imageUrl?.let { imageUrl ->
                                ZhixingRemoteImage(
                                    url = imageUrl,
                                    referer = detail.canonicalUrl ?: detail.url,
                                    repository = repository,
                                    alt = firstImageBlock(firstPost)?.alt,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        ZhixingFeedStats(detail)
                    }
                }
            }
        }
    }
}

@Composable
private fun ZhixingFeedHeader(item: ZhixingFeedItem) {
    val forumName = item.forumName ?: "知行"
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                forumName.take(1),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                forumName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(item.author, item.sourceLabel, item.postedAt)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ZhixingFeedStats(detail: ZhixingThreadDetail) {
    val replies = (detail.posts.size - 1).coerceAtLeast(0)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "${detail.totalPosts.coerceAtLeast(detail.posts.size)} 楼",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "$replies 回复",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (detail.attachments.isEmpty()) "无附件" else "${detail.attachments.size} 附件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ZhixingThreadDetailScreen(
    item: ZhixingFeedItem,
    state: LoadState<ModuleEnvelope<ZhixingThreadDetail>>,
    repository: ZhixingRepository,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onOpenOriginal: () -> Unit,
    onLogin: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReload) { Text("刷新") }
                    OutlinedButton(onClick = onOpenOriginal) { Text("原站") }
                }
            }
        }
        when (state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(state) }
            is LoadState.Data -> {
                val detail = state.value.data
                item {
                    ZhixingThreadHeader(detail = detail, fallback = item)
                }
                if (detail.restricted) {
                    item {
                        InfoCard("需要登录", subtitle = detail.message ?: "当前帖子需要登录或更高权限。") {
                            Button(onClick = onLogin) { Text("登录知行") }
                        }
                    }
                } else {
                    val firstPost = detail.posts.firstOrNull()
                    if (firstPost != null) {
                        item {
                            ZhixingPostCard(
                                title = "正文",
                                post = firstPost,
                                detail = detail,
                                repository = repository,
                                showTitle = false,
                            )
                        }
                    }
                    if (detail.attachments.isNotEmpty()) {
                        item {
                            ZhixingAttachmentSection(detail.attachments)
                        }
                    }
                    val replies = detail.posts.drop(1)
                    item {
                        SectionTitle(
                            title = "评论与回复",
                            subtitle = "${replies.size} 条",
                        )
                    }
                    if (replies.isEmpty()) {
                        item { InfoCard("暂无回复") {} }
                    } else {
                        items(replies, key = { post -> "${post.floor}-${post.postedAt}-${post.content.hashCode()}" }) { post ->
                            ZhixingPostCard(
                                title = post.floor ?: "回复",
                                post = post,
                                detail = detail,
                                repository = repository,
                                showTitle = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZhixingThreadHeader(detail: ZhixingThreadDetail, fallback: ZhixingFeedItem) {
    InfoCard(
        title = detail.title.ifBlank { fallback.title },
        subtitle = listOfNotNull(
            detail.forumName ?: fallback.forumName,
            detail.totalPosts.takeIf { it > 0 }?.let { "$it 楼" },
            detail.page.takeIf { it > 1 }?.let { "第 $it 页" },
        ).joinToString(" · "),
    ) {}
}

@Composable
private fun ZhixingPostCard(
    title: String,
    post: ZhixingThreadPost,
    detail: ZhixingThreadDetail,
    repository: ZhixingRepository,
    showTitle: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = zhixingCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showTitle) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            val meta = listOfNotNull(post.author, post.floor, post.postedAt)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ZhixingContentBlocks(
                post = post,
                referer = detail.canonicalUrl ?: detail.url,
                repository = repository,
            )
        }
    }
}

@Composable
private fun ZhixingContentBlocks(
    post: ZhixingThreadPost,
    referer: String,
    repository: ZhixingRepository,
) {
    val blocks = post.contentBlocks.ifEmpty {
        listOf(ZhixingContentBlock(type = ZhixingContentBlockType.Text, text = post.content))
    }
    blocks.forEach { block ->
        when (block.type) {
            ZhixingContentBlockType.Text -> {
                val text = block.text.orEmpty()
                if (text.isNotBlank()) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            ZhixingContentBlockType.Image -> {
                val imageUrl = block.imageUrl
                if (!imageUrl.isNullOrBlank()) {
                    ZhixingRemoteImage(
                        url = imageUrl,
                        referer = referer,
                        repository = repository,
                        alt = block.alt,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZhixingRemoteImage(
    url: String,
    referer: String,
    repository: ZhixingRepository,
    alt: String?,
    modifier: Modifier = Modifier,
) {
    var state by remember(url, referer) { mutableStateOf<LoadState<ImageBitmap>>(LoadState.Loading) }
    LaunchedEffect(url, referer) {
        state = LoadState.Loading
        runCatching {
            val bytes = if (url.startsWith("data:", ignoreCase = true)) {
                Base64.decode(url.substringAfter(",", ""), Base64.DEFAULT)
            } else {
                repository.imageBytes(url, referer)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                ?: error("图片解码失败")
        }.onSuccess {
            state = LoadState.Data(it)
        }.onFailure {
            state = LoadState.Error(it.message ?: "图片加载失败")
        }
    }

    val shape = MaterialTheme.shapes.medium
    when (val current = state) {
        LoadState.Loading -> Box(
            modifier = modifier
                .aspectRatio(4f / 3f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("图片加载中", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is LoadState.Error -> Box(
            modifier = modifier
                .aspectRatio(4f / 3f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(current.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is LoadState.Data -> {
            val image = current.value
            val aspect = if (image.height > 0) {
                (image.width.toFloat() / image.height.toFloat()).coerceIn(0.75f, 1.8f)
            } else {
                4f / 3f
            }
            Image(
                bitmap = image,
                contentDescription = alt ?: "帖子图片",
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .aspectRatio(aspect)
                    .clip(shape),
            )
        }
    }
}

@Composable
private fun ZhixingAttachmentSection(attachments: List<ZhixingAttachment>) {
    InfoCard("附件", subtitle = "${attachments.size} 个") {
        attachments.forEachIndexed { index, attachment ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    attachment.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (!attachment.size.isNullOrBlank()) {
                    Text(
                        attachment.size,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (index != attachments.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun ZhixingForumSection(forums: List<ZhixingForumEntry>, onOpenForum: (String) -> Unit) {
    InfoCard("板块入口", subtitle = "来自原站版块列表") {
        forums.take(16).forEachIndexed { index, forum ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenForum(forum.url) }
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(forum.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (!forum.description.isNullOrBlank()) {
                    Text(
                        forum.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (index != forums.take(16).lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun ZhixingLoginDialog(
    onDismiss: () -> Unit,
    onLogin: suspend (String, String) -> ZhixingLoginOutcome,
    onSubmitCaptcha: suspend (String, String) -> ZhixingLoginOutcome,
    onSuccess: (ZhixingAuthState) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf<ZhixingLoginChallenge?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun applyOutcome(outcome: ZhixingLoginOutcome) {
        when (outcome.status) {
            ZhixingLoginStatus.Success -> {
                val state = outcome.authState ?: ZhixingAuthState(loggedIn = true, username = username.trim(), message = outcome.message)
                onSuccess(state)
            }
            ZhixingLoginStatus.CaptchaRequired -> {
                challenge = outcome.challenge
                captcha = ""
                error = outcome.message ?: outcome.challenge?.message ?: "请输入验证码后继续登录"
            }
            ZhixingLoginStatus.Failure -> {
                val suffix = outcome.remainingAttempts?.let { "，还可尝试 $it 次" }.orEmpty()
                error = (outcome.message ?: "登录失败") + suffix
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("登录知行") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        challenge = null
                    },
                    label = { Text("账号") },
                    singleLine = true,
                    enabled = challenge == null && !busy,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        challenge = null
                    },
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = challenge == null && !busy,
                    visualTransformation = PasswordVisualTransformation(),
                )
                val currentChallenge = challenge
                if (currentChallenge != null) {
                    ZhixingCaptchaImage(currentChallenge.imageDataUrl)
                    OutlinedTextField(
                        value = captcha,
                        onValueChange = { captcha = it },
                        label = { Text("验证码") },
                        singleLine = true,
                        enabled = !busy,
                    )
                }
                if (!error.isNullOrBlank()) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && username.isNotBlank() && password.isNotBlank() && (challenge == null || captcha.isNotBlank()),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val outcome = challenge?.let {
                                onSubmitCaptcha(it.challengeId, captcha)
                            } ?: onLogin(username, password)
                            applyOutcome(outcome)
                        } catch (e: Throwable) {
                            error = e.message ?: "登录失败"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "提交中" else if (challenge == null) "登录" else "提交验证码") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ZhixingCaptchaImage(dataUrl: String) {
    val bitmap = remember(dataUrl) {
        runCatching {
            val base64 = dataUrl.substringAfter(",", "")
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "知行验证码",
            modifier = Modifier.height(72.dp),
        )
    }
}

private data class ZhixingFeedItem(
    val threadId: String,
    val title: String,
    val url: String,
    val forumName: String?,
    val author: String?,
    val postedAt: String?,
    val sourceLabel: String?,
)

private fun buildFeedItems(data: ZhixingHomeData): List<ZhixingFeedItem> {
    val items = linkedMapOf<String, ZhixingFeedItem>()
    data.latestPosts.forEach { post ->
        items.putIfAbsent(post.threadId, post.toFeedItem(sourceLabel = "最新"))
    }
    data.rankItems.forEach { item ->
        val existing = items[item.threadId]
        if (existing == null) {
            items[item.threadId] = item.toFeedItem()
        } else {
            items[item.threadId] = existing.copy(sourceLabel = listOfNotNull(existing.sourceLabel, item.rankLabel).joinToString(" · "))
        }
    }
    return items.values.toList()
}

private fun ZhixingPostSummary.toFeedItem(sourceLabel: String): ZhixingFeedItem =
    ZhixingFeedItem(
        threadId = threadId,
        title = title,
        url = url,
        forumName = forumName,
        author = author,
        postedAt = null,
        sourceLabel = sourceLabel,
    )

private fun ZhixingRankItem.toFeedItem(): ZhixingFeedItem =
    ZhixingFeedItem(
        threadId = threadId,
        title = title,
        url = url,
        forumName = forumName,
        author = null,
        postedAt = null,
        sourceLabel = rankLabel ?: "排行",
    )

private fun ZhixingSearchResult.toFeedItem(): ZhixingFeedItem =
    ZhixingFeedItem(
        threadId = threadId,
        title = title,
        url = url,
        forumName = forumName,
        author = author,
        postedAt = postedAt,
        sourceLabel = "搜索",
    )

private fun firstTextBlock(post: ZhixingThreadPost): String =
    post.contentBlocks.firstOrNull { it.type == ZhixingContentBlockType.Text }?.text.orEmpty()

private fun firstImageBlock(post: ZhixingThreadPost): ZhixingContentBlock? =
    post.contentBlocks.firstOrNull { it.type == ZhixingContentBlockType.Image && !it.imageUrl.isNullOrBlank() }

@Composable
private fun zhixingCardBorder(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
