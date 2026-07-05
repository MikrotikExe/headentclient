package sk.tvhclient.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** True ak je aktivna svetla schema (podla jasu povrchu) — funguje aj pri manualnom prepnuti. */
@Composable
fun isLightTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() > 0.5f

/** True ak je zapnuty moderny rezim rozhrania (UiModePref) — reaguje zivo na zmenu. */
@Composable
fun isModernUi(): Boolean =
    UiModePref.stateOf(LocalContext.current).value == UiModePref.MODERN

/**
 * Pozadie pod picon (logo kanala/radia). Picony su navrhnute pre tmave pozadie,
 * preto vo svetlom rezime davame neutralne sive plovo, nech biele loga nezaniknu.
 * V tmavom rezime jemne svetle prekrytie ako doteraz.
 */
@Composable
fun piconBackground(): Color =
    if (isLightTheme()) Color(0xFFA2A8B4) else Color(0xFF353B47)

// --- Farby overlay-u prehravaca ---
// V tmavom rezime vracaju presne povodne hodnoty (vizualne nezmenene),
// vo svetlom rezime tmavy text / svetle panely (citatelne nad videom).
@Composable fun playerFg(): Color =
    if (isLightTheme()) Color(0xDE000000) else Color.White
@Composable fun playerFgDim(): Color =
    if (isLightTheme()) Color(0x99000000) else Color(0xCCFFFFFF)
@Composable fun playerFgFaint(): Color =
    if (isLightTheme()) Color(0x66000000) else Color(0x99FFFFFF)
@Composable fun playerTrack(): Color =
    if (isLightTheme()) Color(0x33000000) else Color(0x55FFFFFF)
@Composable fun playerScrim(): Color = when {
    isModernUi() && isLightTheme() -> Color(0xF2F0F4FB)
    isModernUi() -> Color(0xF20A1124)
    isLightTheme() -> Color(0xF2F2F2F6)
    else -> Color(0xE6000000)
}
@Composable fun playerScrimSoft(): Color = when {
    isModernUi() && isLightTheme() -> Color(0xC0F0F4FB)
    isModernUi() -> Color(0x990A1124)
    isLightTheme() -> Color(0xC0F2F2F6)
    else -> Color(0x99000000)
}

// --- Karty a ramiky (EPG browser) ---
@Composable fun playerBorder(): Color =
    if (isLightTheme()) Color(0x1F000000) else Color(0x33FFFFFF)
@Composable fun playerCard(): Color =
    if (isLightTheme()) Color(0x0D000000) else Color(0x14FFFFFF)
/** Akcent prehravaca: klasik modra; moderny rezim teal (tmavy/svetly variant). */
@Composable fun playerAccent(): Color = when {
    isModernUi() && isLightTheme() -> Color(0xFF0F8A63)
    isModernUi() -> Color(0xFF1D9E75)
    else -> Color(0xFF1E88E5)
}
// --- Surf overlay (moderny TV live panel, M351): light/dark varianty ---
/** Vertikalny gradient scrim pod kartami. */
@Composable fun overlayScrim(): List<Color> = if (isLightTheme())
    listOf(Color(0x00F0F4FB), Color(0xD9F0F4FB), Color(0xF7F0F4FB))
    else listOf(Color(0x000A1124), Color(0xD90A1124), Color(0xF70A1124))
/** Podklad karty/pilulky (nefokus). */
@Composable fun overlaySurface(): Color =
    if (isLightTheme()) Color(0xFFEAF0F9) else Color(0xFF13234A)
/** Podklad karty pri fokuse. */
@Composable fun overlaySurfaceFocus(): Color =
    if (isLightTheme()) Color(0xFFE4ECF7) else Color(0xFF12294E)
/** Podklad nefokus karty s jemnou prieh. (velke karty kanalov). */
@Composable fun overlayCard(): Color =
    if (isLightTheme()) Color(0xEAEAF0F9) else Color(0xE60F1E3D)
/** Obrys nefokus prvkov. */
@Composable fun overlayOutline(): Color =
    if (isLightTheme()) Color(0xFFC4D2E8) else Color(0xFF27407A)
/** Obrys nefokus velkej karty. */
@Composable fun overlayCardOutline(): Color =
    if (isLightTheme()) Color(0xFFC4D2E8) else Color(0xFF1E3A6E)
/** Text hintov a vedlajsich popiskov v overlayi. */
@Composable fun overlayHint(): Color =
    if (isLightTheme()) Color(0xFF5A6B85) else Color(0xFF8FA6C8)
/** Track progres barov v overlayi. */
@Composable fun overlayTrack(): Color =
    if (isLightTheme()) Color(0xFFC4D2E8) else Color(0xFF1B2C52)
/** "Live" zvyraznenie pri timeshift. */
@Composable fun overlayLive(): Color =
    if (isLightTheme()) Color(0xFF0F8A63) else Color(0xFF7FE3BF)
/** Farba ikony vo vnutri play tlacidla (kontrast voci teal). */
@Composable fun overlayOnAccent(): Color =
    if (isLightTheme()) Color.White else Color(0xFF04120C)
/** Obrys play tlacidla pri fokuse. */
@Composable fun overlayPlayFocusRing(): Color =
    if (isLightTheme()) Color(0xFF0F8A63) else Color(0xFF7FE3BF)

@Composable fun playerSelTint(): Color = when {
    isModernUi() && isLightTheme() -> Color(0x1F0F8A63)
    isModernUi() -> Color(0x331D9E75)
    isLightTheme() -> Color(0x1F1E88E5)
    else -> Color(0x331E88E5)
}
