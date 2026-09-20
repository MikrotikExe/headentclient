package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.videolan.libvlc.MediaPlayer

/*
 * M662: automatic audio-track selection split out of PlayerUi (PlayerActivity.kt) — the 64 kB method limit.
 * Body 1:1; the caller passes the player, the context and the preferences as values.
 */

/** Automatic audio-track selection after the media loads (remembered for the channel / language priorities / non-AD). */
@Composable
internal fun AudioAutoSelectEffect(
    player: MediaPlayer,
    ctx: Context,
    liveChannelUuid: String?,
    serverId: String?,
    preferredAudio: List<String>
) {
    // Automatic audio-track selection after loading: 1) remembered for the channel, 2) language
    // priorities (AD/narrated tracks are skipped), 3) fallback to a non-AD track.
    // M378: key = liveChannelUuid, so that the selection also runs when the channel is switched
    // within the player (previously LaunchedEffect(Unit) ran only once).
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
                    // M378: within a language prefer a normal track over AD/narrated
                    // (both often carry the same language code, e.g. "English" and
                    // "English AD" — previously the one first in the list won)
                    val cands = real.filter { AudioPref.matches(it.name ?: "", code) }
                    val m = cands.firstOrNull { !AudioPref.isDescriptive(it.name ?: "") }
                        ?: cands.firstOrNull()
                    if (m != null) {
                        if (player.audioTrack != m.id) player.audioTrack = m.id
                        return@LaunchedEffect
                    }
                }
                // M378: no language match — if the default (the current track)
                // were AD/narrated, switch to the first normal track. Handles channels
                // where the AD track is first in order and would win as the default.
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
