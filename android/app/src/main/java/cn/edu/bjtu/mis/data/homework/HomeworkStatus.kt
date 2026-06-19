package cn.edu.bjtu.mis.data.homework

import cn.edu.bjtu.mis.model.HomeworkItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

enum class HomeworkStatusKind(
    val code: String,
    val label: String,
) {
    Open("open", "未提交"),
    Done("done", "已提交"),
    ExpiredCanSubmit("expired_can_submit", "已过期，可补交"),
    ExpiredClosed("expired_closed", "已过期，不可补交"),
}

fun parseHomeworkDueAt(value: String?): LocalDateTime? {
    val normalized = value?.trim().orEmpty()
    if (normalized.isBlank()) return null
    return HomeworkDueAtFormatters.firstNotNullOfOrNull { formatter ->
        try {
            LocalDateTime.parse(normalized, formatter)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

fun homeworkDueDate(item: HomeworkItem): LocalDate? =
    parseHomeworkDueAt(item.dueAt)?.toLocalDate()

fun homeworkOpenDate(item: HomeworkItem): LocalDate? =
    parseHomeworkDueAt(item.openedAt)?.toLocalDate()

fun homeworkCalendarStatusLabel(
    item: HomeworkItem,
    now: LocalDateTime = LocalDateTime.now(),
): String =
    if (homeworkStatusKind(item, now) == HomeworkStatusKind.Done) "已提交" else "未提交"

fun homeworkStatusKind(
    item: HomeworkItem,
    now: LocalDateTime = LocalDateTime.now(),
): HomeworkStatusKind {
    if (!item.submittedAt.isNullOrBlank() || item.status.trim().lowercase(Locale.ROOT) == "done") {
        return HomeworkStatusKind.Done
    }

    val dueAt = parseHomeworkDueAt(item.dueAt)
    if (dueAt != null && !dueAt.isAfter(now)) {
        return if (item.canSubmit && item.canSubmitExplicit) {
            HomeworkStatusKind.ExpiredCanSubmit
        } else {
            HomeworkStatusKind.ExpiredClosed
        }
    }

    return HomeworkStatusKind.Open
}

fun homeworkMatchesStatusFilter(
    item: HomeworkItem,
    filter: String,
    now: LocalDateTime = LocalDateTime.now(),
): Boolean {
    val status = homeworkStatusKind(item, now)
    return when (filter) {
        "all" -> true
        HomeworkStatusKind.Open.code -> status == HomeworkStatusKind.Open
        HomeworkStatusKind.Done.code -> status == HomeworkStatusKind.Done
        "expired" -> status == HomeworkStatusKind.ExpiredCanSubmit || status == HomeworkStatusKind.ExpiredClosed
        HomeworkStatusKind.ExpiredCanSubmit.code -> status == HomeworkStatusKind.ExpiredCanSubmit
        HomeworkStatusKind.ExpiredClosed.code -> status == HomeworkStatusKind.ExpiredClosed
        else -> item.status == filter
    }
}

data class HomeworkIdentityKey(
    val homeworkId: Int?,
    val courseId: Int,
    val title: String,
    val openedAt: String?,
    val dueAt: String?,
)

fun homeworkIdentityKey(item: HomeworkItem): HomeworkIdentityKey =
    HomeworkIdentityKey(
        homeworkId = item.homeworkId,
        courseId = item.courseId,
        title = item.title.normalizedHomeworkIdentityText(),
        openedAt = item.openedAt.normalizedHomeworkIdentityText().ifBlank { null },
        dueAt = item.dueAt.normalizedHomeworkIdentityText().ifBlank { null },
    )

fun findHomeworkByIdentity(items: List<HomeworkItem>, key: HomeworkIdentityKey): HomeworkItem? =
    items.firstOrNull { candidate ->
        if (key.homeworkId != null) {
            candidate.homeworkId == key.homeworkId
        } else {
            candidate.courseId == key.courseId &&
                candidate.title.normalizedHomeworkIdentityText() == key.title &&
                candidate.openedAt.normalizedHomeworkIdentityText().ifBlank { null } == key.openedAt &&
                candidate.dueAt.normalizedHomeworkIdentityText().ifBlank { null } == key.dueAt
        }
    }

private fun String?.normalizedHomeworkIdentityText(): String =
    this?.trim().orEmpty()

private val HomeworkDueAtFormatters: List<DateTimeFormatter> = listOf(
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
)
