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
