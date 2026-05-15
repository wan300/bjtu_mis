package cn.edu.bjtu.mis.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
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
import java.util.Locale

private const val ReplaySeekStepMs = 10_000L
private val ReplayPlaybackSpeeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

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
                    videoId = lesson.videoId,
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
    var isFullscreen by remember(stream.hlsUrl) { mutableStateOf(false) }
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

    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    if (isFullscreen) {
        CourseReplayFullscreenDialog(
            player = player,
            onExitFullscreen = { isFullscreen = false },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16 / 9f)
                .heightIn(min = 180.dp),
            contentAlignment = Alignment.Center,
        ) {
            CourseReplayVideoSurface(
                videoPlayer = if (isFullscreen) null else player,
                controlsPlayer = player,
                isFullscreen = false,
                onFullscreenChange = { isFullscreen = it },
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

@Composable
private fun CourseReplayFullscreenDialog(
    player: ExoPlayer,
    onExitFullscreen: () -> Unit,
) {
    Dialog(
        onDismissRequest = onExitFullscreen,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        FullscreenSystemUiEffect()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            CourseReplayVideoSurface(
                videoPlayer = player,
                controlsPlayer = player,
                isFullscreen = true,
                onFullscreenChange = { fullscreen ->
                    if (!fullscreen) onExitFullscreen()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CourseReplayVideoSurface(
    videoPlayer: ExoPlayer?,
    controlsPlayer: ExoPlayer,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember(controlsPlayer) { mutableStateOf(controlsPlayer.isPlaying) }
    var durationMs by remember(controlsPlayer) { mutableStateOf(controlsPlayer.normalizedDurationMs()) }
    var positionMs by remember(controlsPlayer) { mutableStateOf(controlsPlayer.currentPosition.coerceAtLeast(0L)) }
    var playbackSpeed by remember(controlsPlayer) { mutableStateOf(controlsPlayer.playbackParameters.speed) }
    var volume by remember(controlsPlayer) { mutableStateOf(controlsPlayer.volume.coerceIn(0f, 1f)) }
    var previousVolume by remember(controlsPlayer) {
        mutableStateOf(controlsPlayer.volume.takeIf { it > 0f } ?: 1f)
    }

    LaunchedEffect(controlsPlayer) {
        while (true) {
            isPlaying = controlsPlayer.isPlaying
            durationMs = controlsPlayer.normalizedDurationMs()
            positionMs = controlsPlayer.currentPosition.coerceAtLeast(0L)
            playbackSpeed = controlsPlayer.playbackParameters.speed
            volume = controlsPlayer.volume.coerceIn(0f, 1f)
            delay(500L)
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setBackgroundColor(AndroidColor.BLACK)
                    player = videoPlayer
                }
            },
            update = {
                it.useController = false
                it.keepScreenOn = videoPlayer != null && controlsPlayer.isPlaying
                it.player = videoPlayer
            },
            modifier = Modifier.fillMaxSize(),
        )
        CourseReplayPlayerControls(
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            playbackSpeed = playbackSpeed,
            volume = volume,
            isFullscreen = isFullscreen,
            onPlayPause = {
                if (controlsPlayer.isPlaying) {
                    controlsPlayer.pause()
                } else {
                    controlsPlayer.play()
                }
            },
            onSeek = { controlsPlayer.seekToBounded(it) },
            onSeekBy = { controlsPlayer.seekByBounded(it) },
            onSpeedChange = { speed ->
                playbackSpeed = speed
                controlsPlayer.setPlaybackSpeed(speed)
            },
            onVolumeChange = { nextVolume ->
                val bounded = nextVolume.coerceIn(0f, 1f)
                volume = bounded
                controlsPlayer.volume = bounded
                if (bounded > 0f) previousVolume = bounded
            },
            onToggleMute = {
                if (controlsPlayer.volume > 0f) {
                    previousVolume = controlsPlayer.volume
                    controlsPlayer.volume = 0f
                    volume = 0f
                } else {
                    val restored = previousVolume.takeIf { it > 0f } ?: 1f
                    controlsPlayer.volume = restored
                    volume = restored
                }
            },
            onFullscreenChange = onFullscreenChange,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun CourseReplayPlayerControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    volume: Float,
    isFullscreen: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember(durationMs) { mutableStateOf(positionMs.coerceAtMost(durationMs).toFloat()) }
    val safeDuration = durationMs.coerceAtLeast(0L)
    val displayedPosition = if (isSeeking) seekPosition.toLong() else positionMs.coerceAtMost(safeDuration)

    LaunchedEffect(positionMs, isSeeking, safeDuration) {
        if (!isSeeking) {
            seekPosition = positionMs.coerceIn(0L, safeDuration.coerceAtLeast(positionMs)).toFloat()
        }
    }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = if (isFullscreen) 0.72f else 0.64f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatPlaybackTime(displayedPosition),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = if (safeDuration > 0L) formatPlaybackTime(safeDuration) else "--:--",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (safeDuration > 0L) {
            Slider(
                value = displayedPosition.coerceIn(0L, safeDuration).toFloat(),
                onValueChange = {
                    isSeeking = true
                    seekPosition = it
                },
                onValueChangeFinished = {
                    isSeeking = false
                    onSeek(seekPosition.toLong())
                },
                valueRange = 0f..safeDuration.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = { onSeekBy(-ReplaySeekStepMs) }) {
                Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", tint = Color.White)
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                )
            }
            IconButton(onClick = { onSeekBy(ReplaySeekStepMs) }) {
                Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White)
            }
            PlaybackSpeedMenu(
                currentSpeed = playbackSpeed,
                onSpeedChange = onSpeedChange,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onToggleMute) {
                Icon(
                    imageVector = if (volume <= 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (volume <= 0f) "Unmute" else "Mute",
                    tint = Color.White,
                )
            }
            Slider(
                value = volume.coerceIn(0f, 1f),
                onValueChange = onVolumeChange,
                valueRange = 0f..1f,
                modifier = Modifier.width(if (isFullscreen) 180.dp else 104.dp),
            )
            IconButton(onClick = { onFullscreenChange(!isFullscreen) }) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun PlaybackSpeedMenu(
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.Speed,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(formatPlaybackSpeed(currentSpeed), color = Color.White)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ReplayPlaybackSpeeds.forEach { speed ->
                DropdownMenuItem(
                    text = { Text(formatPlaybackSpeed(speed)) },
                    onClick = {
                        expanded = false
                        onSpeedChange(speed)
                    },
                )
            }
        }
    }
}

@Composable
private fun FullscreenSystemUiEffect() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        val window = activity?.window
        val originalOrientation = activity?.requestedOrientation
        if (activity != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (activity != null && originalOrientation != null) {
                activity.requestedOrientation = originalOrientation
            }
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

private fun ExoPlayer.normalizedDurationMs(): Long {
    val value = duration
    return if (value == C.TIME_UNSET || value < 0L) 0L else value
}

private fun ExoPlayer.seekToBounded(positionMs: Long) {
    val durationMs = normalizedDurationMs()
    val target = if (durationMs > 0L) {
        positionMs.coerceIn(0L, durationMs)
    } else {
        positionMs.coerceAtLeast(0L)
    }
    seekTo(target)
}

private fun ExoPlayer.seekByBounded(deltaMs: Long) {
    seekToBounded(currentPosition + deltaMs)
}

private fun formatPlaybackSpeed(speed: Float): String {
    val value = if (speed == speed.toInt().toFloat()) {
        String.format(Locale.US, "%.1f", speed)
    } else {
        String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    }
    return "${value}x"
}

private fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openUrl(context: android.content.Context, url: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    return runCatching { context.startActivity(Intent.createChooser(intent, "打开课程回放")) }.isSuccess
}
