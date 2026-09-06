package com.diting.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Colour tokens transcribed from the Stitch mockups in
 * `project/uploads/stitch_ai/*/code.html`, which all share one Material 3 scheme.
 * Values are copied verbatim — do not "round" them.
 */
private val Primary = Color(0xFF006B5F)
private val OnPrimary = Color(0xFFFFFFFF)
private val PrimaryContainer = Color(0xFF12A594)
private val OnPrimaryContainer = Color(0xFF00322C)
private val Secondary = Color(0xFF8D4F00)
private val OnSecondary = Color(0xFFFFFFFF)
private val SecondaryContainer = Color(0xFFFFA449)
private val OnSecondaryContainer = Color(0xFF6F3D00)
private val Tertiary = Color(0xFF516169)
private val OnTertiary = Color(0xFFFFFFFF)
private val TertiaryContainer = Color(0xFF85959F)
private val OnTertiaryContainer = Color(0xFF1F2E35)
private val ErrorColor = Color(0xFFBA1A1A)
private val OnError = Color(0xFFFFFFFF)
private val ErrorContainer = Color(0xFFFFDAD6)
private val OnErrorContainer = Color(0xFF93000A)
private val Background = Color(0xFFFAF9F4)
private val OnBackground = Color(0xFF1B1C19)
private val Surface = Color(0xFFFAF9F4)
private val OnSurface = Color(0xFF1B1C19)
private val SurfaceVariant = Color(0xFFE3E3DE)
private val OnSurfaceVariant = Color(0xFF3D4946)
private val Outline = Color(0xFF6D7A76)
private val OutlineVariant = Color(0xFFBCC9C5)
private val InverseSurface = Color(0xFF30312E)
private val InverseOnSurface = Color(0xFFF2F1EC)
private val InversePrimary = Color(0xFF5FDAC7)

/**
 * The semantic colours the design leans on, which Material 3 has no slot for.
 *
 * The three rails are the design's core vocabulary and appear on the cover slide
 * as a legend: mint = 原声 / 理解, slate = 小谛生成, amber = 行动 / 待确认. A user
 * reads a card's left border before they read its text, so these must stay
 * consistent across every screen.
 */
@Immutable
data class DitingColors(
    /** 薄荷 — verbatim audio and understanding of it. */
    val railTranscript: Color = Color(0xFF12A594),
    /** 石板青 — content 小谛 generated rather than heard. */
    val railInsight: Color = Color(0xFF5B7380),
    /** 琥珀 — action, and anything waiting on the user. */
    val railAction: Color = Color(0xFFC9781E),
    /** Risks and blockers. */
    val railBlocker: Color = Color(0xFFD04838),

    /** Page background. */
    val paperRoot: Color = Color(0xFFF4F3EE),
    /** Ordinary card. */
    val paperCard: Color = Color(0xFFFAF9F5),
    /** Raised card. */
    val paperElevated: Color = Color(0xFFFFFFFF),
    /** Recessed wells and code blocks. */
    val paperSunken: Color = Color(0xFFEBE9E1),

    val inkMuted: Color = Color(0xFF8C9793),
    /** Dark panels — the "北极星闭环" strip and the review card. */
    val inkPanel: Color = Color(0xFF1B2420),
    val onInkPanel: Color = Color(0xFFE8ECE5),
    val accentOnPanel: Color = Color(0xFF37C9B6),
    val amberOnPanel: Color = Color(0xFFE0A24A),
)

private val LightDitingColors = DitingColors()

/**
 * Dark values are re-derived rather than reused: the light rails are tuned for a
 * paper ground and go muddy on a dark one. Hue is preserved so the legend still
 * reads the same.
 */
private val DarkDitingColors = DitingColors(
    railTranscript = Color(0xFF37C9B6),
    railInsight = Color(0xFF8FA5B0),
    railAction = Color(0xFFE0A24A),
    railBlocker = Color(0xFFE9705F),
    paperRoot = Color(0xFF14171A),
    paperCard = Color(0xFF1C2023),
    paperElevated = Color(0xFF23282B),
    paperSunken = Color(0xFF101315),
    inkMuted = Color(0xFF8E9A96),
    inkPanel = Color(0xFF0D1113),
    onInkPanel = Color(0xFFE8ECE5),
)

val LocalDitingColors = staticCompositionLocalOf { LightDitingColors }

private val LightScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = ErrorColor,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    inversePrimary = InversePrimary,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5FDAC7),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF7EF7E3),
    secondary = Color(0xFFFFB876),
    onSecondary = Color(0xFF4C2700),
    secondaryContainer = Color(0xFF6B3B00),
    onSecondaryContainer = Color(0xFFFFDCC0),
    tertiary = Color(0xFFB9C9D3),
    onTertiary = Color(0xFF243239),
    tertiaryContainer = Color(0xFF3A4951),
    onTertiaryContainer = Color(0xFFD5E5EF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF14171A),
    onBackground = Color(0xFFE3E3DE),
    surface = Color(0xFF14171A),
    onSurface = Color(0xFFE3E3DE),
    surfaceVariant = Color(0xFF3D4946),
    onSurfaceVariant = Color(0xFFBCC9C5),
    outline = Color(0xFF869390),
    outlineVariant = Color(0xFF3D4946),
    inverseSurface = Color(0xFFE3E3DE),
    inverseOnSurface = Color(0xFF2F312E),
    inversePrimary = Color(0xFF006B5F),
)

/**
 * The design pairs a serif display face with a sans body and a mono for
 * timestamps. Noto Serif SC / Noto Sans SC are not bundled — shipping three CJK
 * faces would add tens of megabytes — so this maps to the platform families and
 * keeps the *weight and size* relationships, which is what carries the
 * typographic hierarchy on a phone.
 */
private val DitingTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.2.sp,
    ),
)

/** Monospace, for the timestamps that anchor "↩ 回到原声". */
val TimestampStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
)

@Composable
fun DitingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalDitingColors provides if (darkTheme) DarkDitingColors else LightDitingColors
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = DitingTypography,
            content = content,
        )
    }
}

/** Shorthand for the semantic palette. */
val ditingColors: DitingColors
    @Composable get() = LocalDitingColors.current
