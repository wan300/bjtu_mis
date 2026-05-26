package cn.edu.bjtu.mis.data.employment

import cn.edu.bjtu.mis.model.EmploymentInfoSummary
import cn.edu.bjtu.mis.model.EmploymentSectionType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class EmploymentCalendarEvent(
    val id: String,
    val type: EmploymentSectionType,
    val title: String,
    val date: LocalDate,
    val startTime: String? = null,
    val endTime: String? = null,
    val organization: String? = null,
    val location: String? = null,
    val url: String,
    val statusLabel: String? = null,
    val sortDateTime: LocalDateTime? = null,
)

fun employmentCalendarEvents(items: List<EmploymentInfoSummary>): List<EmploymentCalendarEvent> =
    items
        .asSequence()
        .mapNotNull { it.toEmploymentCalendarEventOrNull() }
        .distinctBy { "${it.type.name}:${it.id}" }
        .sortedWith(
            compareBy<EmploymentCalendarEvent> { it.date }
                .thenBy { it.sortDateTime ?: it.date.atStartOfDay() }
                .thenBy { it.title }
        )
        .toList()

fun EmploymentInfoSummary.toEmploymentCalendarEventOrNull(): EmploymentCalendarEvent? {
    if (type != EmploymentSectionType.CareerTalk && type != EmploymentSectionType.JobFair) return null
    val normalizedId = id.trim()
    val normalizedTitle = title.trim()
    if (normalizedId.isBlank() || normalizedTitle.isBlank()) return null

    val startDateTime = parseEmploymentEventDateTime(startTime)
    val endDateTime = parseEmploymentEventDateTime(endTime)
    val eventDateTime = startDateTime ?: endDateTime ?: return null

    return EmploymentCalendarEvent(
        id = normalizedId,
        type = type,
        title = normalizedTitle,
        date = eventDateTime.toLocalDate(),
        startTime = startTime?.trim()?.takeIf { it.isNotBlank() },
        endTime = endTime?.trim()?.takeIf { it.isNotBlank() },
        organization = organization?.trim()?.takeIf { it.isNotBlank() },
        location = location?.trim()?.takeIf { it.isNotBlank() },
        url = url.trim(),
        statusLabel = statusLabel?.trim()?.takeIf { it.isNotBlank() },
        sortDateTime = startDateTime ?: endDateTime,
    )
}

fun parseEmploymentEventDateTime(value: String?): LocalDateTime? {
    val text = value?.trim().orEmpty()
    if (text.isBlank()) return null

    EmploymentEventDateTimeFormatters.forEach { formatter ->
        runCatching { LocalDateTime.parse(text, formatter) }.getOrNull()?.let { return it }
    }
    runCatching { LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay() }
        .getOrNull()
        ?.let { return it }

    val datePrefix = text.takeIf { it.length >= 10 }?.take(10).orEmpty()
    return try {
        LocalDate.parse(datePrefix, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay()
    } catch (_: DateTimeParseException) {
        null
    }
}

fun employmentCalendarEventTypeLabel(type: EmploymentSectionType): String =
    when (type) {
        EmploymentSectionType.CareerTalk -> "宣讲会"
        EmploymentSectionType.JobFair -> "双选会"
        EmploymentSectionType.Recruitment -> "招聘信息"
        EmploymentSectionType.Internship -> "实习信息"
    }

private val EmploymentEventDateTimeFormatters = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE_TIME,
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
)
