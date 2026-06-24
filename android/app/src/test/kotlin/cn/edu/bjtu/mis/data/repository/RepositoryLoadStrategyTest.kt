package cn.edu.bjtu.mis.data.repository

import android.content.ContextWrapper
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.data.captcha.CaptchaAnswerSolver
import cn.edu.bjtu.mis.data.captcha.CaptchaSolveResult
import cn.edu.bjtu.mis.data.db.BjtuMisDao
import cn.edu.bjtu.mis.data.db.MailFolderEntity
import cn.edu.bjtu.mis.data.db.MailMessageSummaryEntity
import cn.edu.bjtu.mis.data.db.ModuleSnapshotEntity
import cn.edu.bjtu.mis.data.db.ModuleUpdateSummaryEntity
import cn.edu.bjtu.mis.data.db.SyncRunEntity
import cn.edu.bjtu.mis.data.db.UserCourseEntity
import cn.edu.bjtu.mis.data.db.UserTodoEntity
import cn.edu.bjtu.mis.data.network.AppCookieJar
import cn.edu.bjtu.mis.data.network.BjtuHttpClient
import cn.edu.bjtu.mis.data.provider.SessionEndpoints
import cn.edu.bjtu.mis.data.provider.SessionManager
import cn.edu.bjtu.mis.data.security.CredentialStore
import cn.edu.bjtu.mis.data.security.LoginCredentials
import cn.edu.bjtu.mis.data.security.SessionCookieStore
import cn.edu.bjtu.mis.model.CalendarData
import cn.edu.bjtu.mis.model.CoverageLevel
import cn.edu.bjtu.mis.model.HomeworkData
import cn.edu.bjtu.mis.model.HomeworkItem
import cn.edu.bjtu.mis.model.MailFoldersData
import cn.edu.bjtu.mis.model.MailMessagesData
import cn.edu.bjtu.mis.model.ModuleEnvelope
import cn.edu.bjtu.mis.model.ModuleKeys
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryLoadStrategyTest {
    @Test
    fun saveSnapshotStoresAndReplacesUpdateSummary() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeBjtuMisDao()
            val syncRepository = SyncRepository(dao, sessionManager(server))
            val baseline = homeworkEnvelope(homework(1, "Lab", dueAt = "2026-06-30 23:59"))
            val changed = homeworkEnvelope(homework(1, "Lab", dueAt = "2026-07-01 23:59"))
            val unchanged = homeworkEnvelope(homework(1, "Lab", dueAt = "2026-07-01 23:59"))

            syncRepository.saveSnapshot(ModuleKeys.Homework, baseline)
            assertTrue(syncRepository.updateSummaries().single().items.isEmpty())

            syncRepository.saveSnapshot(ModuleKeys.Homework, changed)
            val changedSummary = syncRepository.updateSummaries().single()
            assertEquals(ModuleKeys.Homework, changedSummary.moduleKey)
            assertEquals(listOf("modified"), changedSummary.items.map { it.changeType })

            syncRepository.saveSnapshot(ModuleKeys.Homework, unchanged)
            assertTrue(syncRepository.updateSummaries().single().items.isEmpty())
        }
    }

    @Test
    fun cacheOnlyReturnsModuleSnapshotWithoutNetwork() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeBjtuMisDao()
            val syncRepository = SyncRepository(dao, sessionManager(server))
            syncRepository.saveSnapshot(
                ModuleKeys.Calendar,
                ModuleEnvelope(
                    module = ModuleKeys.Calendar,
                    sourceSystem = "test",
                    coverage = CoverageLevel.Verified,
                    data = CalendarData(month = "2026-06", currentWeek = "8"),
                ),
            )

            val repository = ModuleRepository(syncRepository, sessionManager(server))
            val envelope = repository.calendar(strategy = ModuleLoadStrategy.CacheOnly)

            assertEquals("8", envelope.data.currentWeek)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun cacheOnlyMissingModuleSnapshotDoesNotUseNetwork() = runBlocking {
        MockWebServer().use { server ->
            val syncRepository = SyncRepository(FakeBjtuMisDao(), sessionManager(server))
            val repository = ModuleRepository(syncRepository, sessionManager(server))

            val error = runCatching {
                repository.calendar(strategy = ModuleLoadStrategy.CacheOnly)
            }.exceptionOrNull()

            assertTrue(error is LocalSnapshotMissingException)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun progressiveCacheOnlyEmitsCachedStateOnly() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeBjtuMisDao()
            val syncRepository = SyncRepository(dao, sessionManager(server))
            syncRepository.saveSnapshot(
                ModuleKeys.Homework,
                ModuleEnvelope(
                    module = ModuleKeys.Homework,
                    sourceSystem = "test",
                    coverage = CoverageLevel.Verified,
                    data = HomeworkData(),
                ),
            )

            val repository = ModuleRepository(syncRepository, sessionManager(server))
            val states = repository.homeworkProgressive(strategy = ModuleLoadStrategy.CacheOnly).toList()

            assertEquals(1, states.size)
            assertTrue(states.single().complete)
            assertTrue(states.single().fromCache)
            assertFalse(states.single().loading)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun mailCacheOnlyReadsCachedFoldersAndMessagesWithoutNetwork() = runBlocking {
        MockWebServer().use { server ->
            val dao = FakeBjtuMisDao()
            dao.saveMailFolders(
                listOf(
                    MailFolderEntity(
                        folderId = "1",
                        name = "Inbox",
                        messageCount = 1,
                        unreadCount = 1,
                        syncedAt = "2026-06-16T00:00:00Z",
                    ),
                ),
            )
            dao.saveMailMessageSummaries(
                listOf(
                    MailMessageSummaryEntity(
                        messageId = "m1",
                        folderId = "1",
                        subject = "Cached mail",
                        fromText = "sender",
                        syncedAt = "2026-06-16T00:00:00Z",
                    ),
                ),
            )

            val repository = MailRepository(
                context = ContextWrapper(null),
                dao = dao,
                sessionManager = sessionManager(server),
            )

            val folders = repository.folders(ModuleLoadStrategy.CacheOnly)
            val messages = repository.messages(strategy = ModuleLoadStrategy.CacheOnly)

            assertEquals(1, folders.data.folders.size)
            assertEquals("Cached mail", messages.data.messages.single().subject)
            assertEquals(0, server.requestCount)
        }
    }

    private fun sessionManager(server: MockWebServer): SessionManager {
        val base = server.url("/").toString().trimEnd('/')
        val cookieJar = AppCookieJar()
        return SessionManager(
            cookieStore = MemoryCookieStore(),
            credentialStore = MemoryCredentialStore(),
            cookieJar = cookieJar,
            httpClient = BjtuHttpClient(cookieJar),
            captchaSolver = FakeCaptchaSolver(),
            endpoints = SessionEndpoints(
                misHomeUrl = "$base/home/",
                misAaBridgeUrl = "$base/module/module/10/",
                aaTimetableUrl = "$base/aa/timetable/",
                bksyVeBridgeUrl = "$base/ve/bridge/",
                casOrigin = base,
                misReferer = "$base/",
                bksyReferer = "$base/",
            ),
        )
    }

    private class MemoryCookieStore : SessionCookieStore {
        private var payload: String? = null

        override fun save(plainText: String) {
            payload = plainText
        }

        override fun load(): String? = payload

        override fun clear() {
            payload = null
        }
    }

    private class MemoryCredentialStore : CredentialStore {
        private var credentials: LoginCredentials? = null

        override fun save(credentials: LoginCredentials) {
            this.credentials = credentials
        }

        override fun load(): LoginCredentials? = credentials

        override fun clear() {
            credentials = null
        }
    }

    private class FakeCaptchaSolver : CaptchaAnswerSolver {
        override fun solve(imageBytes: ByteArray): CaptchaSolveResult =
            CaptchaSolveResult(expression = "1+1=", answer = "2")
    }

    private fun homeworkEnvelope(item: HomeworkItem): ModuleEnvelope<HomeworkData> =
        ModuleEnvelope(
            module = ModuleKeys.Homework,
            syncedAt = "2026-06-23T10:00:00Z",
            sourceSystem = "test",
            coverage = CoverageLevel.Verified,
            data = HomeworkData(currentTerm = "2025-2026-2-2", items = listOf(item)),
            sourceParams = buildJsonObject {
                put("term", "2025-2026-2-2")
            },
        )

    private fun homework(id: Int, title: String, dueAt: String): HomeworkItem =
        HomeworkItem(
            homeworkId = id,
            course = "Software",
            courseId = 10,
            title = title,
            openedAt = "2026-06-20 08:00",
            dueAt = dueAt,
            status = "open",
            subType = 0,
        )

    private class FakeBjtuMisDao : BjtuMisDao {
        private val snapshots = linkedMapOf<String, ModuleSnapshotEntity>()
        private val updateSummaries = linkedMapOf<String, ModuleUpdateSummaryEntity>()
        private val mailFolders = linkedMapOf<String, MailFolderEntity>()
        private val mailMessages = linkedMapOf<String, MailMessageSummaryEntity>()
        private val userCourses = linkedMapOf<Long, UserCourseEntity>()
        private val userTodos = linkedMapOf<Long, UserTodoEntity>()
        private var nextSyncRunId = 1L
        private var latestSyncRun: SyncRunEntity? = null

        override suspend fun insertSyncRun(entity: SyncRunEntity): Long {
            val id = nextSyncRunId++
            latestSyncRun = entity.copy(id = id)
            return id
        }

        override suspend fun finishSyncRun(
            id: Long,
            finishedAt: String,
            status: String,
            moduleSummaryJson: String,
            errorText: String?,
        ) {
            latestSyncRun = latestSyncRun?.copy(
                id = id,
                finishedAt = finishedAt,
                status = status,
                moduleSummaryJson = moduleSummaryJson,
                errorText = errorText,
            )
        }

        override suspend fun saveSnapshot(entity: ModuleSnapshotEntity) {
            snapshots[entity.moduleKey] = entity
        }

        override suspend fun getSnapshot(moduleKey: String): ModuleSnapshotEntity? = snapshots[moduleKey]

        override suspend fun getSnapshots(): List<ModuleSnapshotEntity> = snapshots.values.toList()

        override suspend fun saveModuleUpdateSummary(entity: ModuleUpdateSummaryEntity) {
            updateSummaries[entity.moduleKey] = entity
        }

        override suspend fun getModuleUpdateSummary(moduleKey: String): ModuleUpdateSummaryEntity? =
            updateSummaries[moduleKey]

        override suspend fun getModuleUpdateSummaries(): List<ModuleUpdateSummaryEntity> =
            updateSummaries.values.toList()

        override suspend fun saveUserCourse(entity: UserCourseEntity): Long {
            val id = entity.id.takeIf { it != 0L } ?: ((userCourses.keys.maxOrNull() ?: 0L) + 1L)
            userCourses[id] = entity.copy(id = id)
            return id
        }

        override suspend fun getUserCourse(id: Long): UserCourseEntity? = userCourses[id]

        override suspend fun getUserCourses(): List<UserCourseEntity> = userCourses.values.toList()

        override suspend fun deleteUserCourse(id: Long) {
            userCourses.remove(id)
        }

        override suspend fun saveUserTodo(entity: UserTodoEntity): Long {
            val id = entity.id.takeIf { it != 0L } ?: ((userTodos.keys.maxOrNull() ?: 0L) + 1L)
            userTodos[id] = entity.copy(id = id)
            return id
        }

        override suspend fun getUserTodo(id: Long): UserTodoEntity? = userTodos[id]

        override suspend fun getUserTodos(): List<UserTodoEntity> = userTodos.values.toList()

        override suspend fun setUserTodoDone(id: Long, done: Boolean, updatedAt: String) {
            userTodos[id]?.let { userTodos[id] = it.copy(done = done, updatedAt = updatedAt) }
        }

        override suspend fun deleteUserTodo(id: Long) {
            userTodos.remove(id)
        }

        override suspend fun saveMailFolders(folders: List<MailFolderEntity>) {
            folders.forEach { mailFolders[it.folderId] = it }
        }

        override suspend fun getMailFolders(): List<MailFolderEntity> =
            mailFolders.values.sortedWith(compareByDescending<MailFolderEntity> { it.system }.thenBy { it.folderId })

        override suspend fun clearMailFolders() {
            mailFolders.clear()
        }

        override suspend fun saveMailMessageSummaries(messages: List<MailMessageSummaryEntity>) {
            messages.forEach { mailMessages[it.messageId] = it }
        }

        override suspend fun clearMailMessageSummaries(folderId: String) {
            mailMessages.values.removeAll { it.folderId == folderId }
        }

        override suspend fun deleteMailMessageSummaries(messageIds: List<String>) {
            messageIds.forEach { mailMessages.remove(it) }
        }

        override suspend fun getMailMessageSummariesByIds(messageIds: List<String>): List<MailMessageSummaryEntity> =
            messageIds.mapNotNull { mailMessages[it] }

        override suspend fun markMailMessageSummariesRead(messageIds: List<String>) {
            messageIds.forEach { id ->
                mailMessages[id]?.let { mailMessages[id] = it.copy(read = true) }
            }
        }

        override suspend fun decrementMailFolderUnreadCount(folderId: String, delta: Int) {
            mailFolders[folderId]?.let {
                mailFolders[folderId] = it.copy(unreadCount = (it.unreadCount - delta).coerceAtLeast(0))
            }
        }

        override suspend fun getMailMessageSummaries(folderId: String, start: Int, limit: Int): List<MailMessageSummaryEntity> =
            mailMessages.values
                .filter { it.folderId == folderId }
                .sortedWith(compareByDescending<MailMessageSummaryEntity> {
                    it.receivedAt ?: it.sentAt ?: it.modifiedAt ?: ""
                }.thenByDescending { it.messageId })
                .drop(start)
                .take(limit)

        override suspend fun countMailMessageSummaries(folderId: String): Int =
            mailMessages.values.count { it.folderId == folderId }

        override suspend fun getLatestSyncRun(): SyncRunEntity? = latestSyncRun

        override suspend fun clearSnapshots() {
            snapshots.clear()
        }

        override suspend fun clearSyncRuns() {
            latestSyncRun = null
        }
    }
}
