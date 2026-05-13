package cn.edu.bjtu.mis.data.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class VeProviderTest {
    @Test
    fun courseReplayUserIdCandidatesPreferFreshContextId() {
        val candidates = courseReplayUserIdCandidates(
            contextUserId = " fresh-user ",
            preferredUserId = " stale-user ",
            listenUserId = " listen-user ",
        )

        assertEquals(listOf("fresh-user", "stale-user", "listen-user"), candidates)
    }

    @Test
    fun courseReplayUserIdCandidatesTrimAndDeduplicate() {
        val candidates = courseReplayUserIdCandidates(
            contextUserId = " 1001 ",
            preferredUserId = "1001",
            listenUserId = " ",
        )

        assertEquals(listOf("1001"), candidates)
    }
}
