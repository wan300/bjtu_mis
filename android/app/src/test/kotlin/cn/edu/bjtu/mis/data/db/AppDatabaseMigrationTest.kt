package cn.edu.bjtu.mis.data.db

import org.junit.Assert.assertTrue
import org.junit.Test

class AppDatabaseMigrationTest {
    @Test
    fun migrationSevenToEightCreatesThirdPartyServicesTableShape() {
        val createSql = CREATE_THIRD_PARTY_SERVICES_SQL

        listOf(
            "`service_id` TEXT NOT NULL",
            "`manifest_json` TEXT NOT NULL",
            "`granted_permissions_json` TEXT NOT NULL",
            "`allowed_origins_json` TEXT NOT NULL",
            "`github_owner` TEXT NOT NULL",
            "`github_repo` TEXT NOT NULL",
            "`default_branch` TEXT NOT NULL",
            "`commit_sha` TEXT NOT NULL",
            "`install_dir` TEXT NOT NULL",
            "`enabled` INTEGER NOT NULL",
            "`needs_review` INTEGER NOT NULL",
            "PRIMARY KEY(`service_id`)",
        ).forEach { fragment ->
            assertTrue("Missing SQL fragment: $fragment", createSql.contains(fragment))
        }

        assertTrue(CREATE_THIRD_PARTY_SERVICES_GITHUB_INDEX_SQL.contains("`github_owner`, `github_repo`"))
    }

    @Test
    fun migrationEightToNineAddsThirdPartyPackageDigest() {
        assertTrue(ADD_THIRD_PARTY_SERVICE_PACKAGE_DIGEST_SQL.contains("`package_digest_sha256` TEXT NOT NULL DEFAULT ''"))
    }

    @Test
    fun migrationNineToTenCreatesModuleUpdateSummariesTableShape() {
        val createSql = CREATE_MODULE_UPDATE_SUMMARIES_SQL

        listOf(
            "`module_key` TEXT NOT NULL",
            "`synced_at` TEXT NOT NULL",
            "`items_json` TEXT NOT NULL",
            "PRIMARY KEY(`module_key`)",
        ).forEach { fragment ->
            assertTrue("Missing SQL fragment: $fragment", createSql.contains(fragment))
        }
    }

    @Test
    fun migrationTenToElevenAddsV3IdentityAndDisablesLegacyRows() {
        val sql = MIGRATION_10_11_ADD_COLUMN_SQL.joinToString("\n")
        listOf(
            "`publisher_subject_id`",
            "`data_schema_version`",
            "`compatibility_state`",
            "`verification_level`",
            "`previous_version_json`",
        ).forEach { fragment ->
            assertTrue("Missing SQL fragment: $fragment", sql.contains(fragment))
        }
        assertTrue(MIGRATION_10_11_DISABLE_LEGACY_SQL.contains("`enabled` = 0"))
        assertTrue(MIGRATION_10_11_DISABLE_LEGACY_SQL.contains("`needs_review` = 1"))
        assertTrue(MIGRATION_10_11_DISABLE_LEGACY_SQL.contains("'legacy_disabled'"))
        listOf(
            "`service_id` TEXT NOT NULL",
            "`publisher_subject_id` TEXT NOT NULL",
            "`web_storage_origin` TEXT NOT NULL",
            "PRIMARY KEY(`service_id`)",
        ).forEach { fragment ->
            assertTrue(
                "Missing cleanup tombstone SQL fragment: $fragment",
                CREATE_THIRD_PARTY_CLEANUP_TOMBSTONES_SQL.contains(fragment),
            )
        }
    }

    @Test
    fun migrationElevenToTwelveClassifiesP0aAndFailsAllLegacyClosed() {
        val sql = MIGRATION_11_12_ADD_COLUMN_SQL.joinToString("\n")
        listOf(
            "`granted_capabilities_json`",
            "`runtime_profile`",
            "`runtime_floor`",
            "`marketplace_json`",
        ).forEach { fragment ->
            assertTrue("Missing SQL fragment: $fragment", sql.contains(fragment))
        }
        assertTrue(MIGRATION_11_12_CLASSIFY_AND_DISABLE_SQL.contains("`enabled` = 0"))
        assertTrue(MIGRATION_11_12_CLASSIFY_AND_DISABLE_SQL.contains("`needs_review` = 1"))
        assertTrue(MIGRATION_11_12_CLASSIFY_AND_DISABLE_SQL.contains("'legacy_v3_p0a'"))
        assertTrue(MIGRATION_11_12_CLASSIFY_AND_DISABLE_SQL.contains("'legacy_v1_v2'"))
        assertTrue(MIGRATION_11_12_CLASSIFY_AND_DISABLE_SQL.contains("'contract_v1'"))
        assertTrue(MIGRATION_11_12_CLASSIFY_AND_DISABLE_SQL.contains("`runtime_floor`"))
        assertTrue(MIGRATION_11_12_CLASSIFY_AND_DISABLE_SQL.contains("`granted_capabilities_json` = '[]'"))
    }
}
