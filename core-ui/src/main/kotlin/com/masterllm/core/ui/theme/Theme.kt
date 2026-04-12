package com.masterllm.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7AC67A),
    onPrimary = Color(0xFF0E2C0F),
    primaryContainer = Color(0xFF214624),
    onPrimaryContainer = Color(0xFFC9F4C5),

    secondary = Color(0xFFD9A26B),
    onSecondary = Color(0xFF3D250B),
    secondaryContainer = Color(0xFF5A3D1F),
    onSecondaryContainer = Color(0xFFFFDEBC),

    tertiary = Color(0xFFCDAA92),
    onTertiary = Color(0xFF3E2A1F),
    tertiaryContainer = Color(0xFF5A4033),
    onTertiaryContainer = Color(0xFFFFDAC6),

    background = Color(0xFF12100E),
    onBackground = Color(0xFFEDE2D8),
    surface = Color(0xFF1A1714),
    onSurface = Color(0xFFEDE2D8),
    surfaceVariant = Color(0xFF34302B),
    onSurfaceVariant = Color(0xFFD0C6BC),
)

private val LightColorScheme = lightColorScheme(
    // Green Apple - Fresh, vibrant green
    primary = Color(0xFF4CAF50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F5E9),
    onPrimaryContainer = Color(0xFF1B5E20),

    // Cinnamon - Warm brown-orange
    secondary = Color(0xFF8D6E63),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E6DE),
    onSecondaryContainer = Color(0xFF3E2723),

    // Dark Coffee - Rich brown
    tertiary = Color(0xFF5D4037),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8DED8),
    onTertiaryContainer = Color(0xFF3E2723),

    // Creamy background - warm off-white
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF2C2419),
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF2C2419),
    surfaceVariant = Color(0xFFF0EBE5),
    onSurfaceVariant = Color(0xFF5D5145),
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
