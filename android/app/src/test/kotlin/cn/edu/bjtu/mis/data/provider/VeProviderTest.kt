package cn.edu.bjtu.mis.data.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class VeProviderTest {
    @Test
    fun courseReplayUserIdCandidatesPreferReplayDetailId() {
        val candidates = courseReplayUserIdCandidates(
            detailUserId = " detail-user ",
            contextUserId = " platform-user ",
            preferredUserId = " cached-user ",
            listenUserId = " listen-user ",
        )

        assertEquals(listOf("detail-user", "platform-user", "cached-user", "listen-user"), candidates)
    }

    @Test
    fun courseReplayUserIdCandidatesTrimAndDeduplicate() {
        val candidates = courseReplayUserIdCandidates(
            detailUserId = " ",
            contextUserId = " 1001 ",
            preferredUserId = "1001",
            listenUserId = "1001",
        )

        assertEquals(listOf("1001"), candidates)
    }

    @Test
    fun courseReplayDetailParamsIncludeLessonIdentity() {
        val params = courseReplayDetailParams(
            courseSchedId = " 1774916 ",
            userId = " 136786 ",
            videoId = " 9E2657F72D5E450FAB4CDF1E6725F15E ",
            timeTableId = " 559F6748116A43D399DE404B5ED28ED6 ",
        )

        assertEquals("toDisplyCourseSchedDetail", params["method"])
        assertEquals("1774916", params["courseSchedId"])
        assertEquals("136786", params["userId"])
        assertEquals("1", params["userLevel"])
        assertEquals("9E2657F72D5E450FAB4CDF1E6725F15E", params["videoId"])
        assertEquals("559F6748116A43D399DE404B5ED28ED6", params["uuid"])
        assertEquals("559F6748116A43D399DE404B5ED28ED6", params["timeTableId"])
        assertEquals("559F6748116A43D399DE404B5ED28ED6", params["timetableId"])
    }

    @Test
    fun courseReplayDetailParamsOmitBlankLessonIdentity() {
        val params = courseReplayDetailParams(
            courseSchedId = "1774916",
            userId = "136786",
            videoId = " ",
            timeTableId = "",
        )

        assertEquals(false, params.containsKey("videoId"))
        assertEquals(false, params.containsKey("uuid"))
        assertEquals(false, params.containsKey("timeTableId"))
        assertEquals(false, params.containsKey("timetableId"))
    }
}
