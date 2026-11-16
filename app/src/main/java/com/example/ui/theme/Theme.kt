package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val PsxNovaDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianBg,
    primaryContainer = CyanContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = PsVioletAccent,
    onSecondary = TextPrimary,
    secondaryContainer = SlateSurfaceElevated,
    onSecondaryContainer = TextPrimary,
    tertiary = PsTriangleGreen,
    onTertiary = ObsidianBg,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = SlateSurfaceElevated,
    surfaceContainerHigh = SlateSurfaceBright,
    outline = SlateOutline,
    outlineVariant = SlateOutline.copy(alpha = 0.5f),
    error = StatusError,
    onError = ObsidianBg
)

val PsxNovaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PsxNovaTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PsxNovaDarkColorScheme,
        typography = Typography,
        shapes = PsxNovaShapes,
        content = content
    )
}
