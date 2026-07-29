package cn.edu.bjtu.mis.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UiFormattingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 7, 28, 14, 30, 0, 0, zone)

    @Test
    fun `formats recent timestamps in local friendly text`() {
        assertEquals(
            "10 分钟前",
            friendlyTimestamp("2026-07-28T14:20:00+08:00", now, zone),
        )
        assertEquals(
            "昨天 09:05",
            friendlyTimestamp("2026-07-27T09:05:00+08:00", now, zone),
        )
    }

    @Test
    fun `preserves malformed timestamps and handles missing values`() {
        assertEquals("尚未同步", friendlyTimestamp(null, now, zone))
        assertEquals("upstream value", friendlyTimestamp("upstream value", now, zone))
    }
}
