package cn.edu.bjtu.mis.model

private const val MAX_PARSED_WEEK = 60
const val DEFAULT_USER_COURSE_MAX_WEEK = 21

fun userCourseWeekdayLabel(index: Int): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        .getOrElse(index.coerceIn(0, 6)) { "周一" }

fun formatUserCourseWeeks(startWeek: Int, endWeek: Int): String {
    val start = startWeek.coerceAtLeast(1)
    val end = endWeek.coerceAtLeast(1)
    val first = minOf(start, end)
    val last = maxOf(start, end)
    return if (first == last) {
        "第${first}周"
    } else {
        "第${first}-${last}周"
    }
}

fun formatUserCourseWeeks(weeks: Set<Int>, maxWeek: Int = DEFAULT_USER_COURSE_MAX_WEEK): String {
    val normalized = weeks
        .filter { it in 1..MAX_PARSED_WEEK }
        .toSortedSet()
        .toList()
    if (normalized.isEmpty()) return formatUserCourseWeeks(1, maxWeek)

    val safeMaxWeek = maxWeek.coerceIn(1, MAX_PARSED_WEEK)
    val fullSpan = (1..safeMaxWeek).toList()
    if (normalized == fullSpan.filter { it % 2 == 1 }) {
        return "${formatUserCourseWeeks(1, safeMaxWeek)}（单周）"
    }
    if (normalized == fullSpan.filter { it % 2 == 0 }) {
        return "${formatUserCourseWeeks(1, safeMaxWeek)}（双周）"
    }

    val first = normalized.first()
    val last = normalized.last()
    val span = (first..last).toList()
    if (normalized == span) return formatUserCourseWeeks(first, last)

    val oddSpan = span.filter { it % 2 == 1 }
    if (normalized == oddSpan) return "${formatUserCourseWeeks(first, last)}（单周）"

    val evenSpan = span.filter { it % 2 == 0 }
    if (normalized == evenSpan) return "${formatUserCourseWeeks(first, last)}（双周）"

    val segments = mutableListOf<String>()
    var index = 0
    while (index < normalized.size) {
        val start = normalized[index]
        var end = start
        while (index + 1 < normalized.size && normalized[index + 1] == end + 1) {
            index += 1
            end = normalized[index]
        }
        segments += if (start == end) start.toString() else "$start-$end"
        index += 1
    }
    return "第${segments.joinToString("、")}周"
}

fun parseUserCourseWeeks(
    weeks: String?,
    fallbackStartWeek: Int,
    fallbackEndWeek: Int,
    maxWeek: Int = DEFAULT_USER_COURSE_MAX_WEEK,
): Set<Int> {
    parseTimetableWeekNumbers(weeks)?.takeIf { it.isNotEmpty() }?.let { return it }
    val start = fallbackStartWeek.coerceAtLeast(1)
    val end = fallbackEndWeek.coerceAtLeast(1)
    val first = minOf(start, end)
    val last = maxOf(start, end).coerceAtMost(maxWeek.coerceAtLeast(first))
    return (first..last).toSet()
}

fun normalizedTimetableWeekdayIndex(value: String): Int? {
    val text = value.trim().lowercase()
    return when {
        text.contains("周一") || text.contains("星期一") || text.contains("mon") || text == "day 1" -> 0
        text.contains("周二") || text.contains("星期二") || text.contains("tue") || text == "day 2" -> 1
        text.contains("周三") || text.contains("星期三") || text.contains("wed") || text == "day 3" -> 2
        text.contains("周四") || text.contains("星期四") || text.contains("thu") || text == "day 4" -> 3
        text.contains("周五") || text.contains("星期五") || text.contains("fri") || text == "day 5" -> 4
        text.contains("周六") || text.contains("星期六") || text.contains("sat") || text == "day 6" -> 5
        text.contains("周日") || text.contains("周天") || text.contains("星期日") || text.contains("星期天") ||
            text.contains("sun") || text == "day 7" -> 6
        else -> Regex("""\d+""").find(text)?.value?.toIntOrNull()?.minus(1)?.takeIf { it in 0..6 }
    }
}

fun normalizedTimetablePeriodNumber(value: String): Int? =
    Regex("""\d+""").find(value)?.value?.toIntOrNull()

fun timetableWeeksOverlap(leftWeeks: String?, rightWeeks: String?): Boolean {
    val left = parseTimetableWeekNumbers(leftWeeks)
    val right = parseTimetableWeekNumbers(rightWeeks)
    if (left == null || right == null) return true
    return left.any { it in right }
}

fun timetableEntriesConflict(left: CourseEntry, right: CourseEntry): Boolean {
    val leftLocalId = left.localId
    val rightLocalId = right.localId
    if (leftLocalId != null && leftLocalId == rightLocalId) return false

    val leftDay = normalizedTimetableWeekdayIndex(left.weekday)
    val rightDay = normalizedTimetableWeekdayIndex(right.weekday)
    val sameDay = if (leftDay != null && rightDay != null) {
        leftDay == rightDay
    } else {
        left.weekday.trim() == right.weekday.trim()
    }
    if (!sameDay) return false

    val leftPeriod = normalizedTimetablePeriodNumber(left.period)
    val rightPeriod = normalizedTimetablePeriodNumber(right.period)
    val samePeriod = if (leftPeriod != null && rightPeriod != null) {
        leftPeriod == rightPeriod
    } else {
        left.period.trim() == right.period.trim()
    }
    return samePeriod && timetableWeeksOverlap(left.weeks, right.weeks)
}

internal fun parseTimetableWeekNumbers(weeks: String?): Set<Int>? {
    val text = weeks?.trim().orEmpty()
    if (text.isBlank()) return null

    val normalized = text.lowercase()
    val ranges = Regex("""(\d{1,2})(?:\s*[-~—–－至到]\s*(\d{1,2}))?""")
        .findAll(normalized)
        .mapNotNull { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val end = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: start
            minOf(start, end)..maxOf(start, end)
        }
        .toList()
    if (ranges.isEmpty()) return null

    val oddOnly = normalized.contains("单") || normalized.contains("odd")
    val evenOnly = normalized.contains("双") || normalized.contains("even")
    return ranges
        .flatMap { it }
        .filter { it in 1..MAX_PARSED_WEEK }
        .filter { week -> !oddOnly || week % 2 == 1 }
        .filter { week -> !evenOnly || week % 2 == 0 }
        .toSet()
        .ifEmpty { null }
}
