package com.vibemusic.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Палитра в духе Qobuz: глубокий тёмный фон с синевой, циановый акцент,
// editorial-заголовки антиквой (serif). Поменять всю тему можно здесь.
val DeepBackground = Color(0xFF0A0B10)
val SurfaceLow = Color(0xFF12141B)
val SurfaceHigh = Color(0xFF1A1D26)
val Accent = Color(0xFFFF5C8A)
val TextPrimary = Color(0xFFF2F4F8)
val TextSecondary = Color(0xFF9AA3B2)

private val VibeColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = Accent,
    background = DeepBackground,
    onBackground = TextPrimary,
    surface = SurfaceLow,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceLow,
    surfaceContainerLow = SurfaceLow,
    surfaceContainerHigh = SurfaceHigh,
    outline = Color(0xFF2A2F3A),
)

private val VibeTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun VibeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VibeColorScheme,
        typography = VibeTypography,
        content = content,
    )
}
