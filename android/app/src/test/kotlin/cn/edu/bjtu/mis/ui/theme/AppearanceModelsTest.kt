package cn.edu.bjtu.mis.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceModelsTest {
    @Test
    fun `fresh install defaults to apple`() {
        assertEquals(AppUiStyle.Apple, resolveInitialUiStyle(42L, 42L))
    }

    @Test
    fun `updated install defaults to classic`() {
        assertEquals(AppUiStyle.Classic, resolveInitialUiStyle(42L, 84L))
    }

    @Test
    fun `missing or invalid install metadata safely defaults to classic`() {
        assertEquals(AppUiStyle.Classic, resolveInitialUiStyle(null, 84L))
        assertEquals(AppUiStyle.Classic, resolveInitialUiStyle(42L, null))
        assertEquals(AppUiStyle.Classic, resolveInitialUiStyle(0L, 0L))
    }

    @Test
    fun `unknown storage values retain compatibility fallbacks`() {
        assertEquals(AppThemeOption.Default, AppThemeOption.fromStorageValue("future_theme"))
        assertEquals(AppUiStyle.Classic, AppUiStyle.fromStorageValue("future_style"))
        assertEquals(
            AppEffectOverride.FollowSystem,
            AppEffectOverride.fromStorageValue("future_override"),
        )
    }

    @Test
    fun `plugin top bar remains visible by default`() {
        assertFalse(AppAppearancePreferences().hideThirdPartyServiceTopBar)
    }

    @Test
    fun `all existing theme storage values remain stable`() {
        assertEquals(AppThemeOption.Default, AppThemeOption.fromStorageValue("default"))
        assertEquals(AppThemeOption.MascotGold, AppThemeOption.fromStorageValue("mascot_gold"))
        assertEquals(
            AppThemeOption.IllustrationRose,
            AppThemeOption.fromStorageValue("illustration_rose"),
        )
    }

    @Test
    fun `effect overrides merge with system and high contrast signals`() {
        val followingSystem = resolveEffectiveAppEffects(
            preferences = AppAppearancePreferences(),
            systemReduceMotion = true,
            systemReduceTransparency = false,
            highContrastText = true,
        )
        assertTrue(followingSystem.reduceMotion)
        assertTrue(followingSystem.reduceTransparency)

        val explicit = resolveEffectiveAppEffects(
            preferences = AppAppearancePreferences(
                reduceMotionOverride = AppEffectOverride.Disabled,
                reduceTransparencyOverride = AppEffectOverride.Enabled,
            ),
            systemReduceMotion = true,
            systemReduceTransparency = false,
            highContrastText = false,
        )
        assertFalse(explicit.reduceMotion)
        assertTrue(explicit.reduceTransparency)
    }
}
