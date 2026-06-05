package cn.edu.bjtu.mis.data.calendar

import cn.edu.bjtu.mis.data.homework.homeworkDueDate
import cn.edu.bjtu.mis.data.homework.homeworkOpenDate
import cn.edu.bjtu.mis.model.CalendarItem
import cn.edu.bjtu.mis.model.ExamItem
import cn.edu.bjtu.mis.model.HomeworkItem
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class TaskCalendarBuckets(
    val homeworkStarts: List<HomeworkItem> = emptyList(),
    val homeworkDues: List<HomeworkItem> = emptyList(),
    val exams: List<ExamItem> = emptyList(),
    val calendarItems: List<CalendarItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = homeworkStarts.isEmpty() &&
            homeworkDues.isEmpty() &&
            exams.isEmpty() &&
            calendarItems.isEmpty()

    val totalCount: Int
        get() = homeworkStarts.size + homeworkDues.size + exams.size + calendarItems.size
}

fun groupTaskCalendarBuckets(
    homework: List<HomeworkItem>,
    exams: List<ExamItem>,
    calendarItems: List<CalendarItem>,
): Map<LocalDate, TaskCalendarBuckets> {
    val grouped = linkedMapOf<LocalDate, MutableTaskCalendarBuckets>()

    fun bucket(date: LocalDate): MutableTaskCalendarBuckets =
        grouped.getOrPut(date) { MutableTaskCalendarBuckets() }

    homework.forEach { item ->
        homeworkOpenDate(item)?.let { bucket(it).homeworkStarts += item }
        homeworkDueDate(item)?.let { bucket(it).homeworkDues += item }
    }
    exams.forEach { item ->
        examScheduleDate(item.schedule, item.term)?.let { bucket(it).exams += item }
    }
    calendarItems.forEach { item ->
        val note = item.note?.trim()
        if (!note.isNullOrBlank()) {
            calendarItemDate(item)?.let { bucket(it).calendarItems += item }
        }
    }

    return grouped.mapValues { (_, value) -> value.toBuckets() }
}

fun calendarItemDate(item: CalendarItem): LocalDate? =
    try {
        LocalDate.parse(item.date.trim())
    } catch (_: DateTimeParseException) {
        null
    }

@Suppress("UNUSED_PARAMETER")
fun examScheduleDate(schedule: String?, term: String? = null): LocalDate? {
    val text = schedule?.trim().orEmpty()
    if (text.isBlank()) return null
    return explicitExamDate(text)
}

private fun explicitExamDate(text: String): LocalDate? {
    NumericDateRegex.findAll(text).firstParsedDate()?.let { return it }
    ChineseDateRegex.findAll(text).firstParsedDate()?.let { return it }
    CompactDateRegex.findAll(text).firstParsedDate()?.let { return it }
    return null
}

private fun Sequence<MatchResult>.firstParsedDate(): LocalDate? =
    mapNotNull { match -> localDateOrNull(match.groupValues[1], match.groupValues[2], match.groupValues[3]) }
        .firstOrNull()

private fun localDateOrNull(year: String, month: String, day: String): LocalDate? =
    runCatching {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
    }.getOrNull()

private data class MutableTaskCalendarBuckets(
    val homeworkStarts: MutableList<HomeworkItem> = mutableListOf(),
    val homeworkDues: MutableList<HomeworkItem> = mutableListOf(),
    val exams: MutableList<ExamItem> = mutableListOf(),
    val calendarItems: MutableList<CalendarItem> = mutableListOf(),
) {
    fun toBuckets(): TaskCalendarBuckets =
        TaskCalendarBuckets(
            homeworkStarts = homeworkStarts.toList(),
            homeworkDues = homeworkDues.toList(),
            exams = exams.toList(),
            calendarItems = calendarItems.toList(),
        )
}

private val NumericDateRegex = Regex("""(?<!\d{4}[-/.])(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?![-/.\d])""")
private val ChineseDateRegex =
    Regex("""(\d{4})\s*\u5e74\s*(\d{1,2})\s*\u6708\s*(\d{1,2})\s*(?:\u65e5|\u53f7)?""")
private val CompactDateRegex = Regex("""(?<!\d)(20\d{2})(\d{2})(\d{2})(?!\d)""")
