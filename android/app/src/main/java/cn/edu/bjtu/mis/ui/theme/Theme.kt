package cn.edu.bjtu.mis.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

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

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun BjtuMisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightScheme,
        shapes = AppShapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}
