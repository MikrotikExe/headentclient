package sk.tvhclient.android

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material.icons.filled.SportsHandball
import androidx.compose.material.icons.filled.SportsHockey
import androidx.compose.material.icons.filled.SportsMma
import androidx.compose.material.icons.filled.SportsMotorsports
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.material.icons.filled.SportsVolleyball
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Yard

/**
 * Jednotna paleta farebnych ikonovych cipov moderneho rezimu (M335).
 * Jediny zdroj pravdy pre farby a ikony kategorii/podzanrov — pouziva ju
 * archiv (telefon rady aj TV rail/dlazdice). Nastavenia, sidebar a
 * prihlasenie pouzivaju tie iste hex hodnoty vo svojich call sites.
 */
internal class MgChipColors(val bgL: Long, val fgL: Long, val bgD: Long, val fgD: Long)

internal val mgPalette = listOf(
    MgChipColors(0xFFFFE1E1, 0xFFD64545, 0xFF3A1D20, 0xFFEF8A88), // 0 cervena
    MgChipColors(0xFFE3E0FB, 0xFF6A5AD8, 0xFF241F45, 0xFFA99BF5), // 1 fialova
    MgChipColors(0xFFDCEFDA, 0xFF3D8B40, 0xFF16301C, 0xFF8ED492), // 2 zelena
    MgChipColors(0xFFFFECCC, 0xFFC07A17, 0xFF3A2B12, 0xFFE8B96A), // 3 oranzova
    MgChipColors(0xFFFDE2F1, 0xFFC2408F, 0xFF3A1730, 0xFFE88AC2), // 4 ruzova
    MgChipColors(0xFFD8F0FB, 0xFF1877A8, 0xFF12283A, 0xFF7CC4E8), // 5 modra
    MgChipColors(0xFFE0F2EF, 0xFF0F8A63, 0xFF0F2E22, 0xFF7FE3BF), // 6 teal
    MgChipColors(0xFFF0E9D8, 0xFF9A7B2D, 0xFF332C14, 0xFFD9C27A), // 7 jantarova
)

internal fun mgChipFor(key: String): MgChipColors = when (key) {
    "recent" -> mgPalette[6]
    "search" -> mgPalette[5]
    "all" -> mgPalette[7]
    "channels" -> mgPalette[5]
    "dates" -> mgPalette[6]
    "series" -> mgPalette[1]
    sk.tvhclient.shared.model.DvrClassifier.FILM -> mgPalette[0]
    sk.tvhclient.shared.model.DvrClassifier.SERIAL -> mgPalette[1]
    sk.tvhclient.shared.model.DvrClassifier.SPORT -> mgPalette[2]
    sk.tvhclient.shared.model.DvrClassifier.NEWS -> mgPalette[3]
    sk.tvhclient.shared.model.DvrClassifier.SHOW -> mgPalette[4]
    sk.tvhclient.shared.model.DvrClassifier.CHILDREN -> mgPalette[5]
    sk.tvhclient.shared.model.DvrClassifier.MUSIC -> mgPalette[6]
    sk.tvhclient.shared.model.DvrClassifier.ARTS -> mgPalette[1]
    sk.tvhclient.shared.model.DvrClassifier.DOCUMENTARY -> mgPalette[7]
    sk.tvhclient.shared.model.DvrClassifier.HOBBY -> mgPalette[2]
    sk.tvhclient.shared.model.DvrClassifier.OTHER -> mgPalette[3]
    else -> mgPalette[kotlin.math.abs(key.hashCode()) % mgPalette.size]
}

/** Vlastna ikona podzanru (M328); null = pouzije sa ikona rodicovskej kategorie. */
internal fun mgSubIcon(k: String): androidx.compose.ui.graphics.vector.ImageVector? = when (k) {
    sk.tvhclient.shared.model.DvrClassifier.MV_AKCNY -> Icons.Filled.LocalFireDepartment
    sk.tvhclient.shared.model.DvrClassifier.MV_KOMEDIA -> Icons.Filled.TheaterComedy
    sk.tvhclient.shared.model.DvrClassifier.MV_KRIMI -> Icons.Filled.LocalPolice
    sk.tvhclient.shared.model.DvrClassifier.MV_DRAMA -> Icons.Filled.Theaters
    sk.tvhclient.shared.model.DvrClassifier.MV_SCIFI -> Icons.Filled.RocketLaunch
    sk.tvhclient.shared.model.DvrClassifier.MV_ROMANTIKA -> Icons.Filled.Favorite
    sk.tvhclient.shared.model.DvrClassifier.MV_HOROR -> Icons.Filled.DarkMode
    sk.tvhclient.shared.model.DvrClassifier.MV_DOBRODR -> Icons.Filled.Explore
    sk.tvhclient.shared.model.DvrClassifier.MV_ANIMAK -> Icons.Filled.Animation
    sk.tvhclient.shared.model.DvrClassifier.MV_HISTORICKY -> Icons.Filled.AccountBalance
    sk.tvhclient.shared.model.DvrClassifier.MV_WESTERN -> Icons.Filled.Landscape
    sk.tvhclient.shared.model.DvrClassifier.SP_FUTBAL -> Icons.Filled.SportsSoccer
    sk.tvhclient.shared.model.DvrClassifier.SP_HOKEJ -> Icons.Filled.SportsHockey
    sk.tvhclient.shared.model.DvrClassifier.SP_BASKETBAL -> Icons.Filled.SportsBasketball
    sk.tvhclient.shared.model.DvrClassifier.SP_TENIS -> Icons.Filled.SportsTennis
    sk.tvhclient.shared.model.DvrClassifier.SP_VOLEJBAL -> Icons.Filled.SportsVolleyball
    sk.tvhclient.shared.model.DvrClassifier.SP_HADZANA -> Icons.Filled.SportsHandball
    sk.tvhclient.shared.model.DvrClassifier.SP_BOJOVE -> Icons.Filled.SportsMma
    sk.tvhclient.shared.model.DvrClassifier.SP_ATLETIKA -> Icons.Filled.DirectionsRun
    sk.tvhclient.shared.model.DvrClassifier.SP_CYKLISTIKA -> Icons.Filled.DirectionsBike
    sk.tvhclient.shared.model.DvrClassifier.SP_MOTORSPORT -> Icons.Filled.SportsMotorsports
    sk.tvhclient.shared.model.DvrClassifier.SP_ZIMNE -> Icons.Filled.AcUnit
    sk.tvhclient.shared.model.DvrClassifier.SP_VODNE -> Icons.Filled.Pool
    sk.tvhclient.shared.model.DvrClassifier.SP_NEWS -> Icons.Filled.Newspaper
    sk.tvhclient.shared.model.DvrClassifier.NW_HLAVNE -> Icons.Filled.Newspaper
    sk.tvhclient.shared.model.DvrClassifier.NW_POLITIKA -> Icons.Filled.Gavel
    sk.tvhclient.shared.model.DvrClassifier.NW_KRIMI -> Icons.Filled.LocalPolice
    sk.tvhclient.shared.model.DvrClassifier.NW_MAGAZINY -> Icons.Filled.Article
    sk.tvhclient.shared.model.DvrClassifier.NW_POCASIE -> Icons.Filled.WbSunny
    sk.tvhclient.shared.model.DvrClassifier.SH_REALITY -> Icons.Filled.Videocam
    sk.tvhclient.shared.model.DvrClassifier.SH_TALK -> Icons.Filled.RecordVoiceOver
    sk.tvhclient.shared.model.DvrClassifier.SH_SUTAZ -> Icons.Filled.EmojiEvents
    sk.tvhclient.shared.model.DvrClassifier.SH_KUCHARSKE -> Icons.Filled.Restaurant
    sk.tvhclient.shared.model.DvrClassifier.SH_ZABAVA -> Icons.Filled.Celebration
    sk.tvhclient.shared.model.DvrClassifier.SH_MAGAZINY -> Icons.Filled.Article
    sk.tvhclient.shared.model.DvrClassifier.CH_ANIMAK -> Icons.Filled.Animation
    sk.tvhclient.shared.model.DvrClassifier.CH_ROZPRAVKY -> Icons.Filled.AutoStories
    sk.tvhclient.shared.model.DvrClassifier.CH_VZDELAVAC -> Icons.Filled.School
    sk.tvhclient.shared.model.DvrClassifier.CH_FILMY -> Icons.Filled.Movie
    sk.tvhclient.shared.model.DvrClassifier.MU_KLASIKA -> Icons.Filled.Piano
    sk.tvhclient.shared.model.DvrClassifier.MU_KONCERT -> Icons.Filled.Mic
    sk.tvhclient.shared.model.DvrClassifier.MU_HITY -> Icons.Filled.Star
    sk.tvhclient.shared.model.DvrClassifier.MU_FOLK -> Icons.Filled.MusicNote
    sk.tvhclient.shared.model.DvrClassifier.MU_MAGAZINY -> Icons.Filled.Article
    sk.tvhclient.shared.model.DvrClassifier.AR_DIVADLO -> Icons.Filled.TheaterComedy
    sk.tvhclient.shared.model.DvrClassifier.AR_VYTVARNE -> Icons.Filled.Palette
    sk.tvhclient.shared.model.DvrClassifier.AR_LITERATURA -> Icons.Filled.MenuBook
    sk.tvhclient.shared.model.DvrClassifier.AR_FILM -> Icons.Filled.Movie
    sk.tvhclient.shared.model.DvrClassifier.DC_PRIRODA -> Icons.Filled.Forest
    sk.tvhclient.shared.model.DvrClassifier.DC_HISTORIA -> Icons.Filled.AccountBalance
    sk.tvhclient.shared.model.DvrClassifier.DC_VEDA -> Icons.Filled.Science
    sk.tvhclient.shared.model.DvrClassifier.DC_CESTOPIS -> Icons.Filled.Public
    sk.tvhclient.shared.model.DvrClassifier.DC_OSOBNOSTI -> Icons.Filled.Person
    sk.tvhclient.shared.model.DvrClassifier.DC_SPOLOCNOST -> Icons.Filled.Groups
    sk.tvhclient.shared.model.DvrClassifier.HB_ZAHRADA -> Icons.Filled.Yard
    sk.tvhclient.shared.model.DvrClassifier.HB_BYVANIE -> Icons.Filled.Chair
    sk.tvhclient.shared.model.DvrClassifier.HB_VARENIE -> Icons.Filled.Restaurant
    sk.tvhclient.shared.model.DvrClassifier.HB_AUTO -> Icons.Filled.DirectionsCar
    sk.tvhclient.shared.model.DvrClassifier.HB_CESTOVANIE -> Icons.Filled.FlightTakeoff
    sk.tvhclient.shared.model.DvrClassifier.HB_ZDRAVIE -> Icons.Filled.FitnessCenter
    sk.tvhclient.shared.model.DvrClassifier.HB_DIY -> Icons.Filled.Build
    else -> null
}

internal fun mgIconFor(rawKey: String): androidx.compose.ui.graphics.vector.ImageVector { 
    // Podzaner ma prednostne vlastnu ikonu ("kat|sub" aj samotny sub kluc);
    // *_ine a nezname padaju na ikonu rodicovskej kategorie.
    mgSubIcon(rawKey.substringAfter('|', ""))?.let { return it }
    mgSubIcon(rawKey)?.let { return it }
    val key = rawKey.substringBefore('|'); return when {
    key == "recent" -> Icons.Filled.PlayArrow
    key == "search" -> Icons.Filled.Search
    key == "all" -> Icons.Filled.VideoLibrary
    key == "channels" -> Icons.Filled.LiveTv
    key == "dates" -> Icons.Filled.CalendarMonth
    key == "series" -> Icons.Filled.VideoLibrary
    key == sk.tvhclient.shared.model.DvrClassifier.FILM || key.startsWith("mv_") -> Icons.Filled.Movie
    key == sk.tvhclient.shared.model.DvrClassifier.SERIAL -> Icons.Filled.Tv
    key == sk.tvhclient.shared.model.DvrClassifier.SPORT || key.startsWith("sp_") -> Icons.Filled.SportsSoccer
    key == sk.tvhclient.shared.model.DvrClassifier.NEWS || key.startsWith("nw_") -> Icons.Filled.Newspaper
    key == sk.tvhclient.shared.model.DvrClassifier.SHOW || key.startsWith("sh_") -> Icons.Filled.Star
    key == sk.tvhclient.shared.model.DvrClassifier.CHILDREN || key.startsWith("ch_") -> Icons.Filled.ChildCare
    key == sk.tvhclient.shared.model.DvrClassifier.MUSIC || key.startsWith("mu_") -> Icons.Filled.MusicNote
    key == sk.tvhclient.shared.model.DvrClassifier.ARTS || key.startsWith("ar_") -> Icons.Filled.Palette
    key == sk.tvhclient.shared.model.DvrClassifier.DOCUMENTARY || key.startsWith("dc_") -> Icons.Filled.Description
    key == sk.tvhclient.shared.model.DvrClassifier.HOBBY -> Icons.Filled.Handyman
    else -> Icons.Filled.Category
} }

/** Karta priecinka moderneho archivu: farebny ikonovy cip + tucny nazov + badge poctu + sipka. */
@Composable
private fun ModernFolderRow(
    label: String,
    sub: String,
    iconKey: String,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val light = isLightTheme()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (light) cs.surfaceContainerLowest else cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp))
            .dpadFocusable(RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
        } else {
            val chip = mgChipFor(iconKey)
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Color(if (light) chip.bgL else chip.bgD)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    mgIconFor(iconKey), contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(if (light) chip.fgL else chip.fgD),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            label, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (light) cs.surfaceContainer else cs.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                sub,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = cs.onSurfaceVariant,
                maxLines = 1
            )
        }
        Text(
            "  \u203A",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}
