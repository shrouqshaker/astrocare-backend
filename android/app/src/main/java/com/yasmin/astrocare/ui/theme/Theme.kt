package com.yasmin.astrocare.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = OrbitBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1EAFF),
    onPrimaryContainer = Color(0xFF173A7B),
    secondary = StarlightCyan,
    onSecondary = DeepSpaceNavy,
    secondaryContainer = Color(0xFFD5F3F6),
    onSecondaryContainer = Color(0xFF12434B),
    tertiary = StatusNormal,
    background = LightBackground,
    onBackground = OnSurfaceLight,
    surface = LightSurface,
    onSurface = OnSurfaceLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerLow = LightSurface,
    surfaceContainerHigh = Color(0xFFDCE6F4),
    outline = Color(0xFF7989A1),
    outlineVariant = Color(0xFFCED8E7),
    error = StatusCritical,
    onError = Color.White,
    errorContainer = Color(0xFFFFE3E9),
    onErrorContainer = Color(0xFF842239),
)

private val DarkColorScheme = darkColorScheme(
    primary = OrbitIndigoSoft,
    onPrimary = DeepSpaceNavy,
    primaryContainer = Color(0xFF234573),
    onPrimaryContainer = Color(0xFFE1ECFF),
    secondary = StarlightCyan,
    onSecondary = DeepSpaceNavy,
    secondaryContainer = Color(0xFF164A54),
    onSecondaryContainer = Color(0xFFD3FAFF),
    tertiary = Color(0xFF7EE3AA),
    background = DeepSpaceNavy,
    onBackground = OnSurfaceDark,
    surface = NebulaSurface,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainer = SurfaceVariantDark,
    surfaceContainerLow = NebulaSurface,
    surfaceContainerHigh = Color(0xFF263F60),
    outline = Color(0xFF8A9BB6),
    outlineVariant = Color(0xFF405774),
    error = Color(0xFFFF92A5),
    onError = Color(0xFF640D23),
    errorContainer = Color(0xFF572538),
    onErrorContainer = Color(0xFFFFDBE3),
)

private val CareShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AstroCareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = CareShapes,
        content = content,
    )
}