package cn.edu.bjtu.mis.data.update

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appUpdatePreferencesDataStore by preferencesDataStore("app_update_preferences")

data class AppUpdatePromptPreference(
    val ignoredVersion: String? = null,
    val autoPromptDisabled: Boolean = false,
) {
    fun shouldPromptForUpdate(update: AppUpdateInfo): Boolean =
        !autoPromptDisabled && ignoredVersion != update.latestVersion
}

class AppUpdatePreferenceStore(
    context: Context,
) {
    private val dataStore = context.applicationContext.appUpdatePreferencesDataStore

    val preference: Flow<AppUpdatePromptPreference> = dataStore.data.map { prefs ->
        AppUpdatePromptPreference(
            ignoredVersion = prefs[IGNORED_VERSION]?.takeIf { it.isNotBlank() },
            autoPromptDisabled = prefs[AUTO_PROMPT_DISABLED] ?: false,
        )
    }

    suspend fun snapshot(): AppUpdatePromptPreference = preference.first()

    suspend fun ignoreVersion(version: String) {
        val normalized = SemanticVersion.parse(version)?.toString() ?: version.trim()
        if (normalized.isBlank()) return
        dataStore.edit { prefs ->
            prefs[IGNORED_VERSION] = normalized
        }
    }

    suspend fun disableAutoPrompts() {
        dataStore.edit { prefs ->
            prefs[AUTO_PROMPT_DISABLED] = true
        }
    }

    suspend fun enableAutoPrompts() {
        dataStore.edit { prefs ->
            prefs[AUTO_PROMPT_DISABLED] = false
        }
    }

    private companion object {
        val IGNORED_VERSION = stringPreferencesKey("ignored_version")
        val AUTO_PROMPT_DISABLED = booleanPreferencesKey("auto_prompt_disabled")
    }
}
