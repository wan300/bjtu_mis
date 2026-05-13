package cn.edu.bjtu.mis.data.homework

import cn.edu.bjtu.mis.model.HomeworkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class HomeworkReminderEvaluatorTest {
    private val now = LocalDateTime.of(2026, 5, 10, 8, 0)
    private val evaluator = HomeworkReminderEvaluator()

    @Test
    fun includesOpenUnsubmittedHomeworkWithinSeventyTwoHours() {
        val content = evaluator.evaluate(
            items = listOf(homework(title = "接口设计", dueAt = "2026-05-13 08:00")),
            now = now,
        )

        assertEquals("接口设计", content?.candidates?.single()?.item?.title)
        assertEquals("你有 1 项作业即将截止", content?.title)
    }

    @Test
    fun filtersSubmittedExpiredBeyondWindowAndInvalidDueDates() {
        val content = evaluator.evaluate(
            items = listOf(
                homework(title = "已提交", dueAt = "2026-05-11 08:00", submittedAt = "2026-05-10 09:00"),
                homework(title = "已完成", dueAt = "2026-05-11 08:00", status = "done"),
                homework(title = "已过期", dueAt = "2026-05-10 07:59"),
                homework(title = "超过三天", dueAt = "2026-05-13 08:01"),
                homework(title = "坏日期", dueAt = "not-a-date"),
                homework(title = "有效", dueAt = "2026-05-11 08:00:00"),
            ),
            now = now,
        )

        assertEquals(listOf("有效"), content?.candidates?.map { it.item.title })
    }

    @Test
    fun sortsByDeadlineAndSummarizesAdditionalHomework() {
        val content = evaluator.evaluate(
            items = listOf(
                homework(title = "第三", dueAt = "2026-05-10 12:00"),
                homework(title = "第一", dueAt = "2026-05-10 10:00"),
                homework(title = "第四", dueAt = "2026-05-10 13:00"),
                homework(title = "第二", dueAt = "2026-05-10 11:00"),
            ),
            now = now,
        )

        assertEquals(listOf("第一", "第二", "第三", "第四"), content?.candidates?.map { it.item.title })
        assertEquals("你有 4 项作业即将截止", content?.title)
        assertTrue(content?.bigText?.contains("等 4 项作业即将截止") == true)
    }

    @Test
    fun returnsNullWhenAlreadyRemindedToday() {
        val content = evaluator.evaluate(
            items = listOf(homework(title = "接口设计", dueAt = "2026-05-11 08:00")),
            now = now,
            lastReminderDate = LocalDate.of(2026, 5, 10),
        )

        assertNull(content)
    }

    @Test
    fun coordinatorDoesNotRecordDateWhenSenderRejectsNotification() {
        val state = FakeReminderState()
        val coordinator = HomeworkReminderCoordinator(
            state = state,
            sender = HomeworkReminderNotificationSender { false },
            evaluator = evaluator,
        )

        val sent = coordinator.maybeSend(
            items = listOf(homework(title = "接口设计", dueAt = "2026-05-11 08:00")),
            now = now,
        )

        assertFalse(sent)
        assertNull(state.markedDate)
    }

    @Test
    fun coordinatorRecordsDateAfterSuccessfulNotification() {
        val state = FakeReminderState()
        val coordinator = HomeworkReminderCoordinator(
            state = state,
            sender = HomeworkReminderNotificationSender { true },
            evaluator = evaluator,
        )

        val sent = coordinator.maybeSend(
            items = listOf(homework(title = "接口设计", dueAt = "2026-05-11 08:00")),
            now = now,
        )

        assertTrue(sent)
        assertEquals(LocalDate.of(2026, 5, 10), state.markedDate)
    }

    private fun homework(
        title: String,
        dueAt: String,
        status: String = "open",
        submittedAt: String? = null,
    ): HomeworkItem =
        HomeworkItem(
            homeworkId = title.hashCode(),
            course = "软件工程",
            courseId = 1,
            title = title,
            dueAt = dueAt,
            submittedAt = submittedAt,
            status = status,
            subType = 0,
        )

    private class FakeReminderState(
        private val lastDate: LocalDate? = null,
    ) : HomeworkReminderState {
        var markedDate: LocalDate? = null

        override fun lastReminderDate(): LocalDate? = lastDate

        override fun markReminderSent(date: LocalDate) {
            markedDate = date
        }
    }
}
