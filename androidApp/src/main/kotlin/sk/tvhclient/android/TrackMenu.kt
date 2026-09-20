package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.videolan.libvlc.MediaPlayer

/*
 * M661: track menu (audio / subtitles / profile) extracted from PlayerUi (PlayerActivity.kt) — 64 kB
 * per-method limit. The caller holds the [menu] state and closes it via [setMenu]; the actual rendering
 * is done by TrackMenu (PlayerUiComponents.kt).
 */

/** Track menu (audio / subtitles / profile). Call only when menu != null. */
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
    // Track menu (audio / subtitles)
    if (menu != null) {
        // trackListVersion: read deliberately, so the list re-renders when
        // libVLC adds a track (DVB subtitles / audio languages only appear after start).
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
            allowOff = (menu == "spu"),  // subtitles can be turned off (-1)
            navIndex = trackNavIndex,
            onPick = { id ->
                if (menu == "profile") {
                    profileItems.getOrNull(id)?.let { onPickProfile(it) }
                } else if (menu == "audio") {
                    player.audioTrack = id
                    // remember the selection for the channel (live)
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
