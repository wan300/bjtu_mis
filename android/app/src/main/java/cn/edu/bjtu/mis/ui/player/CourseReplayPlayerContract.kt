package cn.edu.bjtu.mis.ui.player

import android.content.Context
import android.content.Intent
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.model.CourseReplayPlaybackInfo
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class CourseReplayResizeMode {
    Fit,
    Crop,
    Zoom,
}

@Serializable
data class CourseReplayPlayerSession(
    val playback: CourseReplayPlaybackInfo,
    val title: String? = null,
    val subtitle: String? = null,
    val selectedStreamKind: String? = null,
    val positionMs: Long = 0L,
    val playWhenReady: Boolean = true,
    val playbackSpeed: Float = 1f,
    val volume: Float = 1f,
    val resizeMode: CourseReplayResizeMode = CourseReplayResizeMode.Fit,
)

@Serializable
data class CourseReplayPlayerResult(
    val selectedStreamKind: String,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val playbackSpeed: Float,
    val volume: Float,
    val resizeMode: CourseReplayResizeMode,
)

object CourseReplayPlayerContract {
    private const val ExtraSession = "cn.edu.bjtu.mis.extra.COURSE_REPLAY_PLAYER_SESSION"
    private const val ExtraResult = "cn.edu.bjtu.mis.extra.COURSE_REPLAY_PLAYER_RESULT"

    fun createIntent(context: Context, session: CourseReplayPlayerSession): Intent =
        Intent(context, CourseReplayPlayerActivity::class.java)
            .putExtra(ExtraSession, encodeSession(session))

    fun parseSession(intent: Intent?): CourseReplayPlayerSession? =
        intent?.getStringExtra(ExtraSession)?.let(::decodeSession)

    fun resultIntent(result: CourseReplayPlayerResult): Intent =
        Intent().putExtra(ExtraResult, encodeResult(result))

    fun parseResult(intent: Intent?): CourseReplayPlayerResult? =
        intent?.getStringExtra(ExtraResult)?.let(::decodeResult)

    fun encodeSession(session: CourseReplayPlayerSession): String =
        AppJson.encodeToString(CourseReplayPlayerSession.serializer(), session)

    fun decodeSession(value: String): CourseReplayPlayerSession =
        AppJson.decodeFromString(CourseReplayPlayerSession.serializer(), value)

    fun encodeResult(result: CourseReplayPlayerResult): String =
        AppJson.encodeToString(CourseReplayPlayerResult.serializer(), result)

    fun decodeResult(value: String): CourseReplayPlayerResult =
        AppJson.decodeFromString(CourseReplayPlayerResult.serializer(), value)
}

object CourseReplayPlayerHandoff {
    private val results = ConcurrentHashMap<String, CourseReplayPlayerResult>()

    fun put(courseSchedId: String, result: CourseReplayPlayerResult) {
        results[courseSchedId] = result
    }

    fun consume(courseSchedId: String): CourseReplayPlayerResult? =
        results.remove(courseSchedId)
}
