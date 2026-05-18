package cn.edu.bjtu.mis.widget

import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.TimetableData
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableWidgetMapperTest {
    private val monday = LocalDate.of(2026, 5, 18)

    @Test
    fun mapsTodayAndTomorrowIntoSeparateColumns() {
        val model = TimetableWidgetMapper.map(
            data = TimetableData(
                currentTerm = "2025-2026 第二学期",
                entries = listOf(
                    course("Linux操作系统与网络编程", "周一", "第3节", "14:10 - 16:00"),
                    course("勤工助学", "周二", "第4节", "16:20 - 18:10"),
                ),
            ),
            currentWeek = 12,
            today = monday,
        )

        assertEquals("今天", model.today.label)
        assertEquals("5.18", model.today.dateLabel)
        assertEquals("周一", model.today.weekdayLabel)
        assertEquals(listOf("Linux操作系统与网络编程"), model.today.courses.map { it.title })
        assertEquals("明天", model.tomorrow.label)
        assertEquals("5.19", model.tomorrow.dateLabel)
        assertEquals("周二", model.tomorrow.weekdayLabel)
        assertEquals(listOf("勤工助学"), model.tomorrow.courses.map { it.title })
    }

    @Test
    fun filtersCoursesByCurrentWeekIncludingRangesAndOddEvenWeeks() {
        val model = TimetableWidgetMapper.map(
            data = TimetableData(
                entries = listOf(
                    course("范围内", "周一", "第1节", weeks = "第1-16周"),
                    course("范围外", "周一", "第2节", weeks = "第1-10周"),
                    course("单周课", "周一", "第3节", weeks = "第1-21周（单周）"),
                    course("双周课", "周一", "第4节", weeks = "第1-21周（双周）"),
                    course("无周次", "周一", "第5节", weeks = null),
                ),
            ),
            currentWeek = 12,
            today = monday,
        )

        assertEquals(listOf("范围内", "双周课", "无周次"), model.today.courses.map { it.title })
        assertEquals(3, model.today.totalCount)
    }

    @Test
    fun includesUserCreatedCoursesWhenEntriesAreMergedByCaller() {
        val model = TimetableWidgetMapper.map(
            data = TimetableData(
                entries = listOf(
                    course("平台课程", "周一", "第1节"),
                    course(
                        name = "自定义实验",
                        weekday = "周一",
                        period = "第2节",
                        isUserCreated = true,
                        location = "逸夫教学楼",
                    ),
                ),
            ),
            currentWeek = null,
            today = monday,
        )

        assertEquals(listOf("平台课程", "自定义实验"), model.today.courses.map { it.title })
        assertTrue(model.today.courses[1].detail.contains("逸夫教学楼"))
    }

    @Test
    fun emptyModelRepresentsMissingTimetableCache() {
        val model = TimetableWidgetMapper.empty(today = monday)

        assertFalse(model.hasTimetableCache)
        assertEquals("暂无课表缓存", model.meta)
        assertTrue(model.today.courses.isEmpty())
        assertTrue(model.tomorrow.courses.isEmpty())
    }

    @Test
    fun sortsByPeriodAndLimitsEachDayToThreeCourses() {
        val model = TimetableWidgetMapper.map(
            data = TimetableData(
                entries = listOf(
                    course("第三节", "周一", "第3节"),
                    course("第一节", "周一", "第1节"),
                    course("第二节", "周一", "第2节"),
                    course("第四节", "周一", "第4节"),
                ),
            ),
            currentWeek = null,
            today = monday,
        )

        assertEquals(4, model.today.totalCount)
        assertEquals(listOf("第一节", "第二节", "第三节"), model.today.courses.map { it.title })
    }

    private fun course(
        name: String,
        weekday: String,
        period: String,
        timeRange: String? = "08:00 - 09:40",
        weeks: String? = "第1-21周",
        isUserCreated: Boolean = false,
        location: String = "海淀西校区",
    ): CourseEntry =
        CourseEntry(
            weekday = weekday,
            period = period,
            timeRange = timeRange,
            courseCode = name,
            courseName = name,
            weeks = weeks,
            locationText = location,
            isUserCreated = isUserCreated,
        )
}
