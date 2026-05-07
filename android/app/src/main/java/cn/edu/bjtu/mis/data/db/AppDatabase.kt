package cn.edu.bjtu.mis.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.ColumnInfo
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import cn.edu.bjtu.mis.data.AppJson
import cn.edu.bjtu.mis.model.SyncModuleSummary
import cn.edu.bjtu.mis.model.SyncRun
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

    @Query("SELECT * FROM sync_runs ORDER BY id DESC LIMIT 1")
    suspend fun getLatestSyncRun(): SyncRunEntity?

    @Query("DELETE FROM module_snapshots")
    suspend fun clearSnapshots()

    @Query("DELETE FROM sync_runs")
    suspend fun clearSyncRuns()
}

@Database(
    entities = [SyncRunEntity::class, ModuleSnapshotEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): BjtuMisDao
}

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
