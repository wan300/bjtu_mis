package cn.edu.bjtu.mis.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import cn.edu.bjtu.mis.data.repository.CourseReplayRepository
import cn.edu.bjtu.mis.model.CourseReplayData
import cn.edu.bjtu.mis.model.CourseReplayLesson
import cn.edu.bjtu.mis.model.CourseReplayPlaybackInfo
import cn.edu.bjtu.mis.model.CourseReplayStreamChoice
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.SectionTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseReplayScreen(
    repository: CourseReplayRepository,
    okHttpClient: OkHttpClient,
) {
    val scope = rememberCoroutineScope()
    var selectedCourseId by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<LoadState<ModuleEnvelope<CourseReplayData>>>(LoadState.Loading) }
    var selectedLesson by remember { mutableStateOf<CourseReplayLesson?>(null) }
    var playbackState by remember { mutableStateOf<LoadState<CourseReplayPlaybackInfo>?>(null) }
    var selectedStreamKind by remember { mutableStateOf<String?>(null) }

    fun load(courseId: String = selectedCourseId) {
        scope.launch {
            state = LoadState.Loading
            selectedLesson = null
            playbackState = null
            selectedStreamKind = null
            runCatching { repository.listing(courseId = courseId.ifBlank { null }) }
                .onSuccess {
                    selectedCourseId = it.data.selectedCourseId?.toString().orEmpty()
                    state = LoadState.Data(it)
                }
                .onFailure { state = LoadState.Error(it.message ?: "课程回放加载失败") }
        }
    }

    fun loadPlayback(data: CourseReplayData, lesson: CourseReplayLesson) {
        selectedLesson = lesson
        playbackState = LoadState.Loading
        selectedStreamKind = null
        scope.launch {
            runCatching {
                repository.playback(
                    term = data.currentTerm,
                    courseId = selectedCourseId.ifBlank { data.selectedCourseId?.toString() },
                    courseSchedId = lesson.courseSchedId,
                    userId = data.userId,
                    timeTableId = lesson.timeTableId,
                )
            }.onSuccess {
                selectedStreamKind = it.streams.firstOrNull()?.kind
                playbackState = LoadState.Data(it)
            }.onFailure {
                playbackState = LoadState.Error(it.message ?: "播放信息加载失败")
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            SectionTitle(
                title = "课程回放",
                subtitle = "选择课程和课次后播放课堂回放",
                trailing = { OutlinedButton(onClick = { load() }) { Text("刷新") } },
            )
        }
        item {
            val data = (state as? LoadState.Data)?.value?.data
            CourseReplaySelector(
                courses = data?.courses.orEmpty().map {
                    it.courseId.toString() to listOfNotNull(it.courseName, it.teacherName).joinToString(" · ")
                },
                value = selectedCourseId,
                onValueChange = {
                    selectedCourseId = it
                    load(it)
                },
            )
        }
        when (val current = state) {
            LoadState.Loading, is LoadState.Error -> item { LoadingOrError(current) }
            is LoadState.Data -> {
                val envelope = current.value
                val data = envelope.data
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        data.currentTerm?.takeIf { it.isNotBlank() }?.let {
                            AssistChip(onClick = {}, label = { Text(it) })
                        }
                        AssistChip(onClick = {}, label = { Text("${data.lessons.size} 个课次") })
                    }
                }
                selectedLesson?.let { lesson ->
                    item(key = "player-${lesson.courseSchedId}") {
                        CourseReplayPlaybackPanel(
                            lesson = lesson,
                            state = playbackState,
                            selectedStreamKind = selectedStreamKind,
                            onSelectStream = { selectedStreamKind = it },
                            repository = repository,
                            okHttpClient = okHttpClient,
                        )
                    }
                }
                if (data.lessons.isEmpty()) {
                    item {
                        InfoCard("暂无课程回放") {
                            Text("该课程暂未返回可选回放课次。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    items(data.lessons, key = { it.courseSchedId }) { lesson ->
                        CourseReplayLessonCard(
                            lesson = lesson,
                            selected = selectedLesson?.courseSchedId == lesson.courseSchedId,
                            onClick = { loadPlayback(data, lesson) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseReplaySelector(
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

@Composable
private fun CourseReplayLessonCard(
    lesson: CourseReplayLesson,
    selected: Boolean,
    onClick: () -> Unit,
) {
    InfoCard(
        title = lesson.courseName ?: "回放课次 ${lesson.courseSchedId}",
        subtitle = listOfNotNull(lesson.classBeginTime, lesson.teacherName, lesson.classroomName).joinToString(" · "),
        modifier = Modifier.clickable(onClick = onClick),
        trailing = {
            Button(onClick = onClick) {
                Text(if (selected) "正在查看" else "播放")
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("课程号", lesson.courseCode, Modifier.weight(1f))
            KeyValue("结束", lesson.classEndTime, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            KeyValue("课次", lesson.courseSchedId, Modifier.weight(1f))
            KeyValue("视频", if (lesson.hasVideo) "有回放" else "待确认", Modifier.weight(1f))
        }
    }
}

@Composable
private fun CourseReplayPlaybackPanel(
    lesson: CourseReplayLesson,
    state: LoadState<CourseReplayPlaybackInfo>?,
    selectedStreamKind: String?,
    onSelectStream: (String) -> Unit,
    repository: CourseReplayRepository,
    okHttpClient: OkHttpClient,
) {
    InfoCard(
        title = "播放：${lesson.courseName ?: lesson.courseSchedId}",
        subtitle = lesson.classBeginTime,
    ) {
        when (state) {
            null, LoadState.Loading -> LoadingOrError(LoadState.Loading)
            is LoadState.Error -> LoadingOrError(state)
            is LoadState.Data -> {
                val playback = state.value
                if (playback.streams.isEmpty()) {
                    Text("该课次暂无可播放回放。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    val selectedStream = playback.streams.firstOrNull { it.kind == selectedStreamKind }
                        ?: playback.streams.first()
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        playback.streams.forEach { stream ->
                            FilterChip(
                                selected = stream.kind == selectedStream.kind,
                                onClick = { onSelectStream(stream.kind) },
                                label = { Text(stream.label) },
                            )
                        }
                    }
                    CourseReplayPlayer(
                        playback = playback,
                        stream = selectedStream,
                        repository = repository,
                        okHttpClient = okHttpClient,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        KeyValue("大小", playback.rpSize?.let { "$it MB" }, Modifier.weight(1f))
                        KeyValue("状态", playback.rpStatus, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseReplayPlayer(
    playback: CourseReplayPlaybackInfo,
    stream: CourseReplayStreamChoice,
    repository: CourseReplayRepository,
    okHttpClient: OkHttpClient,
) {
    val context = LocalContext.current
    var playerError by remember(stream.hlsUrl) { mutableStateOf<String?>(null) }
    val player = remember(stream.hlsUrl, playback.referer) {
        val requestProperties = buildMap {
            playback.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        }
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(requestProperties)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(stream.hlsUrl))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerError = error.message ?: "播放器加载失败"
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, playback.courseSchedId, stream.hlsUrl) {
        val listenUserId = playback.listenUserId ?: return@LaunchedEffect
        val timetableId = playback.timeTableId ?: return@LaunchedEffect
        val courseId = playback.courseId ?: return@LaunchedEffect
        while (true) {
            delay(60_000L)
            val seconds = player.currentPosition / 1000L
            if (seconds > 0) {
                runCatching {
                    repository.reportListen(
                        userId = listenUserId,
                        timetableId = timetableId,
                        courseId = courseId,
                        listenTimeSeconds = seconds,
                    )
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16 / 9f)
                .heightIn(min = 180.dp),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = true
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }
        playerError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = { openUrl(context, stream.hlsUrl) }) {
            Text("外部打开")
        }
    }
}

private fun openUrl(context: android.content.Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    return runCatching { context.startActivity(Intent.createChooser(intent, "打开课程回放")) }.isSuccess
}
