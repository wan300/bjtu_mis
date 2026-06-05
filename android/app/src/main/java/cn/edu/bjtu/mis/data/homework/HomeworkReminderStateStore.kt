package cn.edu.bjtu.mis.data.homework

import android.content.Context
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException

class HomeworkReminderStateStore(
    context: Context,
) : HomeworkReminderState {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun lastReminderDate(): LocalDate? {
        val value = preferences.getString(KEY_LAST_REMINDER_DATE, null) ?: return null
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    override fun markReminderSent(date: LocalDate) {
        preferences.edit()
            .putString(KEY_LAST_REMINDER_DATE, date.toString())
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "homework_reminders"
        const val KEY_LAST_REMINDER_DATE = "last_homework_reminder_date"
    }
}

class HomeworkReminderPreferenceStore(
    context: Context,
) : HomeworkReminderPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun config(): HomeworkReminderConfig {
        val enabled = preferences.getBoolean(KEY_ENABLED, true)
        val normalHours = preferences.getLong(KEY_NORMAL_HOURS, DEFAULT_NORMAL_HOURS)
        val urgentHours = preferences.getLong(KEY_URGENT_HOURS, DEFAULT_URGENT_HOURS)
        val maxDisplayed = preferences.getInt(KEY_MAX_DISPLAYED, DEFAULT_MAX_DISPLAYED)
        return sanitizeConfig(enabled, normalHours, urgentHours, maxDisplayed)
    }

    override fun saveConfig(config: HomeworkReminderConfig) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putLong(KEY_NORMAL_HOURS, config.normalWindow.toHours())
            .putLong(KEY_URGENT_HOURS, config.urgentWindow.toHours())
            .putInt(KEY_MAX_DISPLAYED, config.maxDisplayedItems)
            .apply()
    }

    private fun sanitizeConfig(
        enabled: Boolean,
        normalHours: Long,
        urgentHours: Long,
        maxDisplayed: Int,
    ): HomeworkReminderConfig {
        val safeUrgent = urgentHours.coerceAtLeast(0)
        val safeNormal = normalHours.coerceAtLeast(safeUrgent + 1)
        val safeMaxDisplayed = maxDisplayed.coerceAtLeast(1)
        return HomeworkReminderConfig(
            enabled = enabled,
            normalWindow = Duration.ofHours(safeNormal),
            urgentWindow = Duration.ofHours(safeUrgent),
            maxDisplayedItems = safeMaxDisplayed,
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "homework_reminders"
        const val KEY_ENABLED = "enabled"
        const val KEY_NORMAL_HOURS = "normal_threshold_hours"
        const val KEY_URGENT_HOURS = "urgent_threshold_hours"
        const val KEY_MAX_DISPLAYED = "max_displayed_items"
        const val DEFAULT_NORMAL_HOURS = 72L
        const val DEFAULT_URGENT_HOURS = 24L
        const val DEFAULT_MAX_DISPLAYED = 3
    }
}
