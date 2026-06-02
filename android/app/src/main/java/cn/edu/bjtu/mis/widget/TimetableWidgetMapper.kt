package cn.edu.bjtu.mis.widget

import cn.edu.bjtu.mis.data.employment.EmploymentCalendarEvent
import cn.edu.bjtu.mis.data.employment.employmentCalendarEventTypeLabel
import cn.edu.bjtu.mis.data.homework.homeworkCalendarStatusLabel
import cn.edu.bjtu.mis.data.homework.homeworkDueDate
import cn.edu.bjtu.mis.data.homework.parseHomeworkDueAt
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.TimetableData
import cn.edu.bjtu.mis.model.UserTodoItem
import cn.edu.bjtu.mis.model.normalizedTimetablePeriodNumber
import cn.edu.bjtu.mis.model.normalizedTimetableWeekdayIndex
import cn.edu.bjtu.mis.model.timetableWeeksOverlap
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private const val CalendarEventColor: Int = -14390871
private const val HomeworkOpenColor: Int = -2737301
private const val HomeworkDoneColor: Int = -13981654
private const val TodoOpenColor: Int = -8623934
private const val TodoDoneColor: Int = -9737365
private const val EmploymentEventColor: Int = -15830000

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
    val color: Int = CalendarEventColor,
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
        homework: List<HomeworkItem> = emptyList(),
        todos: List<UserTodoItem> = emptyList(),
        employmentEvents: List<EmploymentCalendarEvent> = emptyList(),
    ): TimetableWidgetModel =
        buildModel(
            data = null,
            calendar = calendar,
            homework = homework,
            todos = todos,
            employmentEvents = employmentEvents,
            currentWeek = null,
            today = today,
            hasTimetableCache = false,
        )

    fun map(
        data: TimetableData,
        currentWeek: Int?,
        today: LocalDate = LocalDate.now(),
        calendar: CalendarData? = null,
        homework: List<HomeworkItem> = emptyList(),
        todos: List<UserTodoItem> = emptyList(),
        employmentEvents: List<EmploymentCalendarEvent> = emptyList(),
        hasTimetableCache: Boolean = true,
    ): TimetableWidgetModel =
        buildModel(
            data = data,
            calendar = calendar,
            homework = homework,
            todos = todos,
            employmentEvents = employmentEvents,
            currentWeek = currentWeek,
            today = today,
            hasTimetableCache = hasTimetableCache,
        )

    private fun buildModel(
        data: TimetableData?,
        calendar: CalendarData?,
        homework: List<HomeworkItem>,
        todos: List<UserTodoItem>,
        employmentEvents: List<EmploymentCalendarEvent>,
        currentWeek: Int?,
        today: LocalDate,
        hasTimetableCache: Boolean,
    ): TimetableWidgetModel {
        val tomorrow = today.plusDays(1)
        val hasCalendarContent = calendar != null ||
            homework.isNotEmpty() ||
            todos.isNotEmpty() ||
            employmentEvents.isNotEmpty()
        val title = data?.currentTerm?.takeIf { it.isNotBlank() }
            ?: calendar?.currentTerm?.takeIf { it.isNotBlank() }
            ?: if (hasCalendarContent) "学年日历" else "课表"
        val meta = listOfNotNull(
            formatDate(today),
            currentWeek?.let { "第${it}周" },
            weekdayLabel(today),
        ).joinToString(" ")

        return TimetableWidgetModel(
            title = title,
            meta = if (hasTimetableCache || hasCalendarContent) meta else "暂无课表缓存",
            calendarEvents = calendarEventModels(
                calendar = calendar,
                homework = homework,
                todos = todos,
                employmentEvents = employmentEvents,
                today = today,
            ),
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
        homework: List<HomeworkItem>,
        todos: List<UserTodoItem>,
        employmentEvents: List<EmploymentCalendarEvent>,
        today: LocalDate,
    ): List<TimetableWidgetCalendarEvent> {
        val dateLabels = mapOf(today to "今天", today.plusDays(1) to "明天")
        return buildList {
            calendar?.items.orEmpty().forEachIndexed { index, item ->
                val date = parseCalendarDate(item.date) ?: return@forEachIndexed
                val title = item.note?.trim()?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
                add(
                    CalendarEventDraft(
                        date = date,
                        sourceOrder = 0,
                        sortLabel = index.toString().padStart(4, '0'),
                        title = title,
                        detail = item.week?.takeIf { it.isNotBlank() }?.let { "第${it}周" }.orEmpty(),
                        color = CalendarEventColor,
                    )
                )
            }
            homework.forEach { item ->
                val date = homeworkDueDate(item) ?: return@forEach
                add(
                    CalendarEventDraft(
                        date = date,
                        sourceOrder = 1,
                        sortLabel = listOfNotNull(item.dueAt, item.course, item.title).joinToString("|"),
                        title = item.title.ifBlank { "作业截止" },
                        detail = homeworkEventDetail(item),
                        color = if (homeworkCalendarStatusLabel(item) == "已提交") HomeworkDoneColor else HomeworkOpenColor,
                    )
                )
            }
            todos.forEach { item ->
                val date = parseCalendarDate(item.date) ?: return@forEach
                add(
                    CalendarEventDraft(
                        date = date,
                        sourceOrder = 2,
                        sortLabel = listOf(item.done.toString(), item.title, item.createdAt).joinToString("|"),
                        title = item.title.ifBlank { "待办" },
                        detail = todoEventDetail(item),
                        color = if (item.done) TodoDoneColor else TodoOpenColor,
                    )
                )
            }
            employmentEvents.forEach { item ->
                add(
                    CalendarEventDraft(
                        date = item.date,
                        sourceOrder = 3,
                        sortLabel = listOf(item.sortDateTime?.toString().orEmpty(), item.title).joinToString("|"),
                        title = "${employmentCalendarEventTypeLabel(item.type)} ${item.title}",
                        detail = employmentEventDetail(item),
                        color = EmploymentEventColor,
                    )
                )
            }
        }
            .filter { it.date in dateLabels }
            .sortedWith(compareBy<CalendarEventDraft>({ it.date }, { it.sourceOrder }, { it.sortLabel }))
            .map { draft ->
                TimetableWidgetCalendarEvent(
                    dayLabel = dateLabels.getValue(draft.date),
                    dateLabel = formatDate(draft.date),
                    weekdayLabel = weekdayLabel(draft.date),
                    title = draft.title,
                    detail = draft.detail,
                    color = draft.color,
                )
            }
    }

    private data class CalendarEventDraft(
        val date: LocalDate,
        val sourceOrder: Int,
        val sortLabel: String,
        val title: String,
        val detail: String,
        val color: Int,
    )

    private fun homeworkEventDetail(item: HomeworkItem): String =
        listOfNotNull(
            homeworkCalendarStatusLabel(item),
            item.course.takeIf { it.isNotBlank() },
            homeworkDueTimeLabel(item.dueAt)?.let { "截止 $it" },
        ).joinToString(" · ")

    private fun todoEventDetail(item: UserTodoItem): String =
        listOfNotNull(
            if (item.done) "已完成待办" else "待办",
            item.note?.trim()?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    private fun employmentEventDetail(item: EmploymentCalendarEvent): String =
        listOfNotNull(
            employmentTimeLabel(item),
            item.location?.takeIf { it.isNotBlank() },
            item.organization?.takeIf { it.isNotBlank() },
            item.statusLabel?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

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

    private fun homeworkDueTimeLabel(value: String?): String? =
        parseHomeworkDueAt(value)
            ?.toLocalTime()
            ?.takeUnless { it == LocalTime.MIDNIGHT }
            ?.format(TimeFormatter)

    private fun employmentTimeLabel(event: EmploymentCalendarEvent): String? {
        val start = event.startTime.toClockLabel()
        val end = event.endTime.toClockLabel()
        return when {
            start != null && end != null -> "$start-$end"
            start != null -> start
            end != null -> end
            else -> null
        }
    }

    private fun String?.toClockLabel(): String? =
        this?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { text ->
                runCatching { java.time.LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
                    .getOrNull()
                    ?.toLocalTime()
                    ?.takeUnless { it == LocalTime.MIDNIGHT }
                    ?.format(TimeFormatter)
                    ?: Regex("""\d{1,2}:\d{2}""").find(text)?.value
            }

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

    private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
