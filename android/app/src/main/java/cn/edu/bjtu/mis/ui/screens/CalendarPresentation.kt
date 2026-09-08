package cn.edu.bjtu.mis.ui.screens

import cn.edu.bjtu.mis.data.employment.EmploymentCalendarEvent
import cn.edu.bjtu.mis.model.UserTodoItem
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal fun List<UserTodoItem>.groupByTodoDate(): Map<LocalDate, List<UserTodoItem>> =
    mapNotNull { item -> item.todoDate()?.let { it to item } }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })

internal fun List<EmploymentCalendarEvent>.groupByEmploymentEventDate(): Map<LocalDate, List<EmploymentCalendarEvent>> =
    groupBy { it.date }

internal fun UserTodoItem.todoDate(): LocalDate? =
    try {
        LocalDate.parse(date)
    } catch (_: DateTimeParseException) {
        null
    }

internal fun EmploymentCalendarEvent.employmentTimeLabel(): String? =
    when {
        !startTime.isNullOrBlank() && !endTime.isNullOrBlank() ->
            "${startTime.trim().removeSuffix(":00")} - ${endTime.trim().removeSuffix(":00")}"
        !startTime.isNullOrBlank() -> startTime.trim().removeSuffix(":00")
        !endTime.isNullOrBlank() -> endTime.trim().removeSuffix(":00")
        else -> null
    }
