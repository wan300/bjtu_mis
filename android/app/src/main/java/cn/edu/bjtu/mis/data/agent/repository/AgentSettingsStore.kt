package cn.edu.bjtu.mis.data.agent.repository

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.edu.bjtu.mis.data.agent.model.AgentSettings
import cn.edu.bjtu.mis.data.agent.model.SearchProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.agentSettingsDataStore by preferencesDataStore("agent_settings")

class AgentSettingsStore(
    context: Context,
) {
    private val dataStore = context.applicationContext.agentSettingsDataStore

    val settings: Flow<AgentSettings> = dataStore.data.map { prefs ->
        AgentSettings(
            baseUrl = prefs[BASE_URL]?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: AgentSettings().baseUrl,
            textModel = prefs[TEXT_MODEL]?.takeIf { it.isNotBlank() } ?: AgentSettings().textModel,
            visionModel = prefs[VISION_MODEL]?.takeIf { it.isNotBlank() },
            requestTimeoutSeconds = prefs[TIMEOUT_SECONDS] ?: AgentSettings().requestTimeoutSeconds,
            temperature = prefs[TEMPERATURE] ?: AgentSettings().temperature,
            searchProvider = SearchProviderType.entries.firstOrNull { it.name == prefs[SEARCH_PROVIDER] }
                ?: SearchProviderType.DuckDuckGoHtml,
            maxWorkspaceBytes = prefs[MAX_WORKSPACE_BYTES] ?: AgentSettings().maxWorkspaceBytes,
            maxSteps = prefs[MAX_STEPS] ?: AgentSettings().maxSteps,
        )
    }

    suspend fun save(settings: AgentSettings) {
        dataStore.edit { prefs ->
            prefs[BASE_URL] = settings.baseUrl.trimEnd('/').ifBlank { AgentSettings().baseUrl }
            prefs[TEXT_MODEL] = settings.textModel.ifBlank { AgentSettings().textModel }
            prefs[VISION_MODEL] = settings.visionModel.orEmpty()
            prefs[TIMEOUT_SECONDS] = settings.requestTimeoutSeconds.coerceIn(10, 300)
            prefs[TEMPERATURE] = settings.temperature.coerceIn(0.0, 2.0)
            prefs[SEARCH_PROVIDER] = settings.searchProvider.name
            prefs[MAX_WORKSPACE_BYTES] = settings.maxWorkspaceBytes.coerceAtLeast(16L * 1024L * 1024L)
            prefs[MAX_STEPS] = settings.maxSteps.coerceIn(1, 60)
        }
    }

    private companion object {
        val BASE_URL = stringPreferencesKey("base_url")
        val TEXT_MODEL = stringPreferencesKey("text_model")
        val VISION_MODEL = stringPreferencesKey("vision_model")
        val TIMEOUT_SECONDS = intPreferencesKey("timeout_seconds")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val SEARCH_PROVIDER = stringPreferencesKey("search_provider")
        val MAX_WORKSPACE_BYTES = longPreferencesKey("max_workspace_bytes")
        val MAX_STEPS = intPreferencesKey("max_steps")
    }
}
