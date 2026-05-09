package cn.edu.bjtu.mis.data.repository

import android.content.Context
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.db.BjtuMisDao
import cn.edu.bjtu.mis.data.db.ModuleSnapshotEntity
import cn.edu.bjtu.mis.data.db.SyncRunEntity
import cn.edu.bjtu.mis.data.db.encodeSummary
import cn.edu.bjtu.mis.data.db.toModel
import cn.edu.bjtu.mis.data.provider.AaProvider
import cn.edu.bjtu.mis.data.provider.SessionExpiredException
import cn.edu.bjtu.mis.data.provider.SessionManager
import cn.edu.bjtu.mis.data.provider.VeProvider
import cn.edu.bjtu.mis.model.AcademicProgressData
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.CourseResourcesData
import cn.edu.bjtu.mis.model.EmptyRoomData
import cn.edu.bjtu.mis.model.ExamData
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ModuleKeys
import cn.edu.bjtu.mis.model.ScoreData
import cn.edu.bjtu.mis.model.SessionCaptcha
import cn.edu.bjtu.mis.model.SessionStatus
import cn.edu.bjtu.mis.model.StudentProfileData
import cn.edu.bjtu.mis.model.SyncModuleSummary
import cn.edu.bjtu.mis.model.SyncRun
import cn.edu.bjtu.mis.model.TimetableData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset

class SessionRepository(
    private val sessionManager: SessionManager,
) {
    suspend fun status(): SessionStatus = sessionManager.validateSession()

    suspend fun captcha(): SessionCaptcha = sessionManager.fetchInlineLoginCaptcha()

    suspend fun login(loginName: String, password: String, captcha: String): SessionStatus =
        sessionManager.loginInline(loginName, password, captcha)

    fun logout() = sessionManager.logout()
}

class SyncRepository(
    @PublishedApi internal val dao: BjtuMisDao,
    private val sessionManager: SessionManager,
) {
    private val mutex = Mutex()

    suspend fun runSync(): SyncRun = mutex.withLock {
        val startedAt = nowIso()
        val runId = dao.insertSyncRun(SyncRunEntity(startedAt = startedAt, status = "running"))
        val summary = linkedMapOf<String, SyncModuleSummary>()
        val errors = mutableListOf<String>()

        try {
            sessionManager.withAuthenticatedClient { client ->
                val aa = AaProvider(client)
                val ve = VeProvider(client)

                val calendar = fetchAndStore(ModuleKeys.Calendar, summary, errors) { ve.fetchCalendar() }
                val currentWeek = calendar?.data?.currentWeek
                val currentTerm = calendar?.data?.currentTerm

                fetchAndStore(ModuleKeys.Profile, summary, errors) { aa.fetchStudentProfile() }
                fetchAndStore(ModuleKeys.Timetable, summary, errors) { aa.fetchTimetable() }
                fetchAndStore(ModuleKeys.Exams, summary, errors) { aa.fetchExams() }
                fetchAndStore(ModuleKeys.Scores, summary, errors) { aa.fetchScores(ctype = "lr") }
                fetchAndStore(ModuleKeys.HistoryScores, summary, errors) { aa.fetchHistoryScores() }
                fetchAndStore(ModuleKeys.AcademicProgress, summary, errors) { aa.fetchAcademicProgress() }
                fetchAndStore(ModuleKeys.Homework, summary, errors) { ve.fetchHomework(term = currentTerm) }
                fetchAndStore(ModuleKeys.CourseResources, summary, errors) { ve.fetchCourseResources(term = currentTerm) }
                fetchAndStore(ModuleKeys.EmptyRooms, summary, errors) { aa.fetchEmptyRooms(week = currentWeek ?: "8") }
            }
        } catch (error: SessionExpiredException) {
            finish(runId, "session_expired", summary, error.message)
            throw error
        } catch (error: Throwable) {
            finish(runId, "failed", summary, error.message)
            throw error
        }

        val status = if (errors.isEmpty()) "success" else "partial_failure"
        finish(runId, status, summary, errors.joinToString(" | ").takeIf { it.isNotBlank() })
        return latestStatus()
    }

    suspend fun latestStatus(): SyncRun =
        dao.getLatestSyncRun()?.toModel()
            ?: SyncRun(status = "idle")

    suspend fun snapshots(): List<ModuleSnapshotEntity> = dao.getSnapshots()

    @PublishedApi
    internal suspend inline fun <reified T> snapshot(moduleKey: String): ModuleEnvelope<T>? =
        dao.getSnapshot(moduleKey)?.payloadJson?.let { AppJson.decodeFromString<ModuleEnvelope<T>>(it) }

    private suspend inline fun <reified T> fetchAndStore(
        moduleKey: String,
        summary: MutableMap<String, SyncModuleSummary>,
        errors: MutableList<String>,
        crossinline fetcher: suspend () -> ModuleEnvelope<T>,
    ): ModuleEnvelope<T>? {
        return runCatching {
            val envelope = fetcher().withSyncedAt(nowIso())
            saveSnapshot(moduleKey, envelope)
            summary[moduleKey] = SyncModuleSummary(
                status = "success",
                coverage = envelope.coverage,
                items = itemCount(envelope.data),
            )
            envelope
        }.getOrElse { error ->
            summary[moduleKey] = SyncModuleSummary(status = "error", error = error.message)
            errors += "$moduleKey: ${error.message}"
            null
        }
    }

    @PublishedApi
    internal suspend inline fun <reified T> saveSnapshot(moduleKey: String, envelope: ModuleEnvelope<T>) {
        dao.saveSnapshot(
            ModuleSnapshotEntity(
                moduleKey = moduleKey,
                syncedAt = envelope.syncedAt ?: nowIso(),
                sourceSystem = envelope.sourceSystem,
                coverage = envelope.coverage.name.lowercase(),
                sourceParamsJson = envelope.sourceParams.toString(),
                payloadJson = AppJson.encodeToString(envelope),
            )
        )
    }

    private suspend fun finish(
        runId: Long,
        status: String,
        summary: Map<String, SyncModuleSummary>,
        errorText: String?,
    ) {
        dao.finishSyncRun(
            id = runId,
            finishedAt = nowIso(),
            status = status,
            moduleSummaryJson = encodeSummary(summary),
            errorText = errorText,
        )
    }

    private fun <T> ModuleEnvelope<T>.withSyncedAt(value: String): ModuleEnvelope<T> =
        copy(syncedAt = value)

    private fun itemCount(data: Any?): Int = when (data) {
        is TimetableData -> data.entries.size
        is ExamData -> data.items.size
        is ScoreData -> data.items.size
        is CalendarData -> data.items.size
        is HomeworkData -> data.items.size
        is CourseResourcesData -> data.resources.size
        is EmptyRoomData -> data.rooms.size
        is StudentProfileData -> data.fields.size
        is AcademicProgressData -> if (data.buckets.isNotEmpty()) data.buckets.size else data.courses.size
        else -> 0
    }
}

class ModuleRepository(
    private val syncRepository: SyncRepository,
    private val sessionManager: SessionManager,
) {
    suspend fun profile(): ModuleEnvelope<StudentProfileData> =
        snapshotOrFetch(ModuleKeys.Profile) { AaProvider(it).fetchStudentProfile() }

    suspend fun academicProgress(): ModuleEnvelope<AcademicProgressData> =
        snapshotOrFetch(ModuleKeys.AcademicProgress) { AaProvider(it).fetchAcademicProgress() }

    suspend fun historyScores(term: String? = null): ModuleEnvelope<ScoreData> =
        fetchLiveOrSnapshot(ModuleKeys.HistoryScores) { AaProvider(it).fetchHistoryScores(term) }

    suspend fun timetable(): ModuleEnvelope<TimetableData> =
        snapshotOrFetch(ModuleKeys.Timetable) { AaProvider(it).fetchTimetable() }

    suspend fun exams(term: String? = null): ModuleEnvelope<ExamData> =
        fetchLiveOrSnapshot(ModuleKeys.Exams) { AaProvider(it).fetchExams(term) }

    suspend fun scores(term: String? = null, ctype: String? = null): ModuleEnvelope<ScoreData> =
        fetchLiveOrSnapshot(ModuleKeys.Scores) { AaProvider(it).fetchScores(term, ctype) }

    suspend fun calendar(month: String? = null): ModuleEnvelope<CalendarData> =
        fetchLiveOrSnapshot(ModuleKeys.Calendar) { VeProvider(it).fetchCalendar(month) }

    suspend fun homework(status: String = "all"): ModuleEnvelope<HomeworkData> {
        val envelope = fetchLiveOrSnapshot(ModuleKeys.Homework) { VeProvider(it).fetchHomework() }
        if (status == "all") return envelope
        return envelope.copy(data = envelope.data.copy(items = envelope.data.items.filter { it.status == status }))
    }

    suspend fun emptyRooms(
        term: String? = null,
        week: String? = null,
        building: String? = null,
        room: String? = null,
    ): ModuleEnvelope<EmptyRoomData> =
        fetchLiveOrSnapshot(ModuleKeys.EmptyRooms) { AaProvider(it).fetchEmptyRooms(term, week, building, room) }

    suspend fun courseResources(
        term: String? = null,
        courseId: String? = null,
        folderId: String = "0",
        search: String? = null,
    ): ModuleEnvelope<CourseResourcesData> =
        fetchLiveOrSnapshot(ModuleKeys.CourseResources) {
            VeProvider(it).fetchCourseResources(term, courseId, folderId, search)
        }

    suspend fun snapshots(): List<ModuleSnapshotEntity> = syncRepository.snapshots()

    private suspend inline fun <reified T> snapshotOrFetch(
        moduleKey: String,
        crossinline fetcher: suspend (cn.edu.bjtu.mis.data.network.BjtuHttpClient) -> ModuleEnvelope<T>,
    ): ModuleEnvelope<T> =
        syncRepository.snapshot<T>(moduleKey) ?: fetchLiveOrSnapshot(moduleKey, fetcher)

    private suspend inline fun <reified T> fetchLiveOrSnapshot(
        moduleKey: String,
        crossinline fetcher: suspend (cn.edu.bjtu.mis.data.network.BjtuHttpClient) -> ModuleEnvelope<T>,
    ): ModuleEnvelope<T> {
        return runCatching {
            val envelope = sessionManager.withAuthenticatedClient { fetcher(it) }.copy(syncedAt = nowIso())
            syncRepository.saveSnapshot(moduleKey, envelope)
            envelope
        }.getOrElse {
            syncRepository.snapshot<T>(moduleKey)
                ?: throw it
        }
    }
}

class CourseResourceRepository(
    private val context: Context,
    private val moduleRepository: ModuleRepository,
    private val sessionManager: SessionManager,
) {
    suspend fun listing(
        term: String? = null,
        courseId: String? = null,
        folderId: String = "0",
        search: String? = null,
    ): ModuleEnvelope<CourseResourcesData> =
        moduleRepository.courseResources(term, courseId, folderId, search)

    suspend fun download(rpId: String, filename: String, extension: String? = null): File =
        sessionManager.withAuthenticatedClient { client ->
            val targetDir = File(context.filesDir, "downloads").apply { mkdirs() }
            val target = File(targetDir, safeDownloadFileName(filename, extension, rpId))
            VeProvider(client).downloadCourseResource(rpId, target).file
        }

    private fun safeDownloadFileName(filename: String, extension: String?, rpId: String): String {
        val safeName = filename
            .trim()
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .ifBlank { "resource-$rpId" }
        val safeExtension = extension
            ?.trim()
            ?.trimStart('.')
            ?.replace(Regex("""[^A-Za-z0-9]+"""), "_")
            ?.trim('_')
            .orEmpty()

        return if (safeExtension.isBlank() || safeName.endsWith(".$safeExtension", ignoreCase = true)) {
            safeName
        } else {
            "$safeName.$safeExtension"
        }
    }
}

@PublishedApi
internal fun nowIso(): String =
    OffsetDateTime.now(ZoneOffset.UTC).withNano(0).toString()
