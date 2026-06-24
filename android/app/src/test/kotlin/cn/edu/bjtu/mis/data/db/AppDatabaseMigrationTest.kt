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
}
