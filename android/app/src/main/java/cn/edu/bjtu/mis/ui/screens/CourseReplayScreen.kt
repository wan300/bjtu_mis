package cn.edu.bjtu.mis.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.edu.bjtu.mis.data.repository.CourseReplayRepository
import cn.edu.bjtu.mis.data.repository.ModuleLoadStrategy
import cn.edu.bjtu.mis.model.CourseReplayData
import cn.edu.bjtu.mis.model.CourseReplayLesson
import cn.edu.bjtu.mis.model.CourseReplayPlaybackInfo
import cn.edu.bjtu.mis.model.CourseReplayStreamChoice
import cn.edu.bjtu.mis.model.ProgressiveModuleState
import cn.edu.bjtu.mis.ui.components.InfoCard
import cn.edu.bjtu.mis.ui.components.KeyValue
import cn.edu.bjtu.mis.ui.components.LoadState
import cn.edu.bjtu.mis.ui.components.LoadingOrError
import cn.edu.bjtu.mis.ui.components.PageActionRow
import cn.edu.bjtu.mis.ui.components.ProgressiveStatus
import cn.edu.bjtu.mis.ui.player.CourseReplayNativePlayer
import cn.edu.bjtu.mis.ui.player.CourseReplayPlayerHandoff
import cn.edu.bjtu.mis.ui.player.CourseReplayPlayerContract
import cn.edu.bjtu.mis.ui.player.CourseReplayPlayerMode
import cn.edu.bjtu.mis.ui.player.CourseReplayPlayerResult
import cn.edu.bjtu.mis.ui.player.CourseReplayPlayerSession
import cn.edu.bjtu.mis.ui.player.CourseReplayResizeMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseReplayScreen(
    repository: CourseReplayRepository,
    okHttpClient: OkHttpClient,
    initialLoadStrategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
) {
    val scope = rememberCoroutineScope()
    var selectedCourseId by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf(ProgressiveModuleState<CourseReplayData>()) }
    var selectedLessonId by rememberSaveable { mutableStateOf<String?>(null) }
    var playbackState by remember { mutableStateOf<LoadState<CourseReplayPlaybackInfo>?>(null) }
    var requestedPlaybackLessonId by remember { mutableStateOf<String?>(null) }
    var selectedStreamKind by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedLesson = state.envelope?.data?.lessons?.firstOrNull { it.courseSchedId == selectedLessonId }

    fun load(
        courseId: String = selectedCourseId,
        preservePlaybackSelection: Boolean = false,
        strategy: ModuleLoadStrategy = ModuleLoadStrategy.NetworkFirst,
    ) {
        scope.launch {
            state = ProgressiveModuleState()
            if (!preservePlaybackSelection) {
                selectedLessonId = null
                playbackState = null
                requestedPlaybackLessonId = null
                selectedStreamKind = null
            }
            runCatching {
                repository.listingProgressive(courseId = courseId.ifBlank { null }, strategy = strategy)
                    .collect { next ->
                        next.envelope?.data?.let { data ->
                            selectedCourseId = data.selectedCourseId?.toString().orEmpty()
                        }
                        state = next
                    }
            }
                .onFailure {
                    state = state.copy(
                        loading = false,
                        complete = true,
                        errors = state.errors + (it.message ?: "课程回放加载失败"),
                    )
                }
        }
    }

    fun loadPlayback(
        data: CourseReplayData,
        lesson: CourseReplayLesson,
        preserveStreamSelection: Boolean = false,
    ) {
        selectedLessonId = lesson.courseSchedId
        requestedPlaybackLessonId = lesson.courseSchedId
        playbackState = LoadState.Loading
        if (!preserveStreamSelection) {
            selectedStreamKind = null
        }
        scope.launch {
            runCatching {
                repository.playback(
                    term = data.currentTerm,
                    courseId = selectedCourseId.ifBlank { data.selectedCourseId?.toString() },
                    courseSchedId = lesson.courseSchedId,
                    userId = data.userId,
                    timeTableId = lesson.timeTableId,
                    videoId = lesson.videoId,
                )
            }.onSuccess { info ->
                val restoredStreamKind = selectedStreamKind
                    ?.takeIf { kind -> info.streams.any { stream -> stream.kind == kind } }
                selectedStreamKind = restoredStreamKind ?: info.streams.firstOrNull()?.kind
                playbackState = LoadState.Data(info)
            }.onFailure {
                playbackState = LoadState.Error(it.message ?: "播放信息加载失败")
            }
        }
    }

    LaunchedEffect(Unit) {
        load(preservePlaybackSelection = selectedLessonId != null, strategy = initialLoadStrategy)
    }
    LaunchedEffect(state.envelope?.data, selectedLessonId) {
        val data = state.envelope?.data ?: return@LaunchedEffect
        val lessonId = selectedLessonId ?: return@LaunchedEffect
        val lesson = data.lessons.firstOrNull { it.courseSchedId == lessonId }
        if (lesson == null) {
            selectedLessonId = null
            playbackState = null
            requestedPlaybackLessonId = null
            selectedStreamKind = null
            return@LaunchedEffect
        }
        if (requestedPlaybackLessonId != lesson.courseSchedId) {
            loadPlayback(
                data = data,
                lesson = lesson,
                preserveStreamSelection = true,
            )
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            PageActionRow {
                OutlinedButton(onClick = { load() }) { Text("刷新") }
            }
        }
        item {
            val data = state.envelope?.data
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
        val envelope = state.envelope
        if (envelope == null) {
            item {
                LoadingOrError(
                    if (state.loading) LoadState.Loading else LoadState.Error(state.errors.joinToString("；").ifBlank { "课程回放加载失败" })
                )
            }
        } else {
                val data = envelope.data
                item { ProgressiveStatus(state) }
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
                if (data.lessons.isEmpty() && !state.loading) {
                    item {
                        InfoCard("暂无课程回放") {
                            Text("该课程暂未返回可选回放课次。", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    items(data.lessons, key = { it.courseSchedId }) { lesson ->
                        CourseReplayLessonCard(
                            lesson = lesson,
                            selected = selectedLessonId == lesson.courseSchedId,
                            onClick = { loadPlayback(data, lesson) },
                        )
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
                        streams = playback.streams,
                        selectedStream = selectedStream,
                        onSelectStream = onSelectStream,
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
    streams: List<CourseReplayStreamChoice>,
    selectedStream: CourseReplayStreamChoice,
    onSelectStream: (String) -> Unit,
    repository: CourseReplayRepository,
    okHttpClient: OkHttpClient,
) {
    val context = LocalContext.current
    val defaultState = CourseReplayPlayerResult(
        selectedStreamKind = selectedStream.kind,
        positionMs = 0L,
        playWhenReady = true,
        playbackSpeed = 1f,
        volume = 1f,
        resizeMode = CourseReplayResizeMode.Fit,
    )
    var restoreSeed by rememberSaveable(playback.courseSchedId) { mutableStateOf(0) }
    var restoreState by remember(playback.courseSchedId) { mutableStateOf(defaultState) }
    val lifecycleOwner = LocalLifecycleOwner.current

    fun applyPlayerResult(playerResult: CourseReplayPlayerResult) {
        restoreState = playerResult
        restoreSeed += 1
        onSelectStream(playerResult.selectedStreamKind)
    }

    LaunchedEffect(playback.courseSchedId) {
        CourseReplayPlayerHandoff.consume(playback.courseSchedId)?.let(::applyPlayerResult)
    }

    DisposableEffect(lifecycleOwner, playback.courseSchedId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                CourseReplayPlayerHandoff.consume(playback.courseSchedId)?.let(::applyPlayerResult)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    key(restoreSeed) {
        CourseReplayNativePlayer(
            playback = playback,
            title = "Course replay ${playback.courseSchedId}",
            subtitle = playback.rpStatus,
            initialState = restoreState.copy(
                selectedStreamKind = restoreState.selectedStreamKind
                    .takeIf { kind -> streams.any { it.kind == kind } }
                    ?: selectedStream.kind,
            ),
            requestedStreamKind = selectedStream.kind,
            mode = CourseReplayPlayerMode.Preview,
            repository = repository,
            okHttpClient = okHttpClient,
            onOpenDedicated = { snapshot ->
                val session = CourseReplayPlayerSession(
                    playback = playback,
                    title = "Course replay ${playback.courseSchedId}",
                    subtitle = playback.rpStatus,
                    selectedStreamKind = snapshot.selectedStreamKind,
                    positionMs = snapshot.positionMs,
                    playWhenReady = snapshot.playWhenReady,
                    playbackSpeed = snapshot.playbackSpeed,
                    volume = snapshot.volume,
                    resizeMode = snapshot.resizeMode,
                )
                runCatching {
                    val intent = CourseReplayPlayerContract.createIntent(context, session)
                    if (context !is android.app.Activity) {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.isSuccess
            },
            onStateChanged = { snapshot ->
                if (snapshot.selectedStreamKind != selectedStream.kind) {
                    onSelectStream(snapshot.selectedStreamKind)
                }
            },
        )
    }
}
