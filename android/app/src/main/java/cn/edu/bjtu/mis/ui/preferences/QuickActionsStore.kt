package cn.edu.bjtu.mis.ui.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.model.ModuleKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.IOException

private val Context.quickActionsDataStore by preferencesDataStore("quick_actions")

const val ServicesQuickActionRoute = "services"
const val MinimumQuickActions = 1
const val MaximumQuickActions = 8

val DefaultQuickActionRoutes: List<String> = listOf(
    ModuleKeys.Homework,
    ModuleKeys.Timetable,
    ModuleKeys.Mail,
    ModuleKeys.Scores,
    ModuleKeys.Calendar,
    ServicesQuickActionRoute,
)

fun normalizeQuickActionRoutes(
    routes: Iterable<String>,
    validRoutes: Set<String>,
    fallback: List<String> = DefaultQuickActionRoutes,
): List<String> {
    fun normalized(source: Iterable<String>): List<String> =
        source.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter(validRoutes::contains)
            .distinct()
            .take(MaximumQuickActions)
            .toList()

    return normalized(routes).ifEmpty {
        normalized(fallback).ifEmpty {
            validRoutes.asSequence().sorted().take(MinimumQuickActions).toList()
        }
    }
}

class QuickActionsStore(
    context: Context,
) {
    private val dataStore = context.applicationContext.quickActionsDataStore

    val rawRoutes: Flow<List<String>> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            decodeRoutes(preferences[ROUTES])
        }

    suspend fun save(
        routes: Iterable<String>,
        validRoutes: Set<String>,
    ) {
        val normalized = normalizeQuickActionRoutes(routes, validRoutes)
        dataStore.edit { preferences ->
            preferences[ROUTES] = AppJson.encodeToString(normalized)
        }
    }

    private companion object {
        val ROUTES = stringPreferencesKey("routes")

        fun decodeRoutes(value: String?): List<String> {
            if (value.isNullOrBlank()) return DefaultQuickActionRoutes
            return runCatching {
                AppJson.decodeFromString<List<String>>(value)
            }.getOrDefault(DefaultQuickActionRoutes)
        }
    }
}
