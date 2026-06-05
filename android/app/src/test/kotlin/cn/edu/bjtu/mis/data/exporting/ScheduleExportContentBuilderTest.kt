package cn.edu.bjtu.mis.data.exporting

import cn.edu.bjtu.mis.data.calendar.TaskCalendarBuckets
import cn.edu.bjtu.mis.data.employment.EmploymentCalendarEvent
import cn.edu.bjtu.mis.model.CalendarItem
import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.ExamItem
import cn.edu.bjtu.mis.model.EmploymentSectionType
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.TimetableData
import cn.edu.bjtu.mis.model.UserTodoItem
import cn.edu.bjtu.mis.model.buildAcademicCalendar
import cn.edu.bjtu.mis.model.buildAcademicMonthCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class ScheduleExportContentBuilderTest {
    private val generatedAt = LocalDateTime.of(2026, 6, 6, 10, 30)

    @Test
    fun timetableIncludesCourseFieldsAndWeeks() {
        val document = ScheduleExportContentBuilder.buildTimetable(
            data = TimetableData(
                days = listOf("周一", "周二"),
                periods = listOf("第1节"),
                entries = listOf(
                    CourseEntry(
                        weekday = "周一",
                        period = "第1节",
                        timeRange = "08:00-09:40",
                        courseCode = "CS101",
                        courseName = "软件工程",
                        teacher = "张老师",
                        weeks = "1-16周",
                        building = "思源楼",
                        room = "101",
                    )
                ),
                currentTerm = "2025-2026第二学期",
            ),
            currentWeek = 12,
            generatedAt = generatedAt,
        )

        val text = document.flattenedText()
        assertTrue(text.contains("软件工程"))
        assertTrue(text.contains("张老师"))
        assertTrue(text.contains("思源楼 101"))
        assertTrue(text.contains("1-16周"))
        assertTrue(text.contains("当前第 12 周"))
        assertFalse(text.contains("学号"))
    }

    @Test
    fun emptyTimetableStillBuildsExportableDocument() {
        val document = ScheduleExportContentBuilder.buildTimetable(
            data = TimetableData(),
            currentWeek = null,
            generatedAt = generatedAt,
        )

        assertTrue(document.flattenedText().contains("暂无课程"))
    }

    @Test
    fun termOverviewDoesNotIncludeTaskDetails() {
        val calendar = buildAcademicCalendar("2025202602", "2025-2026第二学期")!!
        val document = ScheduleExportContentBuilder.buildTermOverview(
            calendar = calendar,
            currentWeek = 2,
            today = LocalDate.of(2026, 3, 9),
            generatedAt = generatedAt,
        )

        val text = document.flattenedText()
        assertTrue(text.contains("2025-2026第二学期"))
        assertTrue(text.contains("当前周"))
        assertFalse(text.contains("作业"))
        assertFalse(text.contains("考试"))
        assertFalse(text.contains("待办"))
        assertFalse(text.contains("宣讲会"))
    }

    @Test
    fun monthViewIncludesAllAgendaTypesAndFiltersDetails() {
        val date = LocalDate.of(2026, 6, 2)
        val exportData = CalendarExportData(
            bucketsByDate = mapOf(
                date to TaskCalendarBuckets(
                    homeworkStarts = listOf(homework("需求文档", openedAt = "2026-06-02 08:00")),
                    homeworkDues = listOf(homework("实验报告", dueAt = "2026-06-02 23:59")),
                    exams = listOf(ExamItem(courseName = "高等数学", schedule = "2026年6月2日 14:00 教室A101", term = "2025-2026-2-2")),
                    calendarItems = listOf(CalendarItem(date = "2026-06-02", note = "校历安排")),
                )
            ),
            todosByDate = mapOf(date to listOf(todo("整理材料", note = "不应导出的待办备注"))),
            employmentEventsByDate = mapOf(date to listOf(employmentEvent("轨道交通专场宣讲会"))),
        )

        val document = ScheduleExportContentBuilder.buildMonthView(
            calendar = buildAcademicMonthCalendar(YearMonth.of(2026, 6)),
            exportData = exportData,
            today = date,
            generatedAt = generatedAt,
        )

        val text = document.flattenedText()
        assertTrue(text.contains("校历安排"))
        assertTrue(text.contains("需求文档"))
        assertTrue(text.contains("实验报告"))
        assertTrue(text.contains("高等数学"))
        assertTrue(text.contains("整理材料"))
        assertTrue(text.contains("轨道交通专场宣讲会"))
        assertFalse(text.contains("https://job.bjtu.edu.cn/event"))
        assertFalse(text.contains("不应导出的待办备注"))
        assertFalse(text.contains("不应导出的作业正文"))
    }

    @Test
    fun weekViewAnchorFollowsSelectedOrTodayOrMonthStart() {
        val today = LocalDate.of(2026, 6, 6)
        assertEquals(
            LocalDate.of(2026, 6, 3),
            ScheduleExportContentBuilder.weekAnchorDate(
                selectedDate = LocalDate.of(2026, 6, 3),
                today = today,
                displayMonth = YearMonth.of(2026, 5),
            ),
        )
        assertEquals(today, ScheduleExportContentBuilder.weekAnchorDate(null, today, YearMonth.of(2026, 6)))
        assertEquals(
            LocalDate.of(2026, 5, 1),
            ScheduleExportContentBuilder.weekAnchorDate(null, today, YearMonth.of(2026, 5)),
        )
        assertEquals(LocalDate.of(2026, 6, 1), ScheduleExportContentBuilder.calendarWeekStart(today))
    }

    private fun homework(title: String, openedAt: String? = null, dueAt: String? = null): HomeworkItem =
        HomeworkItem(
            homeworkId = title.hashCode(),
            course = "软件工程",
            courseId = 1,
            title = title,
            contentExcerpt = "不应导出的作业正文",
            openedAt = openedAt,
            dueAt = dueAt,
            status = "open",
            subType = 0,
        )

    private fun todo(title: String, note: String): UserTodoItem =
        UserTodoItem(
            id = 1,
            title = title,
            date = "2026-06-02",
            note = note,
            done = false,
            createdAt = "2026-06-01T00:00:00",
            updatedAt = "2026-06-01T00:00:00",
        )

    private fun employmentEvent(title: String): EmploymentCalendarEvent =
        EmploymentCalendarEvent(
            id = "event",
            type = EmploymentSectionType.CareerTalk,
            title = title,
            date = LocalDate.of(2026, 6, 2),
            startTime = "2026-06-02 15:00:00",
            location = "就业中心",
            url = "https://job.bjtu.edu.cn/event",
        )
}
