package cn.edu.bjtu.mis.ui.player

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cn.edu.bjtu.mis.BjtuMisApplication
import cn.edu.bjtu.mis.ui.theme.AppThemeOption
import cn.edu.bjtu.mis.ui.theme.BjtuMisTheme
import kotlin.math.roundToInt

class CourseReplayPlayerActivity : AppCompatActivity() {
    private var latestResult: CourseReplayPlayerResult? = null
    private var courseSchedId: String? = null
    private var playbackActive: Boolean = false
    private var videoAspectRatio: Float = 16f / 9f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLandscapeWhenFullscreen()
        applyImmersiveWindow()

        val session = CourseReplayPlayerContract.parseSession(intent)
        if (session == null || session.playback.streams.isEmpty()) {
            finish()
            return
        }
        courseSchedId = session.playback.courseSchedId
        latestResult = CourseReplayPlayerResult(
            selectedStreamKind = session.selectedStreamKind ?: session.playback.streams.first().kind,
            positionMs = session.positionMs,
            playWhenReady = session.playWhenReady,
            playbackSpeed = session.playbackSpeed,
            volume = session.volume,
            resizeMode = session.resizeMode,
        )
        playbackActive = session.playWhenReady

        val app = application as BjtuMisApplication
        setContent {
            var themeOption by remember { mutableStateOf(AppThemeOption.Default) }
            LaunchedEffect(app.container.themeStore) {
                app.container.themeStore.theme.collect { themeOption = it }
            }
            BjtuMisTheme(themeOption = themeOption) {
                BackHandler { finishWithLatestResult() }
                CourseReplayNativePlayer(
                    playback = session.playback,
                    title = session.title,
                    subtitle = session.subtitle,
                    initialState = CourseReplayPlayerResult(
                        selectedStreamKind = session.selectedStreamKind
                            ?: session.playback.streams.first().kind,
                        positionMs = session.positionMs,
                        playWhenReady = session.playWhenReady,
                        playbackSpeed = session.playbackSpeed,
                        volume = session.volume,
                        resizeMode = session.resizeMode,
                    ),
                    mode = CourseReplayPlayerMode.Dedicated,
                    repository = app.container.courseReplayRepository,
                    okHttpClient = app.container.httpClient.client,
                    onExit = { finishWithLatestResult() },
                    onStateChanged = { latestResult = it },
                    onPlaybackMetadataChanged = { isPlaying, aspectRatio ->
                        playbackActive = isPlaying
                        videoAspectRatio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: videoAspectRatio
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestLandscapeWhenFullscreen()
        applyImmersiveWindow()
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackActive && !isWindowedMode()) {
            enterCourseReplayPictureInPicture()
        } else {
            super.onUserLeaveHint()
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        requestLandscapeWhenFullscreen()
        applyImmersiveWindow()
    }

    override fun finish() {
        latestResult?.let {
            courseSchedId?.let { id -> CourseReplayPlayerHandoff.put(id, it) }
            setResult(RESULT_OK, CourseReplayPlayerContract.resultIntent(it))
        }
        super.finish()
    }

    private fun finishWithLatestResult() {
        finish()
    }

    private fun applyImmersiveWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun requestLandscapeWhenFullscreen() {
        if (isWindowedMode()) return
        runCatching {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun isWindowedMode(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode

    private fun enterCourseReplayPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val width = (videoAspectRatio * 1000).roundToInt().coerceAtLeast(1)
        val height = 1000
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }
}
