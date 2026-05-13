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
}
