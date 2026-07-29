package cn.edu.bjtu.mis.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationInstrumentationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrationTenToElevenPreservesLegacyRecordAndDisablesRuntime() {
        helper.createDatabase(DatabaseName, 10).use { database ->
            database.execSQL(
                """
                INSERT INTO third_party_services(
                    service_id,name,description,version,author,source_url,
                    github_owner,github_repo,default_branch,commit_sha,
                    package_digest_sha256,manifest_json,granted_permissions_json,
                    allowed_origins_json,install_dir,entrypoint,icon,enabled,
                    needs_review,installed_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                arrayOf(
                    "bjtu.legacy",
                    "Legacy",
                    "Legacy plugin",
                    "2.0.0",
                    "Alice",
                    "https://github.com/alice/legacy",
                    "alice",
                    "legacy",
                    "main",
                    "abcdef1",
                    "a".repeat(64),
                    """{"schema_version":2}""",
                    """["identity.profile.read"]""",
                    """["https://example.com"]""",
                    "/data/legacy",
                    "index.html",
                    "icon.svg",
                    1,
                    0,
                    "2026-07-01T00:00:00Z",
                    "2026-07-01T00:00:00Z",
                ),
            )
        }

        helper.runMigrationsAndValidate(
            DatabaseName,
            11,
            true,
            MIGRATION_10_11,
        ).use { database ->
            database.query(
                """
                SELECT enabled,needs_review,compatibility_state,publisher_subject_id,
                    data_schema_version,verification_level,previous_version_json
                FROM third_party_services WHERE service_id='bjtu.legacy'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
                assertEquals("legacy_disabled", cursor.getString(2))
                assertEquals("", cursor.getString(3))
                assertEquals(0, cursor.getInt(4))
                assertEquals("legacy", cursor.getString(5))
                assertTrue(cursor.isNull(6))
            }
            database.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='third_party_cleanup_tombstones'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        }
    }

    private companion object {
        const val DatabaseName = "manifest-v3-migration-test"
    }
}
