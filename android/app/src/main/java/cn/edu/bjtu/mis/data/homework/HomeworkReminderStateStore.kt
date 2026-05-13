package cn.edu.bjtu.mis.data.homework

import android.content.Context
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
