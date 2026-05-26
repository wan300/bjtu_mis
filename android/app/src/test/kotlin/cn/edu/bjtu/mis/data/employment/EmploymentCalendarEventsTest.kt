package cn.edu.bjtu.mis.data.employment

import cn.edu.bjtu.mis.model.EmploymentInfoSummary
import cn.edu.bjtu.mis.model.EmploymentSectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EmploymentCalendarEventsTest {
    @Test
    fun mapsCareerTalkAndJobFair() {
        val events = employmentCalendarEvents(
            listOf(
                summary(
                    id = "talk-1",
                    type = EmploymentSectionType.CareerTalk,
                    title = "轨道交通专场宣讲会",
                    startTime = "2026-06-01 14:30:00",
                ),
                summary(
                    id = "fair-1",
                    type = EmploymentSectionType.JobFair,
                    title = "春季双选会",
                    startTime = "2026-06-02 09:00",
                ),
            )
        )

        assertEquals(2, events.size)
        assertEquals("talk-1", events[0].id)
        assertEquals(LocalDate.of(2026, 6, 1), events[0].date)
        assertEquals("fair-1", events[1].id)
        assertEquals(LocalDate.of(2026, 6, 2), events[1].date)
    }

    @Test
    fun ignoresRecruitmentAndInternship() {
        val events = employmentCalendarEvents(
            listOf(
                summary("job-1", EmploymentSectionType.Recruitment, "招聘信息", startTime = "2026-06-01"),
                summary("intern-1", EmploymentSectionType.Internship, "实习信息", startTime = "2026-06-02"),
            )
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun filtersInvalidDates() {
        val events = employmentCalendarEvents(
            listOf(
                summary("talk-1", EmploymentSectionType.CareerTalk, "无效日期宣讲会", startTime = "not-a-date"),
            )
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun deduplicatesByTypeAndId() {
        val events = employmentCalendarEvents(
            listOf(
                summary("same-id", EmploymentSectionType.CareerTalk, "第一条", startTime = "2026-06-01"),
                summary("same-id", EmploymentSectionType.CareerTalk, "重复条", startTime = "2026-06-01 10:00:00"),
                summary("same-id", EmploymentSectionType.JobFair, "不同类型同 ID", startTime = "2026-06-02"),
            )
        )

        assertEquals(2, events.size)
        assertEquals(listOf(EmploymentSectionType.CareerTalk, EmploymentSectionType.JobFair), events.map { it.type })
    }

    @Test
    fun fallsBackToEndTimeWhenStartTimeMissing() {
        val events = employmentCalendarEvents(
            listOf(
                summary(
                    id = "fair-1",
                    type = EmploymentSectionType.JobFair,
                    title = "结束时间双选会",
                    startTime = null,
                    endTime = "2026-06-03T16:00:00",
                ),
            )
        )

        assertEquals(1, events.size)
        assertEquals(LocalDate.of(2026, 6, 3), events.single().date)
    }

    private fun summary(
        id: String,
        type: EmploymentSectionType,
        title: String,
        startTime: String? = null,
        endTime: String? = null,
    ): EmploymentInfoSummary =
        EmploymentInfoSummary(
            id = id,
            type = type,
            title = title,
            url = "https://job.bjtu.edu.cn/$id",
            startTime = startTime,
            endTime = endTime,
        )
}
