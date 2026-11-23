package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val StudioDarkColorScheme = darkColorScheme(
    primary = StudioCyan,
    onPrimary = Color(0xFF001F26),
    primaryContainer = StudioCyanContainer,
    onPrimaryContainer = StudioCyan,
    secondary = StudioCoral,
    onSecondary = Color(0xFF2B0A04),
    secondaryContainer = StudioCoralContainer,
    onSecondaryContainer = StudioCoral,
    tertiary = StudioViolet,
    onTertiary = Color(0xFF19102E),
    tertiaryContainer = StudioVioletContainer,
    onTertiaryContainer = StudioViolet,
    background = StudioObsidian,
    onBackground = StudioTextPrimary,
    surface = StudioSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioElevated,
    onSurfaceVariant = StudioTextSecondary,
    outline = StudioBorder,
    outlineVariant = Color(0xFF1E2536),
    error = StudioError,
    onError = Color(0xFF2B040C)
)

val StudioShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ReelForgeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioDarkColorScheme,
        typography = StudioTypography,
        shapes = StudioShapes,
        content = content
    )
}
