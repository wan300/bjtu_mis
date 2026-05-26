package cn.edu.bjtu.mis.widget

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.db.BjtuMisDao
import cn.edu.bjtu.mis.data.db.toCourseEntry
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.TimetableData
import kotlinx.serialization.decodeFromString
import java.time.LocalDate

class TimetableWidgetDataSource(
    private val dao: BjtuMisDao,
) {
    suspend fun load(today: LocalDate = LocalDate.now()): TimetableWidgetModel {
        val userCourses = dao.getUserCourses().map { it.toCourseEntry() }
        val snapshot = dao.getSnapshot(ModuleKeys.Timetable)
        val envelope = snapshot?.let {
            runCatching {
                AppJson.decodeFromString<ModuleEnvelope<TimetableData>>(it.payloadJson)
            }.getOrNull()
        }
        val calendar = loadCalendar()

        if (envelope == null && userCourses.isEmpty() && calendar == null) {
            return TimetableWidgetMapper.empty(today)
        }

        val merged = (envelope?.data ?: TimetableData()).copy(
            entries = envelope?.data?.entries.orEmpty() + userCourses,
        )
        return TimetableWidgetMapper.map(
            data = merged,
            currentWeek = calendar?.currentWeek?.let(::parseWeekNumber),
            today = today,
            calendar = calendar,
            hasTimetableCache = envelope != null || userCourses.isNotEmpty(),
        )
    }

    private suspend fun loadCalendar(): CalendarData? =
        dao.getSnapshot(ModuleKeys.Calendar)
            ?.payloadJson
            ?.let { payload ->
                runCatching {
                    AppJson.decodeFromString<ModuleEnvelope<CalendarData>>(payload)
                        .data
                }.getOrNull()
            }

    private fun parseWeekNumber(value: String): Int? =
        Regex("""\d+""").find(value)?.value?.toIntOrNull()
}
