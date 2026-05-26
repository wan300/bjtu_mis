package cn.edu.bjtu.mis.data.employment

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.employmentCalendarSyncDataStore by preferencesDataStore("employment_calendar_sync")

class EmploymentCalendarSyncStore(
    context: Context,
) {
    private val dataStore = context.applicationContext.employmentCalendarSyncDataStore

    val enabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[ENABLED] ?: false
    }

    suspend fun save(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[ENABLED] = enabled
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
    }
}
