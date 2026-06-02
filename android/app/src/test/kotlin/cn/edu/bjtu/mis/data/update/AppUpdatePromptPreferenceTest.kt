package cn.edu.bjtu.mis.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePromptPreferenceTest {
    @Test
    fun defaultPreferencePromptsForAvailableUpdate() {
        assertTrue(AppUpdatePromptPreference().shouldPromptForUpdate(update("1.2.3")))
    }

    @Test
    fun ignoredVersionSuppressesOnlyThatVersion() {
        val preference = AppUpdatePromptPreference(ignoredVersion = "1.2.3")

        assertFalse(preference.shouldPromptForUpdate(update("1.2.3")))
        assertTrue(preference.shouldPromptForUpdate(update("1.2.4")))
    }

    @Test
    fun disabledAutoPromptSuppressesAllVersions() {
        val preference = AppUpdatePromptPreference(autoPromptDisabled = true)

        assertFalse(preference.shouldPromptForUpdate(update("1.2.3")))
        assertFalse(preference.shouldPromptForUpdate(update("1.2.4")))
    }

    @Test
    fun restoredAutoPromptUsesVersionIgnoreRuleAgain() {
        val restored = AppUpdatePromptPreference(
            ignoredVersion = "1.2.3",
            autoPromptDisabled = true,
        ).copy(autoPromptDisabled = false)

        assertFalse(restored.shouldPromptForUpdate(update("1.2.3")))
        assertTrue(restored.shouldPromptForUpdate(update("1.2.4")))
    }

    private fun update(latestVersion: String): AppUpdateInfo =
        AppUpdateInfo(
            currentVersion = "1.0.0",
            latestVersion = latestVersion,
            releaseUrl = "https://github.com/wan300/bjtu_web/releases/tag/v$latestVersion",
        )
}
