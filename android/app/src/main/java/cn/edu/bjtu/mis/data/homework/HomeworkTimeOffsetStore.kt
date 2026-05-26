package cn.edu.bjtu.mis.data.homework

import android.content.Context
import java.time.Duration

class HomeworkTimeOffsetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var offset: Duration
        get() {
            val millis = preferences.getLong(KEY_OFFSET_MILLIS, 0L)
            return Duration.ofMillis(millis.coerceAtLeast(0L))
        }
        set(value) {
            val millis = value.toMillis().coerceAtLeast(0L)
            preferences.edit().putLong(KEY_OFFSET_MILLIS, millis).apply()
        }

    val isActive: Boolean get() = !offset.isZero

    fun clear() {
        offset = Duration.ZERO
    }

    private companion object {
        const val PREFERENCES_NAME = "homework_time_offset"
        const val KEY_OFFSET_MILLIS = "time_offset_millis"
    }
}
