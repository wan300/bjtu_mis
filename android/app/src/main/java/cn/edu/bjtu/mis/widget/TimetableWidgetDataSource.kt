package cn.edu.bjtu.mis.widget

import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.db.BjtuMisDao
import cn.edu.bjtu.mis.data.db.toCourseEntry
import cn.edu.bjtu.mis.data.db.toModel
import cn.edu.bjtu.mis.data.employment.EmploymentCalendarEvent
import cn.edu.bjtu.mis.data.employment.EmploymentCalendarSyncStore
import cn.edu.bjtu.mis.data.employment.employmentCalendarEvents
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.EmploymentConsultationData
import cn.edu.bjtu.mis.model.EmploymentInfoPage
import cn.edu.bjtu.mis.model.EmploymentInfoSummary
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.TimetableData
import cn.edu.bjtu.mis.model.UserTodoItem
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.flow.first
import java.time.LocalDate

class TimetableWidgetDataSource(
    private val dao: BjtuMisDao,
    private val employmentCalendarSyncStore: EmploymentCalendarSyncStore? = null,
) {
    suspend fun load(today: LocalDate = LocalDate.now()): TimetableWidgetModel {
        val userCourses = dao.getUserCourses().map { it.toCourseEntry() }
        val todos = loadTodos()
        val snapshot = dao.getSnapshot(ModuleKeys.Timetable)
        val envelope = snapshot?.let {
            runCatching {
                AppJson.decodeFromString<ModuleEnvelope<TimetableData>>(it.payloadJson)
            }.getOrNull()
        }
        val calendar = loadCalendar()
        val homework = loadHomework()
        val employmentEvents = if (employmentCalendarSyncStore?.enabled?.first() == true) {
            loadEmploymentCalendarEvents()
        } else {
            emptyList()
        }

        if (
            envelope == null &&
            userCourses.isEmpty() &&
            calendar == null &&
            homework.isEmpty() &&
            todos.isEmpty() &&
            employmentEvents.isEmpty()
        ) {
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
            homework = homework,
            todos = todos,
            employmentEvents = employmentEvents,
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

    private suspend fun loadHomework(): List<HomeworkItem> =
        dao.getSnapshot(ModuleKeys.Homework)
            ?.payloadJson
            ?.let { decodeSnapshot<HomeworkData>(it)?.items }
            ?: emptyList()

    private suspend fun loadTodos(): List<UserTodoItem> =
        dao.getUserTodos().map { it.toModel() }

    private suspend fun loadEmploymentCalendarEvents(): List<EmploymentCalendarEvent> {
        val snapshots = dao.getSnapshots()
        val summaries = buildList<EmploymentInfoSummary> {
            snapshots.firstOrNull { it.moduleKey == ModuleKeys.EmploymentConsultation }
                ?.payloadJson
                ?.let { decodeSnapshot<EmploymentConsultationData>(it) }
                ?.sections
                ?.flatMap { it.items }
                ?.let(::addAll)

            snapshots
                .asSequence()
                .filter { it.moduleKey.startsWith("${ModuleKeys.EmploymentConsultation}:page:") }
                .mapNotNull { decodeSnapshot<EmploymentInfoPage>(it.payloadJson) }
                .flatMap { it.items.asSequence() }
                .forEach(::add)
        }
        return employmentCalendarEvents(summaries)
    }

    private inline fun <reified T> decodeSnapshot(payload: String): T? =
        runCatching {
            AppJson.decodeFromString<ModuleEnvelope<T>>(payload).data
        }.getOrNull()

    private fun parseWeekNumber(value: String): Int? =
        Regex("""\d+""").find(value)?.value?.toIntOrNull()
}
