package cn.edu.bjtu.mis.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appThemeDataStore by preferencesDataStore("app_theme")

class AppThemeStore(
    context: Context,
) {
    private val dataStore = context.applicationContext.appThemeDataStore

    val theme: Flow<AppThemeOption> = dataStore.data.map { prefs ->
        AppThemeOption.fromStorageValue(prefs[THEME])
    }

    suspend fun save(theme: AppThemeOption) {
        dataStore.edit { prefs ->
            prefs[THEME] = theme.storageValue
        }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
    }
}
