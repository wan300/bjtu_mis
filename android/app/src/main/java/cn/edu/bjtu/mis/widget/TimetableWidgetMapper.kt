package cn.edu.bjtu.mis.widget

import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.TimetableData
import cn.edu.bjtu.mis.model.normalizedTimetablePeriodNumber
import cn.edu.bjtu.mis.model.normalizedTimetableWeekdayIndex
import cn.edu.bjtu.mis.model.timetableWeeksOverlap
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class TimetableWidgetCourse(
    val title: String,
    val detail: String,
)

data class TimetableWidgetDay(
    val label: String,
    val dateLabel: String,
    val weekdayLabel: String,
    val courses: List<TimetableWidgetCourse>,
    val totalCount: Int,
)

data class TimetableWidgetModel(
    val title: String,
    val meta: String,
    val today: TimetableWidgetDay,
    val tomorrow: TimetableWidgetDay,
    val hasTimetableCache: Boolean,
)

object TimetableWidgetMapper {
    const val MAX_COURSES_PER_DAY = 3

    fun empty(today: LocalDate = LocalDate.now()): TimetableWidgetModel =
        buildModel(
            data = null,
            currentWeek = null,
            today = today,
            hasTimetableCache = false,
        )

    fun map(
        data: TimetableData,
        currentWeek: Int?,
        today: LocalDate = LocalDate.now(),
    ): TimetableWidgetModel =
        buildModel(
            data = data,
            currentWeek = currentWeek,
            today = today,
            hasTimetableCache = true,
        )

    private fun buildModel(
        data: TimetableData?,
        currentWeek: Int?,
        today: LocalDate,
        hasTimetableCache: Boolean,
    ): TimetableWidgetModel {
        val tomorrow = today.plusDays(1)
        val title = data?.currentTerm?.takeIf { it.isNotBlank() } ?: "课表"
        val meta = listOfNotNull(
            formatDate(today),
            currentWeek?.let { "第${it}周" },
            weekdayLabel(today),
        ).joinToString(" ")

        return TimetableWidgetModel(
            title = title,
            meta = if (hasTimetableCache) meta else "暂无课表缓存",
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
            courses = matched.take(MAX_COURSES_PER_DAY).map(::courseModel),
            totalCount = matched.size,
        )
    }

    private fun courseModel(entry: CourseEntry): TimetableWidgetCourse {
        val detail = listOfNotNull(
            entry.locationLabel(),
            entry.timeRange?.takeIf { it.isNotBlank() } ?: entry.period.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        return TimetableWidgetCourse(
            title = entry.courseName.ifBlank { "未命名课程" },
            detail = detail.ifBlank { "时间地点待补充" },
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
}
