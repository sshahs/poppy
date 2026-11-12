package app.poppy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.poppy.core.ThemeMode

// Poppy palette: a poppy-red accent on the black & white of a Lakeland Terrier coat.
val PoppyRed = Color(0xFFD93A35)
private val PoppyRedDark = Color(0xFFFFB4AB)

private val Light = lightColorScheme(
    primary = PoppyRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF3B3B3B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E5E4),
    onSecondaryContainer = Color(0xFF1C1B1B),
    tertiary = Color(0xFF8A5A2B),
    tertiaryContainer = Color(0xFFFFDCC0),
    background = Color(0xFFFFFBFA),
    onBackground = Color(0xFF1C1B1B),
    surface = Color(0xFFFFFBFA),
    onSurface = Color(0xFF1C1B1B),
    surfaceVariant = Color(0xFFF1EDEC),
    onSurfaceVariant = Color(0xFF524443),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9F4F3),
    surfaceContainer = Color(0xFFF3EEED),
    surfaceContainerHigh = Color(0xFFEDE8E7),
    surfaceContainerHighest = Color(0xFFE7E2E1),
    outline = Color(0xFF857372),
    outlineVariant = Color(0xFFD8C2BF),
    error = Color(0xFFBA1A1A),
)

private val Dark = darkColorScheme(
    primary = PoppyRedDark,
    onPrimary = Color(0xFF690005),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFCAC5C4),
    onSecondary = Color(0xFF313030),
    secondaryContainer = Color(0xFF3A3838),
    onSecondaryContainer = Color(0xFFE6E1E0),
    tertiary = Color(0xFFFFB77A),
    tertiaryContainer = Color(0xFF6D3F12),
    background = Color(0xFF141212),
    onBackground = Color(0xFFE6E1E0),
    surface = Color(0xFF141212),
    onSurface = Color(0xFFE6E1E0),
    surfaceVariant = Color(0xFF2B2828),
    onSurfaceVariant = Color(0xFFD8C2BF),
    surfaceContainerLowest = Color(0xFF0F0D0D),
    surfaceContainerLow = Color(0xFF1C1A1A),
    surfaceContainer = Color(0xFF211E1E),
    surfaceContainerHigh = Color(0xFF2B2828),
    surfaceContainerHighest = Color(0xFF363333),
    outline = Color(0xFFA08C8A),
    outlineVariant = Color(0xFF534341),
)

private fun ColorScheme.amoled() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B),
    surfaceContainer = Color(0xFF121212),
)

private val PoppyShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PoppyTheme(mode: ThemeMode, dynamic: Boolean, amoled: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = remember(dark, dynamic, amoled) {
        val base = when {
            dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> Dark
            else -> Light
        }
        if (dark && amoled) base.amoled() else base
    }
    MaterialTheme(colorScheme = scheme, shapes = PoppyShapes, content = content)
}
