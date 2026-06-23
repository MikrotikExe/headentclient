package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Dvr
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uvodna "launcher" obrazovka pre Android TV / set-top box. Pouzivatel si najprv
 * vyberie sekciu (Kanaly, Radia, TV program, Archiv, Nastavenia). Hore datum (vlavo)
 * a cas (vpravo). Plne ovladatelne dialkovym (D-pad) cez fokus.
 */
@Composable
fun TvHomeScreen(
    focusKey: String = "channels",
    onChannels: () -> Unit,
    onRadio: () -> Unit,
    onTvProgram: () -> Unit,
    onArchive: () -> Unit,
    onSettings: () -> Unit,
) {
    val dark = !isLightTheme()
    val bg = if (dark)
        Brush.verticalGradient(listOf(Color(0xFF0B1220), Color(0xFF10193A)))
    else
        Brush.verticalGradient(
            listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)
        )
    val fg = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val fgDim = fg.copy(alpha = 0.65f)

    // Hodiny: prepocet datumu/casu kazdu pol minutu
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) }
    }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val dateStr = remember(now) { SimpleDateFormat("EEEE d. MMMM", locale).format(Date(now)) }
    val timeStr = remember(now) { SimpleDateFormat("HH:mm", locale).format(Date(now)) }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(focusKey) { runCatching { firstFocus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = 40.dp, vertical = 28.dp)
    ) {
        // Horna lista: datum vlavo, cas vpravo
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                dateStr.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() },
                color = fgDim, fontSize = 18.sp
            )
            Text(timeStr, color = fg, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }

        // Stred: nazov + dlazdice (3 + 2)
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { c ->
                    android.widget.ImageView(c).apply { setImageResource(R.mipmap.ic_launcher) }
                },
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp))
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Headent Client",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 26.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(28.dp))
            fun fr(key: String): Modifier = if (focusKey == key) Modifier.focusRequester(firstFocus) else Modifier
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HomeTile(stringResource(R.string.tab_channels), Icons.Default.LiveTv, fg, fr("channels"), onChannels)
                HomeTile(stringResource(R.string.tab_radio), Icons.Default.Radio, fg, fr("radio"), onRadio)
                HomeTile(stringResource(R.string.home_tv_program), Icons.Default.DateRange, fg, fr("epg"), onTvProgram)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HomeTile(stringResource(R.string.tab_dvr), Icons.AutoMirrored.Filled.Dvr, fg, fr("archive"), onArchive)
                HomeTile(stringResource(R.string.tab_settings), Icons.Default.Settings, fg, fr("settings"), onSettings)
            }
        }
    }
}

@Composable
private fun HomeTile(
    label: String,
    icon: ImageVector,
    fg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val dark = !isLightTheme()
    val cardBg = if (dark) Color(0x14FFFFFF) else Color(0x0D000000)
    Column(
        modifier
            .width(150.dp)
            .height(108.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(cardBg, RoundedCornerShape(14.dp))
            .dpadFocusable(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(38.dp))
        Spacer(Modifier.height(10.dp))
        Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
