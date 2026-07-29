package cn.edu.bjtu.mis.data.thirdparty

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

@Entity(
    tableName = "third_party_services",
    indices = [Index(value = ["github_owner", "github_repo"])],
)
data class ThirdPartyServiceEntity(
    @PrimaryKey
    @ColumnInfo(name = "service_id")
    val serviceId: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    @ColumnInfo(name = "source_url")
    val sourceUrl: String,
    @ColumnInfo(name = "github_owner")
    val githubOwner: String,
    @ColumnInfo(name = "github_repo")
    val githubRepo: String,
    @ColumnInfo(name = "default_branch")
    val defaultBranch: String,
    @ColumnInfo(name = "commit_sha")
    val commitSha: String,
    @ColumnInfo(name = "package_digest_sha256")
    val packageDigestSha256: String,
    @ColumnInfo(name = "manifest_json")
    val manifestJson: String,
    @ColumnInfo(name = "granted_permissions_json")
    val grantedPermissionsJson: String,
    @ColumnInfo(name = "allowed_origins_json")
    val allowedOriginsJson: String,
    @ColumnInfo(name = "publisher_subject_id")
    val publisherSubjectId: String = "",
    @ColumnInfo(name = "data_schema_version")
    val dataSchemaVersion: Int = 0,
    @ColumnInfo(name = "compatibility_state")
    val compatibilityState: String = ThirdPartyCompatibilityState.LegacyDisabled.value,
    @ColumnInfo(name = "verification_level")
    val verificationLevel: String = "legacy",
    @ColumnInfo(name = "previous_version_json")
    val previousVersionJson: String? = null,
    @ColumnInfo(name = "install_dir")
    val installDir: String,
    val entrypoint: String,
    val icon: String,
    val enabled: Boolean,
    @ColumnInfo(name = "needs_review")
    val needsReview: Boolean,
    @ColumnInfo(name = "installed_at")
    val installedAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)

@Entity(tableName = "third_party_cleanup_tombstones")
data class ThirdPartyCleanupTombstoneEntity(
    @PrimaryKey
    @ColumnInfo(name = "service_id")
    val serviceId: String,
    @ColumnInfo(name = "publisher_subject_id")
    val publisherSubjectId: String,
    @ColumnInfo(name = "web_storage_origin")
    val webStorageOrigin: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
)

@Dao
interface ThirdPartyServiceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveService(service: ThirdPartyServiceEntity)

    @Query("SELECT * FROM third_party_services ORDER BY name COLLATE NOCASE")
    suspend fun listServices(): List<ThirdPartyServiceEntity>

    @Query("SELECT * FROM third_party_services WHERE service_id = :serviceId")
    suspend fun getService(serviceId: String): ThirdPartyServiceEntity?

    @Query("DELETE FROM third_party_services WHERE service_id = :serviceId")
    suspend fun deleteService(serviceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCleanupTombstone(tombstone: ThirdPartyCleanupTombstoneEntity)

    @Query("SELECT * FROM third_party_cleanup_tombstones ORDER BY created_at")
    suspend fun listCleanupTombstones(): List<ThirdPartyCleanupTombstoneEntity>

    @Query("DELETE FROM third_party_cleanup_tombstones WHERE service_id = :serviceId")
    suspend fun deleteCleanupTombstone(serviceId: String)

    @Transaction
    suspend fun deleteServiceAndScheduleCleanup(tombstone: ThirdPartyCleanupTombstoneEntity) {
        saveCleanupTombstone(tombstone)
        deleteService(tombstone.serviceId)
    }

    @Query(
        """
        UPDATE third_party_services
        SET granted_permissions_json = :grantedPermissionsJson,
            enabled = :enabled,
            needs_review = :needsReview,
            updated_at = :updatedAt
        WHERE service_id = :serviceId
        """
    )
    suspend fun updateGrantState(
        serviceId: String,
        grantedPermissionsJson: String,
        enabled: Boolean,
        needsReview: Boolean,
        updatedAt: String,
    )
}
