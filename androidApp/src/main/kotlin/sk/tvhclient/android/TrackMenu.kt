package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.videolan.libvlc.MediaPlayer

/*
 * M661: menu stôp (audio / titulky / profil) vyclenené z PlayerUi (PlayerActivity.kt) — limit 64 kB
 * na metódu. Stav [menu] drží volajúci a zatvára ho cez [setMenu]; vlastné vykreslenie robí
 * TrackMenu (PlayerUiComponents.kt).
 */

/** Menu stôp (audio / titulky / profil). Volať len keď menu != null. */
@Composable
internal fun PlayerTrackMenu(
    menu: String?,
    setMenu: (String?) -> Unit,
    ctx: Context,
    player: MediaPlayer,
    trackListVersion: Int,
    trackNavIndex: Int,
    profileItems: List<String>,
    currentProfile: String,
    onPickProfile: (String) -> Unit,
    htspSpuItems: List<TrackItem>?,
    htspSpuCurrentId: Int,
    onPickHtspSpu: ((Int) -> Unit)?,
    onPickHttpSpu: ((Int) -> Unit)?,
    liveChannelUuid: String?,
    serverId: String?
) {
    // Menu stop (audio / titulky)
    if (menu != null) {
        // trackListVersion: cita sa zamerne, nech sa zoznam prerenderuje, ked
        // libVLC prida stopu (DVB titulky / audio jazyky sa objavia az po starte).
        @Suppress("UNUSED_EXPRESSION") trackListVersion
        val htspSpu = menu == "spu" && onPickHtspSpu != null
        val items = when {
            menu == "profile" -> profileItems.mapIndexed { i, name -> TrackItem(i, name) }
            menu == "audio" -> player.audioTrackItems()
            htspSpu -> htspSpuItems ?: emptyList()
            else -> player.spuTrackItems()
        }
        val currentId = when {
            menu == "profile" -> profileItems.indexOf(currentProfile)
            menu == "audio" -> player.audioTrack
            htspSpu -> htspSpuCurrentId
            else -> player.spuTrack
        }
        TrackMenu(
            header = when (menu) {
                "profile" -> stringResource(R.string.field_profile)
                "audio" -> stringResource(R.string.track_audio)
                else -> stringResource(R.string.track_subtitles)
            },
            items = items,
            currentId = currentId,
            allowOff = (menu == "spu"),  // titulky sa daju vypnut (-1)
            navIndex = trackNavIndex,
            onPick = { id ->
                if (menu == "profile") {
                    profileItems.getOrNull(id)?.let { onPickProfile(it) }
                } else if (menu == "audio") {
                    player.audioTrack = id
                    // zapamataj vyber pre kanal (live)
                    if (liveChannelUuid != null && serverId != null) {
                        val name = items.firstOrNull { it.id == id }?.name
                        if (!name.isNullOrBlank()) {
                            ChannelPrefs.setLastAudio(ctx, serverId, liveChannelUuid, name)
                        }
                    }
                } else if (htspSpu) {
                    onPickHtspSpu!!(id)
                } else {
                    player.spuTrack = id
                    if (menu == "spu") onPickHttpSpu?.invoke(id)   // M392-fix
                }
                setMenu(null)
            },
            onDismiss = { setMenu(null) }
        )
    }
}
