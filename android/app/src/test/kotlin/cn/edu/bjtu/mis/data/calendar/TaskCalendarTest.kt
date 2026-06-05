package cn.edu.bjtu.mis.data.calendar

import cn.edu.bjtu.mis.model.CalendarItem
import cn.edu.bjtu.mis.model.ExamItem
import cn.edu.bjtu.mis.model.HomeworkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TaskCalendarTest {
    @Test
    fun groupsHomeworkStartsDuesExamsAndCalendarItems() {
        val homework = HomeworkItem(
            homeworkId = 1,
            course = "Software Engineering",
            courseId = 10,
            title = "Requirements doc",
            openedAt = "2026-05-10 08:00",
            dueAt = "2026-05-12 23:59:00",
            status = "open",
            subType = 0,
        )
        val exam = ExamItem(
            courseName = "Advanced Math",
            schedule = "2026-05-12 09:00-11:00",
            term = "2025-2026-2-2",
        )
        val calendarItem = CalendarItem(date = "2026-05-12", week = "12", note = "Makeup class")

        val grouped = groupTaskCalendarBuckets(
            homework = listOf(homework),
            exams = listOf(exam),
            calendarItems = listOf(calendarItem),
        )

        assertEquals(listOf(homework), grouped.getValue(LocalDate.of(2026, 5, 10)).homeworkStarts)
        val dueDay = grouped.getValue(LocalDate.of(2026, 5, 12))
        assertEquals(listOf(homework), dueDay.homeworkDues)
        assertEquals(listOf(exam), dueDay.exams)
        assertEquals(listOf(calendarItem), dueDay.calendarItems)
    }

    @Test
    fun ignoresInvalidAndBlankCalendarSources() {
        val grouped = groupTaskCalendarBuckets(
            homework = listOf(
                HomeworkItem(
                    homeworkId = 1,
                    course = "Software Engineering",
                    courseId = 10,
                    title = "Bad dates",
                    openedAt = "not-a-date",
                    dueAt = "also-bad",
                    status = "open",
                    subType = 0,
                )
            ),
            exams = listOf(ExamItem(courseName = "English", schedule = "week 12 monday")),
            calendarItems = listOf(
                CalendarItem(date = "not-a-date", note = "Invalid"),
                CalendarItem(date = "2026-05-12", note = " "),
            ),
        )

        assertEquals(emptyMap<LocalDate, TaskCalendarBuckets>(), grouped)
    }

    @Test
    fun parsesOnlyExplicitExamDates() {
        assertEquals(LocalDate.of(2026, 6, 1), examScheduleDate("2026\u5e746\u67081\u65e5 14:00"))
        assertEquals(LocalDate.of(2026, 6, 1), examScheduleDate("2026/06/01 14:00"))
        assertEquals(LocalDate.of(2026, 6, 1), examScheduleDate("20260601 14:00"))
        assertNull(examScheduleDate("6\u67081\u65e5 14:00", "2025-2026-2-2"))
        assertNull(examScheduleDate("2025-2026-2-2 6\u67081\u65e5 14:00"))
    }
}
