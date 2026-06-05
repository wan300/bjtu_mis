package cn.edu.bjtu.mis.data.homework

import cn.edu.bjtu.mis.model.HomeworkItem
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class HomeworkReminderCandidate(
    val item: HomeworkItem,
    val dueAt: LocalDateTime,
    val remaining: Duration,
    val urgency: HomeworkReminderUrgency,
)

data class HomeworkReminderContent(
    val reminderDate: LocalDate,
    val candidates: List<HomeworkReminderCandidate>,
    val title: String,
    val contentText: String,
    val bigText: String,
)

enum class HomeworkReminderUrgency {
    Normal,
    Urgent,
}

data class HomeworkReminderConfig(
    val enabled: Boolean = true,
    val normalWindow: Duration = Duration.ofHours(72),
    val urgentWindow: Duration = Duration.ofHours(24),
    val maxDisplayedItems: Int = 3,
) {
    init {
        require(!normalWindow.isNegative && !normalWindow.isZero) { "normalWindow must be positive" }
        require(!urgentWindow.isNegative) { "urgentWindow must not be negative" }
        require(normalWindow > urgentWindow) { "normalWindow must be greater than urgentWindow" }
        require(maxDisplayedItems > 0) { "maxDisplayedItems must be positive" }
    }
}

class HomeworkReminderEvaluator(
    private val config: HomeworkReminderConfig = HomeworkReminderConfig(),
) {
    fun evaluate(
        items: List<HomeworkItem>,
        now: LocalDateTime = LocalDateTime.now(),
        lastReminderDate: LocalDate? = null,
    ): HomeworkReminderContent? {
        if (!config.enabled) return null

        val today = now.toLocalDate()
        if (lastReminderDate == today) return null

        val candidates = items
            .mapNotNull { candidateFor(it, now) }
            .sortedBy { it.dueAt }
        if (candidates.isEmpty()) return null

        val displayLines = buildDisplayLines(candidates)
        return HomeworkReminderContent(
            reminderDate = today,
            candidates = candidates,
            title = "你有 ${candidates.size} 项作业即将截止",
            contentText = displayLines.first(),
            bigText = displayLines.joinToString("\n"),
        )
    }

    fun parseDueAt(value: String?): LocalDateTime? =
        parseHomeworkDueAt(value)

    private fun candidateFor(item: HomeworkItem, now: LocalDateTime): HomeworkReminderCandidate? {
        if (homeworkStatusKind(item, now) != HomeworkStatusKind.Open) return null

        val dueAt = parseDueAt(item.dueAt) ?: return null
        val remaining = Duration.between(now, dueAt)
        if (remaining.isNegative || remaining > config.normalWindow) return null

        val urgency = if (remaining <= config.urgentWindow) {
            HomeworkReminderUrgency.Urgent
        } else {
            HomeworkReminderUrgency.Normal
        }
        return HomeworkReminderCandidate(item = item, dueAt = dueAt, remaining = remaining, urgency = urgency)
    }

    private fun buildDisplayLines(candidates: List<HomeworkReminderCandidate>): List<String> {
        val displayed = candidates.take(config.maxDisplayedItems.coerceAtLeast(1))
        val lines = displayed.map {
            val prefix = if (it.urgency == HomeworkReminderUrgency.Urgent) "【紧急】" else ""
            "$prefix${it.item.course}: ${it.item.title}（截止 ${DueAtLabelFormatter.format(it.dueAt)}）"
        }.toMutableList()
        if (candidates.size > displayed.size) {
            lines += "等 ${candidates.size} 项作业即将截止"
        }
        return lines
    }

    private companion object {
        val DueAtLabelFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")
    }
}

interface HomeworkReminderState {
    fun lastReminderDate(): LocalDate?
    fun markReminderSent(date: LocalDate)
}

interface HomeworkReminderPreferences {
    fun config(): HomeworkReminderConfig
    fun saveConfig(config: HomeworkReminderConfig)
}

fun interface HomeworkReminderNotificationSender {
    fun send(content: HomeworkReminderContent): Boolean
}

class HomeworkReminderCoordinator(
    private val state: HomeworkReminderState,
    private val sender: HomeworkReminderNotificationSender,
    private val evaluator: HomeworkReminderEvaluator? = null,
    private val configProvider: () -> HomeworkReminderConfig = { HomeworkReminderConfig() },
) {
    fun maybeSend(
        items: List<HomeworkItem>,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        val activeEvaluator = evaluator ?: HomeworkReminderEvaluator(configProvider())
        val content = activeEvaluator.evaluate(
            items = items,
            now = now,
            lastReminderDate = state.lastReminderDate(),
        ) ?: return false
        if (!sender.send(content)) return false
        state.markReminderSent(content.reminderDate)
        return true
    }
}
