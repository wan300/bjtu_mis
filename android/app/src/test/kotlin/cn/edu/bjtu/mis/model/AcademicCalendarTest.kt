package cn.edu.bjtu.mis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class AcademicCalendarTest {
    @Test
    fun secondTermStartsAtMarchFirstMondayAndIncludesSummerWeeks() {
        val calendar = buildAcademicCalendar("2025202602", "2025-2026第二学期")!!

        assertEquals(LocalDate.of(2026, 3, 2), calendar.startDate)
        assertEquals(LocalDate.of(2026, 9, 6), calendar.endDate)
        assertEquals(27, calendar.weeks.size)
        assertEquals("春", calendar.weeks.first().seasonLabel)
        assertEquals(18, calendar.weeks[17].seasonWeekNumber)
        assertEquals("夏", calendar.weeks[18].seasonLabel)
        assertEquals(1, calendar.weeks[18].seasonWeekNumber)
        assertEquals("夏", calendar.weeks.last().seasonLabel)
        assertEquals(9, calendar.weeks.last().seasonWeekNumber)
        assertEquals(LocalDate.of(2026, 9, 6), calendar.weeks.last().dates.last())
    }

    @Test
    fun mayTenthTwentyTwentySixFallsInSecondTermWeekTen() {
        val calendar = buildAcademicCalendar("2025202602", "2025-2026第二学期")!!
        val week = calendar.weeks.single { LocalDate.of(2026, 5, 10) in it.dates }

        assertEquals(10, week.termWeekNumber)
        assertEquals(10, week.seasonWeekNumber)
        assertEquals("春", week.seasonLabel)
    }

    @Test
    fun mayTwentyTwentySixMonthGridStartsOnSundayAndEndsOnSaturday() {
        val calendar = buildAcademicMonthCalendar(YearMonth.of(2026, 5))

        assertEquals(6, calendar.weeks.size)
        assertEquals(LocalDate.of(2026, 4, 26), calendar.weeks.first().days.first().date)
        assertEquals(LocalDate.of(2026, 6, 6), calendar.weeks.last().days.last().date)
        assertEquals(7, calendar.weeks.first().days.size)
        assertEquals(false, calendar.weeks.first().days.first().inMonth)
        assertEquals(true, calendar.weeks[1].days.first().inMonth)
    }

    @Test
    fun firstTermStartsAtSeptemberFirstMondayAndEndsBeforeSpringTerm() {
        val calendar = buildAcademicCalendar("2025202601", "2025-2026第一学期")!!

        assertEquals(LocalDate.of(2025, 9, 1), calendar.startDate)
        assertEquals(LocalDate.of(2026, 3, 1), calendar.endDate)
        assertEquals(26, calendar.weeks.size)
        assertEquals("秋", calendar.weeks.first().seasonLabel)
        assertEquals("冬", calendar.weeks.last().seasonLabel)
        assertEquals(LocalDate.of(2026, 3, 1), calendar.weeks.last().dates.last())
    }

    @Test
    fun invalidTermReturnsNull() {
        assertNull(buildAcademicCalendar("not-a-term", "无法解析"))
        assertNull(buildAcademicCalendar("2025202603", "2025-2026第三学期"))
    }

    @Test
    fun labelCanProvideTermWhenCodeIsMissing() {
        val calendar = buildAcademicCalendar(null, "2025-2026第二学期")!!

        assertEquals(2, calendar.termNumber)
        assertTrue(calendar.label.contains("第二学期"))
    }
}
