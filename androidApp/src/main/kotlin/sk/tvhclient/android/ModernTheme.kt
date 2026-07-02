package sk.tvhclient.android

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Farebne schemy moderneho UI rezimu (UiModePref.MODERN). Tmavy variant:
 * navy povrchy + teal akcent; svetly variant: vzdusna modro-biela + teal.
 * Rezim respektuje volbu temy (svetla/tmava/auto) ako klasik. Kedze vsetky
 * obrazovky citaju MaterialTheme.colorScheme, prepnutim schemy dostanu moderny
 * vzhlad EPG, archiv, nastavenia aj dialogy bez prepisovania jednotlivych
 * obrazoviek; fokus D-padu (dpadFocusable) pouziva primary -> teal ramiky.
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

/** Svetly variant modernej schemy: teal akcent na vzdusnych modro-bielych povrchoch. */
fun modernLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF0F8A63),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBFEBD9),
    onPrimaryContainer = Color(0xFF0A3D2C),
    secondary = Color(0xFF0E7FA0),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDEFF9),
    onSecondaryContainer = Color(0xFF0A3140),
    tertiary = Color(0xFF0E7FA0),
    background = Color(0xFFF4F7FC),
    onBackground = Color(0xFF16233F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16233F),
    surfaceVariant = Color(0xFFE4EBF7),
    onSurfaceVariant = Color(0xFF4A5B7D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F4FB),
    surfaceContainer = Color(0xFFE8EEF9),
    surfaceContainerHigh = Color(0xFFDFE7F5),
    surfaceContainerHighest = Color(0xFFD6E0F1),
    outline = Color(0xFFA9BBDD),
    outlineVariant = Color(0xFFC8D4EA),
)
