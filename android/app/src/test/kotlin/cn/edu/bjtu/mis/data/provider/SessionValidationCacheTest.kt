package cn.edu.bjtu.mis.data.provider

import cn.edu.bjtu.mis.model.SessionState
import cn.edu.bjtu.mis.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionValidationCacheTest {
    @Test
    fun returnsReadyStatusInsideTtl() {
        var now = 1_000L
        val cache = SessionValidationCache(ttlMillis = 300_000L, nowMillis = { now })
        val status = SessionStatus(SessionState.Ready, "ok")

        cache.remember(status)
        now += 299_999L

        assertEquals(status, cache.getFresh())
    }

    @Test
    fun expiresReadyStatusAfterTtl() {
        var now = 1_000L
        val cache = SessionValidationCache(ttlMillis = 300_000L, nowMillis = { now })

        cache.remember(SessionStatus(SessionState.Ready, "ok"))
        now += 300_001L

        assertNull(cache.getFresh())
    }

    @Test
    fun nonReadyStatusClearsCache() {
        val cache = SessionValidationCache(ttlMillis = 300_000L, nowMillis = { 1_000L })

        cache.remember(SessionStatus(SessionState.Ready, "ok"))
        cache.remember(SessionStatus(SessionState.Expired, "expired"))

        assertNull(cache.getFresh())
    }
}
