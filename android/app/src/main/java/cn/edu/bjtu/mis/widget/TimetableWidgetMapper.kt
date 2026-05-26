package cn.edu.bjtu.mis.widget

import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.CalendarItem
import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.TimetableData
import cn.edu.bjtu.mis.model.normalizedTimetablePeriodNumber
import cn.edu.bjtu.mis.model.normalizedTimetableWeekdayIndex
import cn.edu.bjtu.mis.model.timetableWeeksOverlap
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class TimetableWidgetCourse(
    val title: String,
    val timeLabel: String,
    val detail: String,
)

data class TimetableWidgetDay(
    val label: String,
    val dateLabel: String,
    val weekdayLabel: String,
    val courses: List<TimetableWidgetCourse>,
    val totalCount: Int,
)

data class TimetableWidgetCalendarEvent(
    val dayLabel: String,
    val dateLabel: String,
    val weekdayLabel: String,
    val title: String,
    val detail: String,
    val placeholder: Boolean = false,
)

data class TimetableWidgetModel(
    val title: String,
    val meta: String,
    val calendarEvents: List<TimetableWidgetCalendarEvent>,
    val today: TimetableWidgetDay,
    val tomorrow: TimetableWidgetDay,
    val hasTimetableCache: Boolean,
)

object TimetableWidgetMapper {
    fun empty(
        today: LocalDate = LocalDate.now(),
        calendar: CalendarData? = null,
    ): TimetableWidgetModel =
        buildModel(
            data = null,
            calendar = calendar,
            currentWeek = null,
            today = today,
            hasTimetableCache = false,
        )

    fun map(
        data: TimetableData,
        currentWeek: Int?,
        today: LocalDate = LocalDate.now(),
        calendar: CalendarData? = null,
        hasTimetableCache: Boolean = true,
    ): TimetableWidgetModel =
        buildModel(
            data = data,
            calendar = calendar,
            currentWeek = currentWeek,
            today = today,
            hasTimetableCache = hasTimetableCache,
        )

    private fun buildModel(
        data: TimetableData?,
        calendar: CalendarData?,
        currentWeek: Int?,
        today: LocalDate,
        hasTimetableCache: Boolean,
    ): TimetableWidgetModel {
        val tomorrow = today.plusDays(1)
        val title = data?.currentTerm?.takeIf { it.isNotBlank() }
            ?: calendar?.currentTerm?.takeIf { it.isNotBlank() }
            ?: "课表"
        val meta = listOfNotNull(
            formatDate(today),
            currentWeek?.let { "第${it}周" },
            weekdayLabel(today),
        ).joinToString(" ")

        return TimetableWidgetModel(
            title = title,
            meta = if (hasTimetableCache || calendar != null) meta else "暂无课表缓存",
            calendarEvents = calendarEventModels(calendar, today),
            today = dayModel("今天", today, data?.entries.orEmpty(), currentWeek),
            tomorrow = dayModel("明天", tomorrow, data?.entries.orEmpty(), currentWeek),
            hasTimetableCache = hasTimetableCache,
        )
    }

    private fun dayModel(
        label: String,
        date: LocalDate,
        entries: List<CourseEntry>,
        currentWeek: Int?,
    ): TimetableWidgetDay {
        val weekdayIndex = date.dayOfWeek.value - 1
        val matched = entries
            .asSequence()
            .filter { entry -> normalizedTimetableWeekdayIndex(entry.weekday) == weekdayIndex }
            .filter { entry -> currentWeek == null || timetableWeeksOverlap(entry.weeks, "第${currentWeek}周") }
            .sortedWith(
                compareBy<CourseEntry>(
                    { normalizedTimetablePeriodNumber(it.period) ?: Int.MAX_VALUE },
                    { it.period },
                    { it.courseName },
                )
            )
            .toList()

        return TimetableWidgetDay(
            label = label,
            dateLabel = formatDate(date),
            weekdayLabel = weekdayLabel(date),
            courses = matched.map(::courseModel),
            totalCount = matched.size,
        )
    }

    private fun calendarEventModels(
        calendar: CalendarData?,
        today: LocalDate,
    ): List<TimetableWidgetCalendarEvent> {
        val itemsByDate = calendar?.items
            .orEmpty()
            .groupBy { parseCalendarDate(it.date) }
        return listOf("今天" to today, "明天" to today.plusDays(1)).flatMap { (label, date) ->
            val items = itemsByDate[date].orEmpty()
            if (items.isEmpty()) {
                listOf(
                    TimetableWidgetCalendarEvent(
                        dayLabel = label,
                        dateLabel = formatDate(date),
                        weekdayLabel = weekdayLabel(date),
                        title = "暂无校历事项",
                        detail = "",
                        placeholder = true,
                    )
                )
            } else {
                items.map { item -> calendarEventModel(label, date, item) }
            }
        }
    }

    private fun calendarEventModel(
        dayLabel: String,
        date: LocalDate,
        item: CalendarItem,
    ): TimetableWidgetCalendarEvent =
        TimetableWidgetCalendarEvent(
            dayLabel = dayLabel,
            dateLabel = formatDate(date),
            weekdayLabel = weekdayLabel(date),
            title = item.note?.takeIf { it.isNotBlank() } ?: "校历安排",
            detail = item.week?.takeIf { it.isNotBlank() }?.let { "第${it}周" }.orEmpty(),
        )

    private fun courseModel(entry: CourseEntry): TimetableWidgetCourse {
        val timeLabel = entry.timeRange
            ?.takeIf { it.isNotBlank() }
            ?.replace(Regex("""\s*-\s*"""), "-")
            ?: entry.period.takeIf { it.isNotBlank() }
            ?: "时间待补充"
        val detail = entry.locationLabel().orEmpty()
        return TimetableWidgetCourse(
            title = entry.courseName.ifBlank { "未命名课程" },
            timeLabel = timeLabel,
            detail = detail.ifBlank { "地点待补充" },
        )
    }

    private fun CourseEntry.locationLabel(): String? =
        locationText?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(campus, building, room)
                .joinToString(" ")
                .takeIf { it.isNotBlank() }

    private fun formatDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("M.d"))

    private fun weekdayLabel(date: LocalDate): String =
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]

    private fun parseCalendarDate(value: String): LocalDate? {
        val text = value.trim()
        val match = Regex("""\d{4}[-/]\d{1,2}[-/]\d{1,2}""").find(text)?.value ?: text
        val normalized = match.replace('/', '-')
        return listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy-M-d"),
        ).firstNotNullOfOrNull { formatter ->
            try {
                LocalDate.parse(normalized, formatter)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
