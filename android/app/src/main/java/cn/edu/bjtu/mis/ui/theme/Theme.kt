package cn.edu.bjtu.mis.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

enum class AppThemeOption(
    val storageValue: String,
    val isDark: Boolean = false,
) {
    Default("default"),
    MascotGold("mascot_gold", isDark = true),
    IllustrationRose("illustration_rose");

    companion object {
        fun fromStorageValue(value: String?): AppThemeOption =
            entries.firstOrNull { it.storageValue == value } ?: Default
    }
}

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B74F6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF00325F),
    secondary = Color(0xFF315F8F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EEF8),
    onSecondaryContainer = Color(0xFF173B5F),
    tertiary = Color(0xFF00A6A6),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9F4F2),
    onTertiaryContainer = Color(0xFF003D3B),
    background = Color(0xFFF3F6FA),
    onBackground = Color(0xFF152033),
    surface = Color.White,
    onSurface = Color(0xFF152033),
    surfaceVariant = Color(0xFFEAF0F7),
    onSurfaceVariant = Color(0xFF5D6A7A),
    outline = Color(0xFFD5DDE8),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val MascotGoldScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFE4B96A),
    onPrimary = Color(0xFF241607),
    primaryContainer = Color(0xFF4B3518),
    onPrimaryContainer = Color(0xFFFFE5AD),
    secondary = Color(0xFFCBB08A),
    onSecondary = Color(0xFF251A0E),
    secondaryContainer = Color(0xFF3A2B1C),
    onSecondaryContainer = Color(0xFFEFD8B6),
    tertiary = Color(0xFFD56B63),
    onTertiary = Color(0xFF2F0907),
    tertiaryContainer = Color(0xFF51201E),
    onTertiaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1018),
    onBackground = Color(0xFFF4EDE3),
    surface = Color(0xFF171A24),
    onSurface = Color(0xFFF4EDE3),
    surfaceVariant = Color(0xFF262A38),
    onSurfaceVariant = Color(0xFFCFC4B8),
    outline = Color(0xFF51483D),
    outlineVariant = Color(0xFF39342D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val IllustrationRoseScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFB86B63),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4D8D0),
    onPrimaryContainer = Color(0xFF3A1814),
    secondary = Color(0xFF91524B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3D7D1),
    onSecondaryContainer = Color(0xFF351614),
    tertiary = Color(0xFF576484),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDE4FF),
    onTertiaryContainer = Color(0xFF121D3B),
    background = Color(0xFFFFF8F3),
    onBackground = Color(0xFF2A2429),
    surface = Color.White,
    onSurface = Color(0xFF2A2429),
    surfaceVariant = Color(0xFFF4E4D4),
    onSurfaceVariant = Color(0xFF674C48),
    outline = Color(0xFFD2B4A3),
    outlineVariant = Color(0xFFEAD2C5),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

private val AppleShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val AppleTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
)

enum class AppWindowWidthClass {
    Compact,
    Medium,
    Expanded,
}

fun appWindowWidthClass(widthDp: Int): AppWindowWidthClass =
    when {
        widthDp >= 840 -> AppWindowWidthClass.Expanded
        widthDp >= 600 -> AppWindowWidthClass.Medium
        else -> AppWindowWidthClass.Compact
    }

data class AppDesignTokens(
    val pageHorizontalPadding: Dp,
    val pageVerticalPadding: Dp,
    val sectionSpacing: Dp,
    val itemSpacing: Dp,
    val cardContentPadding: Dp,
    val minimumTouchTarget: Dp,
    val materialSurfaceAlpha: Float,
)

data class AppMotionTokens(
    val reduceMotion: Boolean,
    val feedbackDurationMillis: Int,
    val normalDampingRatio: Float,
    val normalStiffness: Float,
    val snapDampingRatio: Float,
    val snapStiffness: Float,
)

enum class AppHapticEvent {
    Selection,
    Snap,
    Success,
    Error,
    Commit,
}

class AppHaptics internal constructor(
    private val performFeedback: (AppHapticEvent) -> Unit,
) {
    fun perform(event: AppHapticEvent) = performFeedback(event)
}

val LocalAppUiStyle = staticCompositionLocalOf { AppUiStyle.Classic }
val LocalAppWindowWidthClass = staticCompositionLocalOf { AppWindowWidthClass.Compact }
val LocalAppEffects = staticCompositionLocalOf {
    EffectiveAppEffects(
        reduceMotion = false,
        reduceTransparency = false,
    )
}
val LocalAppDesign = staticCompositionLocalOf {
    AppDesignTokens(
        pageHorizontalPadding = 14.dp,
        pageVerticalPadding = 16.dp,
        sectionSpacing = 14.dp,
        itemSpacing = 8.dp,
        cardContentPadding = 16.dp,
        minimumTouchTarget = 48.dp,
        materialSurfaceAlpha = 1f,
    )
}
val LocalAppMotion = staticCompositionLocalOf {
    AppMotionTokens(
        reduceMotion = false,
        feedbackDurationMillis = 120,
        normalDampingRatio = 1f,
        normalStiffness = 400f,
        snapDampingRatio = 0.8f,
        snapStiffness = 500f,
    )
}
val LocalAppHaptics = compositionLocalOf { AppHaptics {} }

@Composable
fun BjtuMisTheme(
    themeOption: AppThemeOption = AppThemeOption.Default,
    appearance: AppAppearancePreferences = AppAppearancePreferences(theme = themeOption),
    content: @Composable () -> Unit,
) {
    val resolvedAppearance = if (appearance.theme == themeOption) {
        appearance
    } else {
        appearance.copy(theme = themeOption)
    }
    val colorScheme = when (themeOption) {
        AppThemeOption.Default -> LightScheme
        AppThemeOption.MascotGold -> MascotGoldScheme
        AppThemeOption.IllustrationRose -> IllustrationRoseScheme
    }
    val isDark = themeOption.isDark
    val isApple = resolvedAppearance.uiStyle == AppUiStyle.Apple
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val systemReduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
    val highContrastText = remember(context) {
        runCatching {
            Settings.Secure.getInt(
                context.contentResolver,
                "high_text_contrast_enabled",
                0,
            ) == 1
        }.getOrDefault(false)
    }
    val effects = resolveEffectiveAppEffects(
        preferences = resolvedAppearance,
        systemReduceMotion = systemReduceMotion,
        systemReduceTransparency = false,
        highContrastText = highContrastText,
    )
    val design = if (isApple) {
        AppDesignTokens(
            pageHorizontalPadding = 20.dp,
            pageVerticalPadding = 18.dp,
            sectionSpacing = 22.dp,
            itemSpacing = 12.dp,
            cardContentPadding = 18.dp,
            minimumTouchTarget = 48.dp,
            materialSurfaceAlpha = if (effects.reduceTransparency) 1f else 0.94f,
        )
    } else {
        LocalAppDesign.current
    }
    val motion = AppMotionTokens(
        reduceMotion = effects.reduceMotion,
        feedbackDurationMillis = 120,
        normalDampingRatio = 1f,
        normalStiffness = 400f,
        snapDampingRatio = 0.8f,
        snapStiffness = 500f,
    )
    val platformHaptics = LocalHapticFeedback.current
    val haptics = remember(platformHaptics) {
        AppHaptics { event ->
            platformHaptics.performHapticFeedback(
                when (event) {
                    AppHapticEvent.Selection,
                    AppHapticEvent.Snap,
                    AppHapticEvent.Success
                    -> HapticFeedbackType.TextHandleMove
                    AppHapticEvent.Error,
                    AppHapticEvent.Commit
                    -> HapticFeedbackType.LongPress
                },
            )
        }
    }
    val widthClass = appWindowWidthClass(configuration.screenWidthDp)

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = if (isApple) AppleShapes else AppShapes,
        typography = if (isApple) AppleTypography else MaterialTheme.typography,
    ) {
        CompositionLocalProvider(
            LocalAppUiStyle provides resolvedAppearance.uiStyle,
            LocalAppWindowWidthClass provides widthClass,
            LocalAppEffects provides effects,
            LocalAppDesign provides design,
            LocalAppMotion provides motion,
            LocalAppHaptics provides haptics,
        ) {
            BjtuMisSystemBars(
                statusBarColor = if (isApple) {
                    MaterialTheme.colorScheme.background
                } else {
                    MaterialTheme.colorScheme.primary
                },
                navigationBarColor = MaterialTheme.colorScheme.surface,
                useDarkStatusBarIcons = !isDark,
                useDarkNavigationBarIcons = !isDark,
            )
            content()
        }
    }
}

@Composable
fun BjtuMisSystemBars(
    statusBarColor: Color = MaterialTheme.colorScheme.primary,
    navigationBarColor: Color = MaterialTheme.colorScheme.surface,
    useDarkStatusBarIcons: Boolean,
    useDarkNavigationBarIcons: Boolean = useDarkStatusBarIcons,
    decorFitsSystemWindows: Boolean? = null,
    restoreDecorFitsSystemWindowsOnDispose: Boolean = false,
) {
    val view = LocalView.current
    if (view.isInEditMode) return

    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        decorFitsSystemWindows?.let { WindowCompat.setDecorFitsSystemWindows(window, it) }
        window.statusBarColor = statusBarColor.toArgb()
        window.navigationBarColor = navigationBarColor.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = useDarkStatusBarIcons
            isAppearanceLightNavigationBars = useDarkNavigationBarIcons
        }
    }

    if (restoreDecorFitsSystemWindowsOnDispose && decorFitsSystemWindows != null) {
        DisposableEffect(view) {
            onDispose {
                val window = view.context.findActivity()?.window
                if (window != null) {
                    WindowCompat.setDecorFitsSystemWindows(window, true)
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
