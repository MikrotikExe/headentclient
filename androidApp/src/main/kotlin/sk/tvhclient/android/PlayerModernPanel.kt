package sk.tvhclient.android

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Moderny ovladaci panel prehravaca pre TELEFON (UiModePref.MODERN):
 * 3 hlavne tlacidla (prev / velke Play / next, pri timeshiftu aj -30/+30)
 * + pas 5 ikon s popiskami (Spat, PiP, Kanaly, Program, Viac). Zvysne
 * funkcie (zvuk, titulky, casovac, zamok, info) su vo vysuvacom paneli
 * ModernMoreSheet. Vsetky akcie su te iste lambdy ako klasicky panel —
 * meni sa len usporiadanie; klasik ostava nedotknuty.
 */
@Composable
internal fun ModernPhoneControls(
    isPlaying: Boolean,
    timeshiftEngaged: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    hasList: Boolean,
    hasEpg: Boolean,
    onClose: () -> Unit,
    onAudio: () -> Unit,
    onList: () -> Unit,
    onEpg: () -> Unit,
    onTogglePlay: () -> Unit,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onSkipBack: () -> Unit,
    onSkipFwd: () -> Unit,
    onMore: () -> Unit,
) {
    val accent = playerAccent()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // transport: prev | PLAY | next (+ timeshift skoky)
        Row(
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (timeshiftEngaged) ModernSmallCircle(Icons.Default.Replay30, onSkipBack)
            if (hasPrev && onPrev != null) ModernSmallCircle(Icons.Default.SkipPrevious, onPrev)
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .clickable { onTogglePlay() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color(0xFF04120C),
                    modifier = Modifier.size(38.dp)
                )
            }
            if (hasNext && onNext != null) ModernSmallCircle(Icons.Default.SkipNext, onNext)
            if (timeshiftEngaged) ModernSmallCircle(Icons.Default.Forward30, onSkipFwd)
        }
        // pas ikon s popiskami
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            ModernLabeled(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.pm_back), onClose)
            ModernLabeled(Icons.Default.MusicNote, stringResource(R.string.pm_audio), onAudio)
            if (hasList) ModernLabeled(Icons.AutoMirrored.Filled.List, stringResource(R.string.tab_channels), onList)
            if (hasEpg) ModernLabeled(Icons.Default.GridView, stringResource(R.string.home_tv_program), onEpg)
            ModernLabeled(Icons.Default.MoreHoriz, stringResource(R.string.pm_more), onMore)
        }
    }
}

@Composable
private fun ModernSmallCircle(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF).compositeOverPanel())
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = playerFg(), modifier = Modifier.size(26.dp))
    }
}

/** Jemne kruhove pozadie citatelne na navy aj svetlom paneli. */
@Composable
private fun Color.compositeOverPanel(): Color =
    if (isLightTheme()) Color(0x14000000) else Color(0x1FFFFFFF)

@Composable
private fun ModernLabeled(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onClick() }.padding(6.dp)
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0x00000000).compositeOverPanel()),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = playerFg(), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = playerFgDim(), fontSize = 11.sp, maxLines = 1)
    }
}

/**
 * Vysuvaci panel "Viac" zdola: zvukova stopa, titulky, casovac spanku,
 * zamok otocenia (ak je k dispozicii) a informacie o relacii. Stmavene
 * pozadie, tuknutim mimo panel sa zavrie.
 */
@Composable
internal fun ModernMoreSheet(
    lockVisible: Boolean,
    orientationLocked: Boolean,
    // PiP polozka (M349-fix5): len ked je Auto-PiP vypnuty — rucny vstup do PiP
    pipVisible: Boolean = false,
    onPip: () -> Unit = {},
    // M383: prepinac stream profilu (len HTTP live)
    profileVisible: Boolean = false,
    onProfile: () -> Unit = {},
    onSubs: () -> Unit,
    onSleep: () -> Unit,
    onLockToggle: () -> Unit,
    onInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x8C000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(playerScrim())
                .clickable(enabled = false) {}   // pohlti klik, nech nezavrie panel
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.size(width = 44.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(playerFgFaint())
                    .align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.pm_more), color = playerFg(),
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            ModernSheetRow(Icons.Default.ClosedCaption, stringResource(R.string.track_subtitles), onSubs)
            if (profileVisible) {
                ModernSheetRow(Icons.Default.Tune, stringResource(R.string.field_profile), onProfile)
            }
            if (pipVisible) {
                ModernSheetRow(Icons.Default.PictureInPictureAlt, stringResource(R.string.pm_pip), onPip)
            }
            ModernSheetRow(Icons.Default.Timer, stringResource(R.string.sleep_timer), onSleep)
            if (lockVisible) {
                ModernSheetRow(
                    Icons.Default.Lock,
                    stringResource(R.string.pm_lock),
                    onLockToggle,
                    value = if (orientationLocked) "ON" else null
                )
            }
            ModernSheetRow(Icons.Default.Info, stringResource(R.string.pm_info), onInfo)

            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(playerCard())
                    .clickable { onDismiss() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.pm_close), color = playerFg(), fontSize = 15.sp)
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun ModernSheetRow(icon: ImageVector, label: String, onClick: () -> Unit, value: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(playerCard())
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(playerSelTint()),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = playerFg(), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = playerFg(), fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, color = playerAccent(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
        }
        Text("\u203A", color = playerFgFaint(), fontSize = 18.sp)
    }
}


/**
 * Moderny riadok zoznamu kanalov v prehravaci — ina stavba nez klasik:
 * obsah na prvom mieste (velky picon, nazov kanala drobne NAD tucnym nazvom
 * prave beziacej relacie, "Dalej:" ktore klasik neukazuje), vpravo zostavajuce
 * minuty a decentne cislo kanala. Klasicky riadok ostava nezmeneny.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ModernPlayerChannelRow(
    ch: LivePlaylist.LiveChannel,
    selected: Boolean,
    locked: Boolean,
    nowSec: Long,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) playerSelTint() else playerCard())
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // velky picon
        Box(
            Modifier.size(52.dp, 42.dp).clip(RoundedCornerShape(10.dp)).background(piconBackground()),
            contentAlignment = Alignment.Center
        ) {
            if (ch.piconUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(ch.piconUrl).size(140).build(),
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(3.dp)
                )
            } else {
                Text(ch.name.take(3).uppercase(), color = playerFg(), fontSize = 11.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch.name, color = playerFgDim(), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (ch.recording) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFE53935)))
                }
                if (locked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(androidx.compose.material.icons.Icons.Filled.Lock, contentDescription = null,
                        tint = playerFgDim(), modifier = Modifier.size(13.dp))
                }
            }
            Text(
                ch.nowTitle.ifBlank { ch.name },
                color = playerFg(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (ch.nextTitle.isNotBlank()) {
                Text(
                    stringResource(R.string.mh_next) + " " + ch.nextTitle,
                    color = playerFgFaint(), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (ch.nowStop > ch.nowStart) {
                val total = (ch.nowStop - ch.nowStart).coerceAtLeast(1)
                val frac = (nowSec - ch.nowStart).coerceIn(0, total).toFloat() / total
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 3.dp),
                    color = playerAccent(), trackColor = playerTrack()
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (ch.number > 0) {
                Text(ch.number.toString(), color = playerAccent(),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            val left = if (ch.nowStop > nowSec && ch.nowStart > 0) ((ch.nowStop - nowSec) / 60).toInt() else null
            if (left != null) {
                Text("$left min", color = playerFgDim(), fontSize = 11.sp)
            }
        }
    }
}
