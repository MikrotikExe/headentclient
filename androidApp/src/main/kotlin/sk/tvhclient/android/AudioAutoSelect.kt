package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.videolan.libvlc.MediaPlayer

/*
 * M662: auto-výber audio stopy vyčlenený z PlayerUi (PlayerActivity.kt) — limit 64 kB na metódu.
 * Telo 1:1; volajúci odovzdáva prehrávač, kontext a preferencie ako hodnoty.
 */

/** Auto-výber audio stopy po načítaní média (zapamätaná pre kanál / jazykové priority / mimo AD). */
@Composable
internal fun AudioAutoSelectEffect(
    player: MediaPlayer,
    ctx: Context,
    liveChannelUuid: String?,
    serverId: String?,
    preferredAudio: List<String>
) {
    // Auto-vyber audio stopy po nacitani: 1) zapamatana pre kanal, 2) jazykove
    // priority (AD/narrated stopy preskakujeme), 3) fallback mimo AD stopy.
    // M378: kluc = liveChannelUuid, nech vyber prebehne aj pri prepnuti kanala
    // v ramci prehravaca (predtym LaunchedEffect(Unit) bezal len raz).
    LaunchedEffect(liveChannelUuid) {
        repeat(30) {
            kotlinx.coroutines.delay(500)
            val real = player.audioTracks?.filter { it.id >= 0 } ?: emptyList()
            if (real.size >= 2) {
                val remembered = if (liveChannelUuid != null && serverId != null)
                    ChannelPrefs.getLastAudio(ctx, serverId, liveChannelUuid) else ""
                if (remembered.isNotBlank()) {
                    val m = real.firstOrNull { (it.name ?: "") == remembered }
                        ?: real.firstOrNull { (it.name ?: "").contains(remembered) }
                    if (m != null) {
                        if (player.audioTrack != m.id) player.audioTrack = m.id
                        return@LaunchedEffect
                    }
                }
                for (code in preferredAudio) {
                    // M378: v ramci jazyka preferuj beznu stopu pred AD/narrated
                    // (obe casto nesu rovnaky jazykovy kod, napr. "English" a
                    // "English AD" — predtym vyhrala ta, co bola v zozname prva)
                    val cands = real.filter { AudioPref.matches(it.name ?: "", code) }
                    val m = cands.firstOrNull { !AudioPref.isDescriptive(it.name ?: "") }
                        ?: cands.firstOrNull()
                    if (m != null) {
                        if (player.audioTrack != m.id) player.audioTrack = m.id
                        return@LaunchedEffect
                    }
                }
                // M378: ziadna jazykova zhoda — ak by default (aktualna stopa)
                // bol AD/narrated, prepni na prvu beznu stopu. Riesi kanaly,
                // kde je AD stopa prva v poradi a vyhrala by ako default.
                val curName = real.firstOrNull { it.id == player.audioTrack }?.name ?: ""
                if (AudioPref.isDescriptive(curName)) {
                    val plain = real.firstOrNull { !AudioPref.isDescriptive(it.name ?: "") }
                    if (plain != null && player.audioTrack != plain.id) player.audioTrack = plain.id
                }
                return@LaunchedEffect
            }
        }
    }
}
