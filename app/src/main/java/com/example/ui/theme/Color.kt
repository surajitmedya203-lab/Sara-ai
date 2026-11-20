package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Luminous Obsidian & Cyber Hologram Palette
val ObsidianNight = Color(0xFF070B14)
val DeepNebula = Color(0xFF0D1426)
val GlassSurface = Color(0xFF141E36)
val GlassSurfaceElevated = Color(0xFF1B2847)
val GlassBorder = Color(0xFF283A63)

val ElectricCyan = Color(0xFF38BDF8)
val NeonCyan = Color(0xFF00E5FF)
val LuminousViolet = Color(0xFF8B5CF6)
val SoftIris = Color(0xFFA78BFA)
val WarmCoralRose = Color(0xFFFB7185)
val EmeraldPulse = Color(0xFF34D399)
val AmberGlow = Color(0xFFFBBF24)

val IceWhite = Color(0xFFF1F5F9)
val MutedSlate = Color(0xFF94A3B8)
val SubtleSlate = Color(0xFF64748B)

// Light palette equivalents
val MistBackground = Color(0xFFF3F7FD)
val CrispSurface = Color(0xFFFFFFFF)
val SoftIceContainer = Color(0xFFE6EEF9)
val DeepInk = Color(0xFF0F172A)
val SlateText = Color(0xFF475569)
val CyanPrimaryDark = Color(0xFF0284C7)
val VioletSecondaryDark = Color(0xFF6D28D9)
val RoseTertiaryDark = Color(0xFFE11D48)

enum class SaraShaderStyle(
    val id: String,
    val label: String,
    val labelBn: String,
    val primaryColor: Color,
    val secondaryColor: Color,
    val accentColor: Color
) {
    HOLOGRAM_CYAN(
        id = "HOLOGRAM_CYAN",
        label = "Cyber Cyan",
        labelBn = "সাইবার সায়ান",
        primaryColor = Color(0xFF38BDF8),
        secondaryColor = Color(0xFF8B5CF6),
        accentColor = Color(0xFF00F5D4)
    ),
    AURORA_VIOLET(
        id = "AURORA_VIOLET",
        label = "Nebula Violet",
        labelBn = "নেবুলা ভায়োলেট",
        primaryColor = Color(0xFFA78BFA),
        secondaryColor = Color(0xFF38BDF8),
        accentColor = Color(0xFFF472B6)
    ),
    SOLAR_ROSE(
        id = "SOLAR_ROSE",
        label = "Aurora Rose",
        labelBn = "অরোরা রোজ",
        primaryColor = Color(0xFFFB7185),
        secondaryColor = Color(0xFFFBBF24),
        accentColor = Color(0xFF38BDF8)
    );

    companion object {
        fun fromId(id: String): SaraShaderStyle = entries.firstOrNull { it.id == id } ?: HOLOGRAM_CYAN
    }
}
