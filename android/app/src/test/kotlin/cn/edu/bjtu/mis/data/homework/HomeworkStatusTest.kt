package cn.edu.bjtu.mis.data.homework

import cn.edu.bjtu.mis.model.HomeworkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class HomeworkStatusTest {
    private val now = LocalDateTime.of(2026, 5, 10, 8, 0)

    @Test
    fun classifiesExpiredHomeworkByMakeupAvailability() {
        assertEquals(
            HomeworkStatusKind.ExpiredCanSubmit,
            homeworkStatusKind(homework(dueAt = "2026-05-10 07:59", canSubmit = true, canSubmitExplicit = true), now),
        )
        assertEquals(
            HomeworkStatusKind.ExpiredClosed,
            homeworkStatusKind(homework(dueAt = "2026-05-10 07:59", canSubmit = false, canSubmitExplicit = true), now),
        )
    }

    @Test
    fun expiredHomeworkWithoutExplicitMakeupAvailabilityIsClosed() {
        assertEquals(
            HomeworkStatusKind.ExpiredClosed,
            homeworkStatusKind(homework(dueAt = "2026-05-10 07:59", canSubmit = true, canSubmitExplicit = false), now),
        )
    }

    @Test
    fun submittedHomeworkStaysDoneEvenAfterDeadline() {
        assertEquals(
            HomeworkStatusKind.Done,
            homeworkStatusKind(
                homework(dueAt = "2026-05-10 07:59", canSubmit = false, submittedAt = "2026-05-09 12:00"),
                now,
            ),
        )
    }

    @Test
    fun calendarHelpersExposeDueDateAndSubmissionLabel() {
        val open = homework(dueAt = "2026-05-13 23:59")
        val submitted = homework(dueAt = "2026-05-13 23:59", submittedAt = "2026-05-12 12:00")

        assertEquals(LocalDate.of(2026, 5, 13), homeworkDueDate(open))
        assertEquals("未提交", homeworkCalendarStatusLabel(open, now))
        assertEquals("已提交", homeworkCalendarStatusLabel(submitted, now))
    }

    @Test
    fun filtersExpiredSubclasses() {
        val canSubmit = homework(title = "补交", dueAt = "2026-05-10 07:59", canSubmit = true, canSubmitExplicit = true)
        val closed = homework(title = "关闭", dueAt = "2026-05-10 07:59", canSubmit = false, canSubmitExplicit = true)
        val open = homework(title = "待完成", dueAt = "2026-05-11 08:00")

        assertTrue(homeworkMatchesStatusFilter(canSubmit, "expired", now))
        assertTrue(homeworkMatchesStatusFilter(closed, "expired", now))
        assertTrue(homeworkMatchesStatusFilter(canSubmit, HomeworkStatusKind.ExpiredCanSubmit.code, now))
        assertTrue(homeworkMatchesStatusFilter(closed, HomeworkStatusKind.ExpiredClosed.code, now))
        assertFalse(homeworkMatchesStatusFilter(open, "expired", now))
    }

    private fun homework(
        title: String = "作业",
        dueAt: String,
        canSubmit: Boolean = true,
        canSubmitExplicit: Boolean = false,
        submittedAt: String? = null,
    ): HomeworkItem =
        HomeworkItem(
            homeworkId = title.hashCode(),
            course = "软件工程",
            courseId = 1,
            title = title,
            dueAt = dueAt,
            submittedAt = submittedAt,
            status = if (submittedAt == null) "open" else "done",
            subType = 0,
            canSubmit = canSubmit,
            canSubmitExplicit = canSubmitExplicit,
        )
}
