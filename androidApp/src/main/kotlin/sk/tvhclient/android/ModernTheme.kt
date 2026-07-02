package sk.tvhclient.android

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Farebna schema moderneho UI rezimu (UiModePref.MODERN): tmave navy povrchy
 * + teal/zeleny akcent (brand HeadentClient). Kedze vsetky obrazovky citaju
 * MaterialTheme.colorScheme, prepnutim schemy dostanu moderny vzhlad EPG,
 * archiv, nastavenia aj dialogy bez prepisovania jednotlivych obrazoviek.
 * Fokus D-padu (dpadFocusable) pouziva primary -> teal ramiky automaticky.
 * Moderny rezim je zamerne vzdy tmavy (navy), bez ohladu na svetlu/tmavu temu.
 */
fun modernColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF1D9E75),
    onPrimary = Color(0xFF04120C),
    primaryContainer = Color(0xFF0F2E22),
    onPrimaryContainer = Color(0xFF7FE3BF),
    secondary = Color(0xFF2BB6D6),
    onSecondary = Color(0xFF041318),
    secondaryContainer = Color(0xFF0F2A3A),
    onSecondaryContainer = Color(0xFF9FE0F2),
    tertiary = Color(0xFF2BB6D6),
    background = Color(0xFF0A1124),
    onBackground = Color(0xFFE8EEFB),
    surface = Color(0xFF0C1730),
    onSurface = Color(0xFFE8EEFB),
    surfaceVariant = Color(0xFF13234A),
    onSurfaceVariant = Color(0xFF9FB4D8),
    surfaceContainerLowest = Color(0xFF08101F),
    surfaceContainerLow = Color(0xFF0B1528),
    surfaceContainer = Color(0xFF0F1E3D),
    surfaceContainerHigh = Color(0xFF13234A),
    surfaceContainerHighest = Color(0xFF182B55),
    outline = Color(0xFF27407A),
    outlineVariant = Color(0xFF1B2C52),
)
