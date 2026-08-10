package cn.edu.bjtu.mis.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.appThemeDataStore by preferencesDataStore("app_theme")

class AppThemeStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.appThemeDataStore
    private val missingStyleDefault: AppUiStyle by lazy {
        resolveInitialUiStyle(
            firstInstallTime = runCatching {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).firstInstallTime
            }.getOrNull(),
            lastUpdateTime = runCatching {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime
            }.getOrNull(),
        )
    }

    val appearance: Flow<AppAppearancePreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            AppAppearancePreferences(
                theme = AppThemeOption.fromStorageValue(preferences[THEME]),
                uiStyle = preferences[UI_STYLE]
                    ?.let(AppUiStyle::fromStorageValue)
                    ?: missingStyleDefault,
                reduceMotionOverride = AppEffectOverride.fromStorageValue(
                    preferences[REDUCE_MOTION],
                ),
                reduceTransparencyOverride = AppEffectOverride.fromStorageValue(
                    preferences[REDUCE_TRANSPARENCY],
                ),
            )
        }

    val theme: Flow<AppThemeOption> = appearance.map { it.theme }

    suspend fun initialize(): AppAppearancePreferences {
        return runCatching {
            dataStore.edit { preferences ->
                if (preferences[UI_STYLE] == null) {
                    preferences[UI_STYLE] = missingStyleDefault.storageValue
                }
            }
            appearance.first()
        }.getOrElse {
            AppAppearancePreferences(
                theme = AppThemeOption.Default,
                uiStyle = AppUiStyle.Classic,
            )
        }
    }

    suspend fun save(theme: AppThemeOption) {
        saveTheme(theme)
    }

    suspend fun saveTheme(theme: AppThemeOption) {
        dataStore.edit { prefs ->
            prefs[THEME] = theme.storageValue
        }
    }

    suspend fun saveUiStyle(style: AppUiStyle) {
        dataStore.edit { preferences ->
            preferences[UI_STYLE] = style.storageValue
        }
    }

    suspend fun saveReduceMotionOverride(value: AppEffectOverride) {
        dataStore.edit { preferences ->
            preferences[REDUCE_MOTION] = value.storageValue
        }
    }

    suspend fun saveReduceTransparencyOverride(value: AppEffectOverride) {
        dataStore.edit { preferences ->
            preferences[REDUCE_TRANSPARENCY] = value.storageValue
        }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val UI_STYLE = stringPreferencesKey("ui_style")
        val REDUCE_MOTION = stringPreferencesKey("reduce_motion")
        val REDUCE_TRANSPARENCY = stringPreferencesKey("reduce_transparency")
    }
}
