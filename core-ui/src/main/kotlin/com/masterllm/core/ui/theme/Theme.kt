package com.masterllm.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Solace therapeutic palette — calm, warm, safe
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8BBAD4),           // Soft blue
    onPrimary = Color(0xFF0E2C3F),
    primaryContainer = Color(0xFF1A4560),
    onPrimaryContainer = Color(0xFFC5E4F4),

    secondary = Color(0xFFA8D5BA),         // Soft sage green
    onSecondary = Color(0xFF1A3325),
    secondaryContainer = Color(0xFF2D5A42),
    onSecondaryContainer = Color(0xFFC5E8D2),

    tertiary = Color(0xFFF4A97F),          // Warm peach
    onTertiary = Color(0xFF3F2210),
    tertiaryContainer = Color(0xFF5A3520),
    onTertiaryContainer = Color(0xFFFFDBC8),

    background = Color(0xFF121618),        // Dark calm
    onBackground = Color(0xFFE2E6E8),
    surface = Color(0xFF1A1E20),
    onSurface = Color(0xFFE2E6E8),
    surfaceVariant = Color(0xFF2A3033),
    onSurfaceVariant = Color(0xFFBFC6CA),

    error = Color(0xFFE57373),
    onError = Color(0xFF2C0E0E),
    errorContainer = Color(0xFF5A1A1A),
    onErrorContainer = Color(0xFFFFB4AB),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF5B8DB8),           // Calm blue
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E8F5),
    onPrimaryContainer = Color(0xFF1A3D5C),

    secondary = Color(0xFFA8D5BA),         // Soft sage green
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4EFE0),
    onSecondaryContainer = Color(0xFF1A3325),

    tertiary = Color(0xFFF4A97F),          // Warm peach accent
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE8D6),
    onTertiaryContainer = Color(0xFF3F2210),

    background = Color(0xFFF8F6F3),        // Warm off-white
    onBackground = Color(0xFF2D2D2D),
    surface = Color.White,
    onSurface = Color(0xFF2D2D2D),
    surfaceVariant = Color(0xFFEFF4F8),
    onSurfaceVariant = Color(0xFF5D6870),

    error = Color(0xFFE53935),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun MasterLLMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MasterLLMTypography,
        content = content
    )
}
