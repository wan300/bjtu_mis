package cn.edu.bjtu.mis.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.ColumnInfo
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.model.CourseEntry
import cn.edu.bjtu.mis.model.MailFolder
import cn.edu.bjtu.mis.model.MailMessageSummary
import cn.edu.bjtu.mis.model.SyncModuleSummary
import cn.edu.bjtu.mis.model.SyncRun
import cn.edu.bjtu.mis.model.UserTodoItem
import cn.edu.bjtu.mis.model.formatUserCourseWeeks
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceDao
import cn.edu.bjtu.mis.data.thirdparty.ThirdPartyServiceEntity
import kotlinx.serialization.encodeToString

@Entity(tableName = "sync_runs")
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "started_at")
    val startedAt: String,
    @ColumnInfo(name = "finished_at")
    val finishedAt: String? = null,
    val status: String,
    @ColumnInfo(name = "module_summary_json")
    val moduleSummaryJson: String = "{}",
    @ColumnInfo(name = "error_text")
    val errorText: String? = null,
)

@Entity(tableName = "module_snapshots")
data class ModuleSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "module_key")
    val moduleKey: String,
    @ColumnInfo(name = "synced_at")
    val syncedAt: String,
    @ColumnInfo(name = "source_system")
    val sourceSystem: String,
    val coverage: String,
    @ColumnInfo(name = "source_params_json")
    val sourceParamsJson: String = "{}",
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
)

@Entity(tableName = "module_update_summaries")
data class ModuleUpdateSummaryEntity(
    @PrimaryKey
    @ColumnInfo(name = "module_key")
    val moduleKey: String,
    @ColumnInfo(name = "synced_at")
    val syncedAt: String,
    @ColumnInfo(name = "items_json")
    val itemsJson: String,
)

@Entity(tableName = "user_courses")
data class UserCourseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "course_name")
    val courseName: String,
    val weekday: String,
    @ColumnInfo(name = "weekday_index")
    val weekdayIndex: Int,
    val period: String,
    @ColumnInfo(name = "period_number")
    val periodNumber: Int,
    @ColumnInfo(name = "time_range")
    val timeRange: String? = null,
    @ColumnInfo(name = "start_week")
    val startWeek: Int,
    @ColumnInfo(name = "end_week")
    val endWeek: Int,
    @ColumnInfo(name = "weeks_text")
    val weeksText: String? = null,
    @ColumnInfo(name = "duration_type")
    val durationType: String,
    val teacher: String? = null,
    @ColumnInfo(name = "location_text")
    val locationText: String? = null,
    val remark: String? = null,
    @ColumnInfo(name = "color_index")
    val colorIndex: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)

@Entity(
    tableName = "user_todos",
    indices = [Index(value = ["date"])],
)
data class UserTodoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val date: String,
    val note: String? = null,
    val done: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)

@Entity(tableName = "mail_folders")
data class MailFolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "folder_id")
    val folderId: String,
    val name: String,
    @ColumnInfo(name = "message_count")
    val messageCount: Int = 0,
    @ColumnInfo(name = "unread_count")
    val unreadCount: Int = 0,
    @ColumnInfo(name = "message_size")
    val messageSize: Int = 0,
    @ColumnInfo(name = "unread_size")
    val unreadSize: Int = 0,
    val system: Boolean = false,
    @ColumnInfo(name = "synced_at")
    val syncedAt: String,
)

@Entity(
    tableName = "mail_message_summaries",
    indices = [
        Index(value = ["folder_id", "received_at"]),
        Index(value = ["folder_id", "sent_at"]),
    ],
)
data class MailMessageSummaryEntity(
    @PrimaryKey
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "folder_id")
    val folderId: String,
    val subject: String = "",
    @ColumnInfo(name = "from_text")
    val fromText: String = "",
    @ColumnInfo(name = "to_text")
    val toText: String = "",
    val sender: String? = null,
    @ColumnInfo(name = "sent_at")
    val sentAt: String? = null,
    @ColumnInfo(name = "received_at")
    val receivedAt: String? = null,
    @ColumnInfo(name = "modified_at")
    val modifiedAt: String? = null,
    val size: Int = 0,
    val read: Boolean = false,
    val attached: Boolean = false,
    val priority: Int? = null,
    val summary: String? = null,
    @ColumnInfo(name = "synced_at")
    val syncedAt: String,
)

@Dao
interface BjtuMisDao {
    @Insert
    suspend fun insertSyncRun(entity: SyncRunEntity): Long

    @Query(
        """
        UPDATE sync_runs
        SET finished_at = :finishedAt,
            status = :status,
            module_summary_json = :moduleSummaryJson,
            error_text = :errorText
        WHERE id = :id
        """
    )
    suspend fun finishSyncRun(
        id: Long,
        finishedAt: String,
        status: String,
        moduleSummaryJson: String,
        errorText: String?,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSnapshot(entity: ModuleSnapshotEntity)

    @Query("SELECT * FROM module_snapshots WHERE module_key = :moduleKey")
    suspend fun getSnapshot(moduleKey: String): ModuleSnapshotEntity?

    @Query("SELECT * FROM module_snapshots ORDER BY module_key")
    suspend fun getSnapshots(): List<ModuleSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveModuleUpdateSummary(entity: ModuleUpdateSummaryEntity)

    @Query("SELECT * FROM module_update_summaries WHERE module_key = :moduleKey")
    suspend fun getModuleUpdateSummary(moduleKey: String): ModuleUpdateSummaryEntity?

    @Query("SELECT * FROM module_update_summaries ORDER BY module_key")
    suspend fun getModuleUpdateSummaries(): List<ModuleUpdateSummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUserCourse(entity: UserCourseEntity): Long

    @Query("SELECT * FROM user_courses WHERE id = :id")
    suspend fun getUserCourse(id: Long): UserCourseEntity?

    @Query("SELECT * FROM user_courses ORDER BY weekday_index, period_number, course_name")
    suspend fun getUserCourses(): List<UserCourseEntity>

    @Query("DELETE FROM user_courses WHERE id = :id")
    suspend fun deleteUserCourse(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveUserTodo(entity: UserTodoEntity): Long

    @Query("SELECT * FROM user_todos WHERE id = :id")
    suspend fun getUserTodo(id: Long): UserTodoEntity?

    @Query("SELECT * FROM user_todos ORDER BY date, done, created_at")
    suspend fun getUserTodos(): List<UserTodoEntity>

    @Query("UPDATE user_todos SET done = :done, updated_at = :updatedAt WHERE id = :id")
    suspend fun setUserTodoDone(id: Long, done: Boolean, updatedAt: String)

    @Query("DELETE FROM user_todos WHERE id = :id")
    suspend fun deleteUserTodo(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMailFolders(folders: List<MailFolderEntity>)

    @Query("SELECT * FROM mail_folders ORDER BY system DESC, folder_id")
    suspend fun getMailFolders(): List<MailFolderEntity>

    @Query("DELETE FROM mail_folders")
    suspend fun clearMailFolders()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMailMessageSummaries(messages: List<MailMessageSummaryEntity>)

    @Query("DELETE FROM mail_message_summaries WHERE folder_id = :folderId")
    suspend fun clearMailMessageSummaries(folderId: String)

    @Query("DELETE FROM mail_message_summaries WHERE message_id IN (:messageIds)")
    suspend fun deleteMailMessageSummaries(messageIds: List<String>)

    @Query("SELECT * FROM mail_message_summaries WHERE message_id IN (:messageIds)")
    suspend fun getMailMessageSummariesByIds(messageIds: List<String>): List<MailMessageSummaryEntity>

    @Query("UPDATE mail_message_summaries SET read = 1 WHERE message_id IN (:messageIds)")
    suspend fun markMailMessageSummariesRead(messageIds: List<String>)

    @Query("UPDATE mail_folders SET unread_count = MAX(unread_count - :delta, 0) WHERE folder_id = :folderId")
    suspend fun decrementMailFolderUnreadCount(folderId: String, delta: Int)

    @Query(
        """
        SELECT * FROM mail_message_summaries
        WHERE folder_id = :folderId
        ORDER BY COALESCE(received_at, sent_at, modified_at, '') DESC, message_id DESC
        LIMIT :limit OFFSET :start
        """
    )
    suspend fun getMailMessageSummaries(folderId: String, start: Int, limit: Int): List<MailMessageSummaryEntity>

    @Query("SELECT COUNT(*) FROM mail_message_summaries WHERE folder_id = :folderId")
    suspend fun countMailMessageSummaries(folderId: String): Int

    @Query("SELECT * FROM sync_runs ORDER BY id DESC LIMIT 1")
    suspend fun getLatestSyncRun(): SyncRunEntity?

    @Query("DELETE FROM module_snapshots")
    suspend fun clearSnapshots()

    @Query("DELETE FROM sync_runs")
    suspend fun clearSyncRuns()
}

@Database(
    entities = [
        SyncRunEntity::class,
        ModuleSnapshotEntity::class,
        ModuleUpdateSummaryEntity::class,
        UserCourseEntity::class,
        UserTodoEntity::class,
        MailFolderEntity::class,
        MailMessageSummaryEntity::class,
        ThirdPartyServiceEntity::class,
    ],
    version = 10,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): BjtuMisDao
    abstract fun thirdPartyServiceDao(): ThirdPartyServiceDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `user_courses` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `course_name` TEXT NOT NULL,
                `weekday` TEXT NOT NULL,
                `weekday_index` INTEGER NOT NULL,
                `period` TEXT NOT NULL,
                `period_number` INTEGER NOT NULL,
                `time_range` TEXT,
                `start_week` INTEGER NOT NULL,
                `end_week` INTEGER NOT NULL,
                `duration_type` TEXT NOT NULL,
                `teacher` TEXT,
                `location_text` TEXT,
                `remark` TEXT,
                `color_index` INTEGER NOT NULL,
                `created_at` TEXT NOT NULL,
                `updated_at` TEXT NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `user_courses` ADD COLUMN `weeks_text` TEXT")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) = Unit
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mail_folders` (
                `folder_id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `message_count` INTEGER NOT NULL,
                `unread_count` INTEGER NOT NULL,
                `message_size` INTEGER NOT NULL,
                `unread_size` INTEGER NOT NULL,
                `system` INTEGER NOT NULL,
                `synced_at` TEXT NOT NULL,
                PRIMARY KEY(`folder_id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mail_message_summaries` (
                `message_id` TEXT NOT NULL,
                `folder_id` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `from_text` TEXT NOT NULL,
                `to_text` TEXT NOT NULL,
                `sender` TEXT,
                `sent_at` TEXT,
                `received_at` TEXT,
                `modified_at` TEXT,
                `size` INTEGER NOT NULL,
                `read` INTEGER NOT NULL,
                `attached` INTEGER NOT NULL,
                `priority` INTEGER,
                `summary` TEXT,
                `synced_at` TEXT NOT NULL,
                PRIMARY KEY(`message_id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mail_message_summaries_folder_id_received_at` ON `mail_message_summaries` (`folder_id`, `received_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_mail_message_summaries_folder_id_sent_at` ON `mail_message_summaries` (`folder_id`, `sent_at`)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `user_todos` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `date` TEXT NOT NULL,
                `note` TEXT,
                `done` INTEGER NOT NULL,
                `created_at` TEXT NOT NULL,
                `updated_at` TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_todos_date` ON `user_todos` (`date`)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `agent_messages`")
        db.execSQL("DROP TABLE IF EXISTS `agent_artifacts`")
        db.execSQL("DROP TABLE IF EXISTS `agent_steps`")
        db.execSQL("DROP TABLE IF EXISTS `agent_tasks`")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_THIRD_PARTY_SERVICES_SQL)
        db.execSQL(CREATE_THIRD_PARTY_SERVICES_GITHUB_INDEX_SQL)
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(ADD_THIRD_PARTY_SERVICE_PACKAGE_DIGEST_SQL)
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_MODULE_UPDATE_SUMMARIES_SQL)
    }
}

internal const val CREATE_MODULE_UPDATE_SUMMARIES_SQL = """
CREATE TABLE IF NOT EXISTS `module_update_summaries` (
    `module_key` TEXT NOT NULL,
    `synced_at` TEXT NOT NULL,
    `items_json` TEXT NOT NULL,
    PRIMARY KEY(`module_key`)
)
"""

internal const val CREATE_THIRD_PARTY_SERVICES_SQL = """
CREATE TABLE IF NOT EXISTS `third_party_services` (
    `service_id` TEXT NOT NULL,
    `name` TEXT NOT NULL,
    `description` TEXT NOT NULL,
    `version` TEXT NOT NULL,
    `author` TEXT NOT NULL,
    `source_url` TEXT NOT NULL,
    `github_owner` TEXT NOT NULL,
    `github_repo` TEXT NOT NULL,
    `default_branch` TEXT NOT NULL,
    `commit_sha` TEXT NOT NULL,
    `manifest_json` TEXT NOT NULL,
    `granted_permissions_json` TEXT NOT NULL,
    `allowed_origins_json` TEXT NOT NULL,
    `install_dir` TEXT NOT NULL,
    `entrypoint` TEXT NOT NULL,
    `icon` TEXT NOT NULL,
    `enabled` INTEGER NOT NULL,
    `needs_review` INTEGER NOT NULL,
    `installed_at` TEXT NOT NULL,
    `updated_at` TEXT NOT NULL,
    PRIMARY KEY(`service_id`)
)
"""

internal const val CREATE_THIRD_PARTY_SERVICES_GITHUB_INDEX_SQL =
    "CREATE INDEX IF NOT EXISTS `index_third_party_services_github_owner_github_repo` ON `third_party_services` (`github_owner`, `github_repo`)"

internal const val ADD_THIRD_PARTY_SERVICE_PACKAGE_DIGEST_SQL =
    "ALTER TABLE `third_party_services` ADD COLUMN `package_digest_sha256` TEXT NOT NULL DEFAULT ''"

fun SyncRunEntity.toModel(): SyncRun {
    val summary = runCatching {
        AppJson.decodeFromString<Map<String, SyncModuleSummary>>(moduleSummaryJson)
    }.getOrDefault(emptyMap())
    return SyncRun(
        id = id,
        startedAt = startedAt,
        finishedAt = finishedAt,
        status = status,
        moduleSummary = summary,
        errorText = errorText,
    )
}

fun encodeSummary(summary: Map<String, SyncModuleSummary>): String =
    AppJson.encodeToString(summary)

fun UserCourseEntity.toCourseEntry(): CourseEntry =
    CourseEntry(
        weekday = weekday,
        period = period,
        timeRange = timeRange,
        courseCode = "LOCAL-$id",
        section = durationType,
        courseName = courseName,
        teacher = teacher,
        weeks = weeksText ?: formatUserCourseWeeks(startWeek, endWeek),
        locationText = locationText,
        localId = id,
        remark = remark,
        colorIndex = colorIndex,
        isUserCreated = true,
    )

fun UserTodoEntity.toModel(): UserTodoItem =
    UserTodoItem(
        id = id,
        title = title,
        date = date,
        note = note,
        done = done,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun MailFolderEntity.toModel(): MailFolder =
    MailFolder(
        folderId = folderId,
        name = name,
        messageCount = messageCount,
        unreadCount = unreadCount,
        messageSize = messageSize,
        unreadSize = unreadSize,
        system = system,
    )

fun MailFolder.toEntity(syncedAt: String): MailFolderEntity =
    MailFolderEntity(
        folderId = folderId,
        name = name,
        messageCount = messageCount,
        unreadCount = unreadCount,
        messageSize = messageSize,
        unreadSize = unreadSize,
        system = system,
        syncedAt = syncedAt,
    )

fun MailMessageSummaryEntity.toModel(): MailMessageSummary =
    MailMessageSummary(
        messageId = messageId,
        folderId = folderId,
        subject = subject,
        fromText = fromText,
        toText = toText,
        sender = sender,
        sentAt = sentAt,
        receivedAt = receivedAt,
        modifiedAt = modifiedAt,
        size = size,
        read = read,
        attached = attached,
        priority = priority,
        summary = summary,
    )

fun MailMessageSummary.toEntity(syncedAt: String): MailMessageSummaryEntity =
    MailMessageSummaryEntity(
        messageId = messageId,
        folderId = folderId,
        subject = subject,
        fromText = fromText,
        toText = toText,
        sender = sender,
        sentAt = sentAt,
        receivedAt = receivedAt,
        modifiedAt = modifiedAt,
        size = size,
        read = read,
        attached = attached,
        priority = priority,
        summary = summary,
        syncedAt = syncedAt,
    )
