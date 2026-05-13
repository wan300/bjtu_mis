package cn.edu.bjtu.mis.model

import java.time.DayOfWeek
import java.time.LocalDate

data class AcademicCalendarTerm(
    val code: String,
    val label: String,
    val academicYearStart: Int,
    val academicYearEnd: Int,
    val termNumber: Int,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val weeks: List<AcademicCalendarWeek>,
)

data class AcademicCalendarWeek(
    val termWeekNumber: Int,
    val seasonWeekNumber: Int,
    val seasonLabel: String,
    val monthLabel: String,
    val dates: List<LocalDate>,
)

fun buildAcademicCalendar(termCode: String?, label: String? = null): AcademicCalendarTerm? {
    val identity = parseAcademicCalendarTerm(termCode, label) ?: return null
    val (startDate, endDate) = when (identity.termNumber) {
        1 -> firstMondayOfMonth(identity.academicYearStart, 9) to firstMondayOfMonth(identity.academicYearEnd, 3).minusDays(1)
        2 -> firstMondayOfMonth(identity.academicYearEnd, 3) to firstMondayOfMonth(identity.academicYearEnd, 9).minusDays(1)
        else -> return null
    }
    if (endDate.isBefore(startDate)) return null

    val weeks = generateSequence(startDate) { it.plusWeeks(1) }
        .takeWhile { !it.isAfter(endDate) }
        .mapIndexed { index, weekStart ->
            val termWeekNumber = index + 1
            val dates = (0..6).map { weekStart.plusDays(it.toLong()) }
            val firstSeason = if (identity.termNumber == 1) "秋" else "春"
            val secondSeason = if (identity.termNumber == 1) "冬" else "夏"
            AcademicCalendarWeek(
                termWeekNumber = termWeekNumber,
                seasonWeekNumber = if (termWeekNumber <= MAIN_SEASON_WEEKS) termWeekNumber else termWeekNumber - MAIN_SEASON_WEEKS,
                seasonLabel = if (termWeekNumber <= MAIN_SEASON_WEEKS) firstSeason else secondSeason,
                monthLabel = dates.map { it.monthValue }.distinct().joinToString("/") { "${it}月" },
                dates = dates,
            )
        }
        .toList()

    return AcademicCalendarTerm(
        code = termCode.orEmpty(),
        label = label?.takeIf { it.isNotBlank() } ?: defaultAcademicCalendarLabel(identity),
        academicYearStart = identity.academicYearStart,
        academicYearEnd = identity.academicYearEnd,
        termNumber = identity.termNumber,
        startDate = startDate,
        endDate = endDate,
        weeks = weeks,
    )
}

private const val MAIN_SEASON_WEEKS = 18

private data class AcademicCalendarIdentity(
    val academicYearStart: Int,
    val academicYearEnd: Int,
    val termNumber: Int,
)

private fun parseAcademicCalendarTerm(termCode: String?, label: String?): AcademicCalendarIdentity? {
    parseCompactTermCode(termCode)?.let { return it }

    return listOfNotNull(termCode, label)
        .asSequence()
        .mapNotNull(::parseTermText)
        .firstOrNull()
}

private fun parseCompactTermCode(termCode: String?): AcademicCalendarIdentity? {
    val digits = termCode?.filter { it.isDigit() } ?: return null
    if (digits.length != 10) return null
    val startYear = digits.substring(0, 4).toIntOrNull() ?: return null
    val endYear = digits.substring(4, 8).toIntOrNull() ?: return null
    val termNumber = digits.substring(8, 10).toIntOrNull() ?: return null
    return AcademicCalendarIdentity(startYear, endYear, termNumber).takeIf { it.isValid() }
}

private fun parseTermText(text: String): AcademicCalendarIdentity? {
    val yearMatch = Regex("""(20\d{2})\D*(20\d{2})""").find(text) ?: return null
    val startYear = yearMatch.groupValues[1].toIntOrNull() ?: return null
    val endYear = yearMatch.groupValues[2].toIntOrNull() ?: return null
    val tail = text.substring(yearMatch.range.last + 1)
    val termText = Regex("""第?\s*([一二12])\s*学期""").find(text)?.groupValues?.getOrNull(1)
    val termNumber = termText?.toAcademicTermNumber()
        ?: Regex("""[12]""").find(tail)?.value?.toIntOrNull()
        ?: return null
    return AcademicCalendarIdentity(startYear, endYear, termNumber).takeIf { it.isValid() }
}

private fun String.toAcademicTermNumber(): Int? = when (this) {
    "1", "一" -> 1
    "2", "二" -> 2
    else -> null
}

private fun AcademicCalendarIdentity.isValid(): Boolean =
    academicYearEnd == academicYearStart + 1 && termNumber in 1..2

private fun firstMondayOfMonth(year: Int, month: Int): LocalDate {
    var date = LocalDate.of(year, month, 1)
    while (date.dayOfWeek != DayOfWeek.MONDAY) {
        date = date.plusDays(1)
    }
    return date
}

private fun defaultAcademicCalendarLabel(identity: AcademicCalendarIdentity): String =
    "${identity.academicYearStart}-${identity.academicYearEnd}第${if (identity.termNumber == 1) "一" else "二"}学期"
