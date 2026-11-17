package dev.pocketserver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette: deep ink surfaces with a terminal-green accent.
val Bg = Color(0xFF0B0F14)
val Surface1 = Color(0xFF131A22)
val Surface2 = Color(0xFF1B2530)
val BorderC = Color(0xFF24313D)
val FgC = Color(0xFFE8EEF4)
val MutedC = Color(0xFF8FA3B8)
val Accent = Color(0xFF22C55E)
val AccentDim = Color(0xFF0F2A1B)
val AccentBorder = Color(0xFF2E6B45)
val WarnC = Color(0xFFF59E0B)
val DangerC = Color(0xFFEF4444)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04140A),
    primaryContainer = AccentDim,
    onPrimaryContainer = Accent,
    secondary = MutedC,
    onSecondary = Bg,
    secondaryContainer = Surface2,
    onSecondaryContainer = FgC,
    background = Bg,
    onBackground = FgC,
    surface = Surface1,
    onSurface = FgC,
    surfaceVariant = Surface2,
    onSurfaceVariant = MutedC,
    outline = BorderC,
    outlineVariant = BorderC,
    error = DangerC,
    onError = Bg
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp,
        color = FgC
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        color = FgC
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        color = FgC
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
        color = MutedC
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        color = MutedC
    )
)

val Mono = FontFamily.Monospace

@Composable
fun PocketServerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography, content = content)
}
