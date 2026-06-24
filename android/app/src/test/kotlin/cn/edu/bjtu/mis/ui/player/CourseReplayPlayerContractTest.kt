package cn.edu.bjtu.mis.ui.player

import cn.edu.bjtu.mis.model.CourseReplayPlaybackInfo
import cn.edu.bjtu.mis.model.CourseReplayStreamChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class CourseReplayPlayerContractTest {
    @Test
    fun sessionRoundTripPreservesPlaybackAndState() {
        val playback = CourseReplayPlaybackInfo(
            courseSchedId = "lesson-1",
            timeTableId = "table-1",
            courseId = 42,
            listenUserId = "listen-user",
            streams = listOf(
                CourseReplayStreamChoice(kind = "screen", label = "Screen", hlsUrl = "https://example.test/screen.m3u8"),
                CourseReplayStreamChoice(kind = "teacher", label = "Teacher", hlsUrl = "https://example.test/teacher.m3u8"),
            ),
            rpStatus = "ready",
            referer = "https://example.test/referer",
        )
        val session = CourseReplayPlayerSession(
            playback = playback,
            title = "Course replay",
            subtitle = "Lesson 1",
            selectedStreamKind = "teacher",
            positionMs = 12_345L,
            playWhenReady = false,
            playbackSpeed = 1.25f,
            volume = 0.4f,
            resizeMode = CourseReplayResizeMode.Crop,
        )

        val decoded = CourseReplayPlayerContract.decodeSession(
            CourseReplayPlayerContract.encodeSession(session),
        )

        assertEquals(session, decoded)
    }

    @Test
    fun resultRoundTripPreservesSessionState() {
        val result = CourseReplayPlayerResult(
            selectedStreamKind = "screen",
            positionMs = 67_890L,
            playWhenReady = true,
            playbackSpeed = 1.5f,
            volume = 0.75f,
            resizeMode = CourseReplayResizeMode.Zoom,
        )

        val decoded = CourseReplayPlayerContract.decodeResult(
            CourseReplayPlayerContract.encodeResult(result),
        )

        assertEquals(result, decoded)
    }

    @Test
    fun handoffConsumesResultOnce() {
        val result = CourseReplayPlayerResult(
            selectedStreamKind = "screen",
            positionMs = 12_000L,
            playWhenReady = true,
            playbackSpeed = 1.25f,
            volume = 0.8f,
            resizeMode = CourseReplayResizeMode.Crop,
        )

        CourseReplayPlayerHandoff.put("lesson-1", result)

        assertEquals(result, CourseReplayPlayerHandoff.consume("lesson-1"))
        assertEquals(null, CourseReplayPlayerHandoff.consume("lesson-1"))
    }
}
