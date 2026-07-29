package cn.edu.bjtu.mis.ui

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

fun friendlyTimestamp(
    value: String?,
    now: ZonedDateTime = ZonedDateTime.now(),
    zoneId: ZoneId = now.zone,
): String {
    if (value.isNullOrBlank()) return "尚未同步"
    val parsed = parseTimestamp(value, zoneId) ?: return value
    val local = parsed.withZoneSameInstant(zoneId)
    val age = Duration.between(local, now.withZoneSameInstant(zoneId))
    val days = ChronoUnit.DAYS.between(local.toLocalDate(), now.toLocalDate())

    return when {
        !age.isNegative && age.toMinutes() < 1 -> "刚刚"
        !age.isNegative && age.toHours() < 1 -> "${age.toMinutes().coerceAtLeast(1)} 分钟前"
        days == 0L -> "今天 ${local.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        days == 1L -> "昨天 ${local.format(DateTimeFormatter.ofPattern("HH:mm"))}"
        local.year == now.year -> local.format(
            DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA),
        )
        else -> local.format(
            DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA),
        )
    }
}

private fun parseTimestamp(
    value: String,
    zoneId: ZoneId,
): ZonedDateTime? {
    val text = value.trim()
    return sequenceOf<() -> ZonedDateTime>(
        { Instant.parse(text).atZone(zoneId) },
        { OffsetDateTime.parse(text).toZonedDateTime() },
        { ZonedDateTime.parse(text) },
        { LocalDateTime.parse(text).atZone(zoneId) },
        { LocalDate.parse(text).atStartOfDay(zoneId) },
    ).firstNotNullOfOrNull { parser ->
        try {
            parser()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
