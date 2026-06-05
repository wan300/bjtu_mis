package cn.edu.bjtu.mis.data.exporting

import cn.edu.bjtu.mis.data.calendar.TaskCalendarBuckets
import cn.edu.bjtu.mis.data.employment.EmploymentCalendarEvent
import cn.edu.bjtu.mis.data.employment.employmentCalendarEventTypeLabel
import cn.edu.bjtu.mis.data.homework.homeworkCalendarStatusLabel
import cn.edu.bjtu.mis.model.AcademicCalendarTerm
import cn.edu.bjtu.mis.model.AcademicMonthCalendar
import cn.edu.bjtu.mis.model.CalendarItem
import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.ExamItem
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.TimetableData
import cn.edu.bjtu.mis.model.UserTodoItem
import cn.edu.bjtu.mis.model.normalizedTimetablePeriodNumber
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

object ScheduleExportContentBuilder {
    private val MonthTitleFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)
    private val DayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd E", Locale.CHINA)
    private val WeekdayColumns = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val MonthWeekdayColumns = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    fun buildTimetable(
        data: TimetableData,
        currentWeek: Int?,
        generatedAt: LocalDateTime = LocalDateTime.now(),
    ): ScheduleExportDocument {
        val entries = data.entries.sortedWith(
            compareBy<CourseEntry> { weekdayIndex(it.weekday) }
                .thenBy { periodIndex(data.periods, it.period) }
                .thenBy { it.courseName },
        )
        val periods = timetablePeriods(data, entries)
        val rows = periods.map { period ->
            val periodEntries = entries.groupBy { weekdayIndex(it.weekday) to it.period }
            val cells = (0..6).map { dayIndex ->
                periodEntries[dayIndex to period]
                    .orEmpty()
                    .joinToString("\n\n") { it.exportCourseLabel() }
            }
            listOf(periodLabel(period, entries)) + cells
        }
        val summary = buildList {
            data.currentTerm?.takeIf { it.isNotBlank() }?.let { add(it) }
            currentWeek?.let { add("当前第 $it 周") }
            add("${entries.size} 门课程")
        }.joinToString(" · ")

        return ScheduleExportDocument(
            title = "课表导出",
            subtitle = summary,
            generatedAt = generatedAt,
            blocks = listOf(
                ScheduleExportBlock(
                    table = ScheduleExportTable(
                        columns = listOf("节次/时间") + WeekdayColumns,
                        rows = rows.ifEmpty { listOf(listOf("暂无课程") + List(7) { "" }) },
                        columnWeights = listOf(0.9f) + List(7) { 1.15f },
                    )
                )
            ),
        )
    }

    fun buildTermOverview(
        calendar: AcademicCalendarTerm,
        currentWeek: Int?,
        today: LocalDate,
        generatedAt: LocalDateTime = LocalDateTime.now(),
    ): ScheduleExportDocument {
        val rows = calendar.weeks.map { week ->
            val weekLabel = buildList {
                add("第 ${week.termWeekNumber} 周")
                add("${week.seasonLabel}${week.seasonWeekNumber}周")
                if (week.termWeekNumber == currentWeek) add("当前周")
            }.joinToString("\n")
            listOf(weekLabel, week.monthLabel) + week.dates.map { date ->
                buildString {
                    append(date.monthValue).append('/').append(date.dayOfMonth)
                    if (date == today) append("\n今天")
                }
            }
        }
        return ScheduleExportDocument(
            title = "${calendar.label} 学期周表",
            subtitle = "${calendar.startDate} 至 ${calendar.endDate}",
            generatedAt = generatedAt,
            blocks = listOf(
                ScheduleExportBlock(
                    table = ScheduleExportTable(
                        columns = listOf("周次", "月份") + WeekdayColumns,
                        rows = rows,
                        columnWeights = listOf(1.05f, 0.72f) + List(7) { 0.9f },
                    )
                )
            ),
        )
    }

    fun buildMonthView(
        calendar: AcademicMonthCalendar,
        exportData: CalendarExportData,
        today: LocalDate,
        generatedAt: LocalDateTime = LocalDateTime.now(),
    ): ScheduleExportDocument {
        val gridRows = calendar.weeks.map { week ->
            week.days.map { day ->
                buildString {
                    append(day.date.dayOfMonth)
                    if (!day.inMonth) append(" 邻月")
                    if (day.date == today) append(" 今天")
                    dailySummary(day.date, exportData)?.let { append('\n').append(it) }
                }
            }
        }
        val agendaRows = datesInMonth(calendar.month)
            .flatMap { date -> dailyAgendaRows(date, exportData) }
            .ifEmpty { listOf(listOf("本月暂无事项", "", "")) }

        return ScheduleExportDocument(
            title = "${calendar.month.format(MonthTitleFormatter)} 学年日历",
            subtitle = "当前月视图",
            generatedAt = generatedAt,
            blocks = listOf(
                ScheduleExportBlock(
                    heading = "月历",
                    table = ScheduleExportTable(
                        columns = MonthWeekdayColumns,
                        rows = gridRows,
                    )
                ),
                ScheduleExportBlock(
                    heading = "事项",
                    table = ScheduleExportTable(
                        columns = listOf("日期", "类型", "内容"),
                        rows = agendaRows,
                        columnWeights = listOf(1.1f, 0.8f, 4.0f),
                    )
                ),
            ),
        )
    }

    fun buildWeekView(
        anchorDate: LocalDate,
        exportData: CalendarExportData,
        today: LocalDate,
        generatedAt: LocalDateTime = LocalDateTime.now(),
    ): ScheduleExportDocument {
        val weekStart = calendarWeekStart(anchorDate)
        val dates = (0L..6L).map { weekStart.plusDays(it) }
        val rows = dates.flatMap { date ->
            dailyAgendaRows(date, exportData)
                .ifEmpty {
                    listOf(
                        listOf(
                            date.format(DayFormatter) + if (date == today) " 今天" else "",
                            "无",
                            "暂无事项",
                        )
                    )
                }
        }
        return ScheduleExportDocument(
            title = "${weekStart} 至 ${weekStart.plusDays(6)} 学年日历",
            subtitle = "当前周视图",
            generatedAt = generatedAt,
            blocks = listOf(
                ScheduleExportBlock(
                    table = ScheduleExportTable(
                        columns = listOf("日期", "类型", "内容"),
                        rows = rows,
                        columnWeights = listOf(1.1f, 0.8f, 4.0f),
                    )
                )
            ),
        )
    }

    fun weekAnchorDate(selectedDate: LocalDate?, today: LocalDate, displayMonth: YearMonth): LocalDate =
        selectedDate ?: if (YearMonth.from(today) == displayMonth) today else displayMonth.atDay(1)

    fun calendarWeekStart(anchorDate: LocalDate): LocalDate =
        anchorDate.minusDays((anchorDate.dayOfWeek.value - 1).toLong())

    private fun timetablePeriods(data: TimetableData, entries: List<CourseEntry>): List<String> =
        (data.periods + entries.map { it.period })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { periodIndex(data.periods, it) }

    private fun periodLabel(period: String, entries: List<CourseEntry>): String {
        val time = entries.firstOrNull { it.period == period && !it.timeRange.isNullOrBlank() }?.timeRange
        return listOfNotNull("第 ${periodNumber(period)} 节", time?.replace("-", " - "))
            .joinToString("\n")
    }

    private fun CourseEntry.exportCourseLabel(): String =
        buildList {
            add(courseName)
            teacher?.takeIf { it.isNotBlank() }?.let { add("教师：$it") }
            locationLabel()?.let { add("地点：$it") }
            weeks?.takeIf { it.isNotBlank() }?.let { add("周次：$it") }
            remark?.takeIf { isUserCreated && it.isNotBlank() }?.let { add("备注：$it") }
        }.joinToString("\n")

    private fun dailySummary(date: LocalDate, exportData: CalendarExportData): String? {
        val count = dailyAgendaRows(date, exportData).size
        return count.takeIf { it > 0 }?.let { "$it 项事项" }
    }

    private fun dailyAgendaRows(date: LocalDate, exportData: CalendarExportData): List<List<String>> {
        val dateLabel = date.format(DayFormatter)
        val buckets = exportData.bucketsByDate[date] ?: TaskCalendarBuckets()
        return buildList {
            buckets.calendarItems.forEach { item ->
                item.note?.trim()?.takeIf { it.isNotBlank() }?.let { note ->
                    add(listOf(dateLabel, "校历", note))
                }
            }
            buckets.homeworkStarts.forEach { item ->
                add(listOf(dateLabel, "作业开始", item.homeworkLabel()))
            }
            buckets.homeworkDues.forEach { item ->
                add(listOf(dateLabel, "作业截止", item.homeworkLabel()))
            }
            buckets.exams.forEach { exam ->
                add(listOf(dateLabel, "考试", exam.examLabel()))
            }
            exportData.todosByDate[date].orEmpty().forEach { todo ->
                add(listOf(dateLabel, "待办", "${todo.title.trim()}（${if (todo.done) "已完成" else "未完成"}）"))
            }
            exportData.employmentEventsByDate[date].orEmpty().forEach { event ->
                add(listOf(dateLabel, employmentCalendarEventTypeLabel(event.type), event.employmentLabel()))
            }
        }
    }

    private fun HomeworkItem.homeworkLabel(): String =
        listOf(
            course.takeIf { it.isNotBlank() },
            title.takeIf { it.isNotBlank() },
            homeworkCalendarStatusLabel(this).takeIf { it.isNotBlank() }?.let { "状态：$it" },
            dueAt?.takeIf { it.isNotBlank() }?.let { "截止：$it" },
        ).filterNotNull().joinToString(" · ")

    private fun ExamItem.examLabel(): String =
        listOfNotNull(
            courseName.takeIf { it.isNotBlank() },
            schedule?.takeIf { it.isNotBlank() },
            examMode?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    private fun EmploymentCalendarEvent.employmentLabel(): String =
        listOfNotNull(
            title.takeIf { it.isNotBlank() },
            employmentTimeLabel(),
            location?.takeIf { it.isNotBlank() },
            organization?.takeIf { it.isNotBlank() },
            statusLabel?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

    private fun EmploymentCalendarEvent.employmentTimeLabel(): String? =
        when {
            !startTime.isNullOrBlank() && !endTime.isNullOrBlank() ->
                "${startTime.trim().removeSuffix(":00")} - ${endTime.trim().removeSuffix(":00")}"
            !startTime.isNullOrBlank() -> startTime.trim().removeSuffix(":00")
            !endTime.isNullOrBlank() -> endTime.trim().removeSuffix(":00")
            else -> null
        }

    private fun datesInMonth(month: YearMonth): List<LocalDate> =
        (1..month.lengthOfMonth()).map { month.atDay(it) }

    private fun CourseEntry.locationLabel(): String? =
        locationText ?: listOfNotNull(campus, building, room).joinToString(" ").takeIf { it.isNotBlank() }

    private fun weekdayIndex(value: String): Int {
        val text = value.trim().lowercase(Locale.ROOT)
        return when {
            text.contains("周一") || text.contains("星期一") || text.contains("mon") || text == "day 1" -> 0
            text.contains("周二") || text.contains("星期二") || text.contains("tue") || text == "day 2" -> 1
            text.contains("周三") || text.contains("星期三") || text.contains("wed") || text == "day 3" -> 2
            text.contains("周四") || text.contains("星期四") || text.contains("thu") || text == "day 4" -> 3
            text.contains("周五") || text.contains("星期五") || text.contains("fri") || text == "day 5" -> 4
            text.contains("周六") || text.contains("星期六") || text.contains("sat") || text == "day 6" -> 5
            text.contains("周日") || text.contains("星期日") || text.contains("星期天") || text.contains("sun") || text == "day 7" -> 6
            else -> Regex("""\d+""").find(text)?.value?.toIntOrNull()?.minus(1)?.takeIf { it in 0..6 }
                ?: Int.MAX_VALUE
        }
    }

    private fun periodIndex(periods: List<String>, period: String): Int {
        val sourceIndex = periods.indexOf(period)
        if (sourceIndex >= 0) return sourceIndex
        return normalizedTimetablePeriodNumber(period) ?: Int.MAX_VALUE
    }

    private fun periodNumber(period: String): String =
        normalizedTimetablePeriodNumber(period)?.toString() ?: period
}
