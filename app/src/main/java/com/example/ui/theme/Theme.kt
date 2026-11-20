package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SaraDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF02131D),
    primaryContainer = Color(0xFF0E2C47),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = LuminousViolet,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF241B4D),
    onSecondaryContainer = Color(0xFFDDD6FE),
    tertiary = WarmCoralRose,
    onTertiary = Color(0xFF2B0712),
    tertiaryContainer = Color(0xFF3E1322),
    onTertiaryContainer = Color(0xFFFFD8E0),
    background = ObsidianNight,
    onBackground = IceWhite,
    surface = DeepNebula,
    onSurface = IceWhite,
    surfaceVariant = GlassSurface,
    onSurfaceVariant = MutedSlate,
    surfaceContainerLowest = ObsidianNight,
    surfaceContainerLow = DeepNebula,
    surfaceContainer = GlassSurface,
    surfaceContainerHigh = GlassSurfaceElevated,
    outline = GlassBorder,
    outlineVariant = Color(0xFF1E293B),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0808)
)

private val SaraLightColorScheme = lightColorScheme(
    primary = CyanPrimaryDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF082F49),
    secondary = VioletSecondaryDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF2E1065),
    tertiary = RoseTertiaryDark,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE4E6),
    onTertiaryContainer = Color(0xFF4C0519),
    background = MistBackground,
    onBackground = DeepInk,
    surface = CrispSurface,
    onSurface = DeepInk,
    surfaceVariant = SoftIceContainer,
    onSurfaceVariant = SlateText,
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0)
)

val SaraShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SaraTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) SaraDarkColorScheme else SaraLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SaraTypography,
        shapes = SaraShapes,
        content = content
    )
}
