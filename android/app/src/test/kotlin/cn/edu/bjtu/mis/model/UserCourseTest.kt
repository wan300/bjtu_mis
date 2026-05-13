package cn.edu.bjtu.mis.model

import cn.edu.bjtu.mis.data.db.UserCourseEntity
import cn.edu.bjtu.mis.data.db.UserTodoEntity
import cn.edu.bjtu.mis.data.db.toCourseEntry
import cn.edu.bjtu.mis.data.db.toModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserCourseTest {
    @Test
    fun userCourseEntityMapsToCourseEntry() {
        val entry = UserCourseEntity(
            id = 7,
            courseName = "移动开发",
            weekday = "周三",
            weekdayIndex = 2,
            period = "Period 3",
            periodNumber = 3,
            timeRange = "12:10-14:00",
            startWeek = 2,
            endWeek = 6,
            durationType = UserCourseDurationType.LongTerm.name,
            teacher = "张老师",
            locationText = "思源东楼",
            remark = "带电脑",
            colorIndex = 3,
            createdAt = "2026-05-09T00:00:00Z",
            updatedAt = "2026-05-09T00:00:00Z",
        ).toCourseEntry()

        assertEquals(7L, entry.localId)
        assertEquals("LOCAL-7", entry.courseCode)
        assertEquals("移动开发", entry.courseName)
        assertEquals("第2-6周", entry.weeks)
        assertEquals("思源东楼", entry.locationText)
        assertTrue(entry.isUserCreated)
    }

    @Test
    fun temporaryCourseUsesSingleCurrentWeek() {
        assertEquals("第10周", formatUserCourseWeeks(10, 10))
    }

    @Test
    fun longTermCourseNormalizesWeekRange() {
        assertEquals("第3-8周", formatUserCourseWeeks(8, 3))
    }

    @Test
    fun selectedWeeksFormatSupportsPresetsAndRanges() {
        assertEquals("第1-21周", formatUserCourseWeeks((1..21).toSet()))
        assertEquals("第1-21周（单周）", formatUserCourseWeeks((1..21).filter { it % 2 == 1 }.toSet()))
        assertEquals("第1-21周（双周）", formatUserCourseWeeks((1..21).filter { it % 2 == 0 }.toSet()))
        assertEquals("第1-3、5、8-9周", formatUserCourseWeeks(setOf(1, 2, 3, 5, 8, 9)))
    }

    @Test
    fun userCourseEntityPreservesCustomWeekText() {
        val entry = UserCourseEntity(
            id = 8,
            courseName = "移动开发",
            weekday = "周三",
            weekdayIndex = 2,
            period = "Period 3",
            periodNumber = 3,
            startWeek = 1,
            endWeek = 21,
            weeksText = "第1-21周（双周）",
            durationType = UserCourseDurationType.LongTerm.name,
            createdAt = "2026-05-09T00:00:00Z",
            updatedAt = "2026-05-09T00:00:00Z",
        ).toCourseEntry()

        assertEquals("第1-21周（双周）", entry.weeks)
        assertTrue(timetableWeeksOverlap(entry.weeks, "第4周"))
        assertFalse(timetableWeeksOverlap(entry.weeks, "第5周"))
    }

    @Test
    fun userTodoEntityMapsToModelAndDoneState() {
        val todo = UserTodoEntity(
            id = 12,
            title = "完成实验报告",
            date = "2026-05-13",
            note = "提交到课程平台",
            done = true,
            createdAt = "2026-05-12T00:00:00Z",
            updatedAt = "2026-05-13T00:00:00Z",
        ).toModel()

        assertEquals(12L, todo.id)
        assertEquals("完成实验报告", todo.title)
        assertEquals("2026-05-13", todo.date)
        assertEquals("提交到课程平台", todo.note)
        assertTrue(todo.done)
    }

    @Test
    fun conflictDetectionUsesWeekOverlap() {
        val base = CourseEntry(
            weekday = "周四",
            period = "Period 3",
            courseCode = "A1",
            courseName = "操作系统",
            weeks = "第3-8周",
        )
        val overlapping = base.copy(courseCode = "LOCAL-1", courseName = "自习", weeks = "第8周", localId = 1, isUserCreated = true)
        val separate = base.copy(courseCode = "LOCAL-2", courseName = "训练", weeks = "第9周", localId = 2, isUserCreated = true)
        val otherPeriod = base.copy(courseCode = "LOCAL-3", period = "Period 4", courseName = "训练", weeks = "第5周")

        assertTrue(timetableEntriesConflict(base, overlapping))
        assertFalse(timetableEntriesConflict(base, separate))
        assertFalse(timetableEntriesConflict(base, otherPeriod))
    }
}
