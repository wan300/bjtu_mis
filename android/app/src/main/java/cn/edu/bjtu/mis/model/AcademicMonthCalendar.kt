package cn.edu.bjtu.mis.model

import java.time.LocalDate
import java.time.YearMonth

data class AcademicMonthCalendar(
    val month: YearMonth,
    val weeks: List<AcademicMonthWeek>,
)

data class AcademicMonthWeek(
    val days: List<AcademicMonthDay>,
)

data class AcademicMonthDay(
    val date: LocalDate,
    val inMonth: Boolean,
)

fun buildAcademicMonthCalendar(month: YearMonth): AcademicMonthCalendar {
    val firstDay = month.atDay(1)
    val lastDay = month.atEndOfMonth()
    val firstDayOffset = firstDay.dayOfWeek.value % 7
    val lastDayOffset = lastDay.dayOfWeek.value % 7
    val gridStart = firstDay.minusDays(firstDayOffset.toLong())
    val gridEnd = lastDay.plusDays((6 - lastDayOffset).toLong())

    val days = generateSequence(gridStart) { it.plusDays(1) }
        .takeWhile { !it.isAfter(gridEnd) }
        .map { date ->
            AcademicMonthDay(
                date = date,
                inMonth = YearMonth.from(date) == month,
            )
        }
        .toList()

    return AcademicMonthCalendar(
        month = month,
        weeks = days.chunked(7).map(::AcademicMonthWeek),
    )
}

fun defaultAcademicMonth(today: LocalDate, term: AcademicCalendarTerm?): YearMonth {
    if (term == null) return YearMonth.from(today)
    val todayInTerm = !today.isBefore(term.startDate) && !today.isAfter(term.endDate)
    return YearMonth.from(if (todayInTerm) today else term.startDate)
}
