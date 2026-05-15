package cn.edu.bjtu.mis.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class AppThemeOption(
    val storageValue: String,
) {
    Default("default"),
    MascotGold("mascot_gold");

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

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun BjtuMisTheme(
    themeOption: AppThemeOption = AppThemeOption.Default,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (themeOption) {
        AppThemeOption.Default -> LightScheme
        AppThemeOption.MascotGold -> MascotGoldScheme
    }
    val isDark = themeOption == AppThemeOption.MascotGold

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = MaterialTheme.typography,
    ) {
        BjtuMisSystemBars(
            useDarkStatusBarIcons = !isDark,
            useDarkNavigationBarIcons = !isDark,
        )
        content()
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
