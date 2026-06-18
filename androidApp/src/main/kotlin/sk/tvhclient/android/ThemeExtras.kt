package sk.tvhclient.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** True ak je aktivna svetla schema (podla jasu povrchu) — funguje aj pri manualnom prepnuti. */
@Composable
fun isLightTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() > 0.5f

/**
 * Pozadie pod picon (logo kanala/radia). Picony su navrhnute pre tmave pozadie,
 * preto vo svetlom rezime davame neutralne sive plovo, nech biele loga nezaniknu.
 * V tmavom rezime jemne svetle prekrytie ako doteraz.
 */
@Composable
fun piconBackground(): Color =
    if (isLightTheme()) Color(0xFFB4B9C0) else Color(0x22FFFFFF)

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
@Composable fun playerScrim(): Color =
    if (isLightTheme()) Color(0xF2F2F2F6) else Color(0xE6000000)
@Composable fun playerScrimSoft(): Color =
    if (isLightTheme()) Color(0xC0F2F2F6) else Color(0x99000000)
