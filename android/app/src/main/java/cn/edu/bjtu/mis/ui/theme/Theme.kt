package cn.edu.bjtu.mis.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFB34F1F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCA),
    onPrimaryContainer = Color(0xFF3C1400),
    secondary = Color(0xFF6F5B4E),
    onSecondary = Color.White,
    background = Color(0xFFFFF8F0),
    onBackground = Color(0xFF241A14),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF241A14),
    surfaceVariant = Color(0xFFF2DED2),
    onSurfaceVariant = Color(0xFF52443C),
)

@Composable
fun BjtuMisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
