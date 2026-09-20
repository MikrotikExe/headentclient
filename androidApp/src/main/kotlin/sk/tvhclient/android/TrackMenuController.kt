package sk.tvhclient.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import sk.tvhclient.shared.Tvh

/**
 * M671: track menu actions (audio / subtitles / profile) extracted from PlayerActivity — the state is
 * held by [TrackState] (M637); these are the operations on the player and the server: HTSP subtitle
 * selection (our own decoder), opening the profile menu with the list from the server (M383), a profile
 * change = new server default + stream restart, and D-pad selection of an item in the open menu.
 */
internal class TrackMenuController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val tracks: TrackState,
    private val live: LiveSession,
    private val stream: StreamState,
    private val player: () -> MediaPlayer?,
    private val hooks: Hooks
) {
    interface Hooks {
        fun subtitleReset()
        /** Restart of the current channel after a profile change (liveIndex = -1; switchToIndex(i, poke = false)). */
        fun restartCurrentChannel()
    }

    /** HTSP subtitle selection: remember the wanted language and try to set it in libVLC straight away; if the
     *  track is not there yet (the language has not spoken), it is applied on ESAdded. id < 0 = Off. */
    fun pickHtspSpu(esIndex: Int) {
        tracks.selectedSubEs.value = esIndex
        // We decode and render DVB subtitles ourselves; they do not go into libVLC. Selection = which ES to decode.
        hooks.subtitleReset()
        stream.htspFeeder?.selectSubtitle(esIndex)
    }

    fun openProfileMenu() {
        val srv = live.server ?: return
        // immediate fallback, the server may replace the list with its own right afterwards
        if (tracks.profileItems.value.isEmpty()) {
            tracks.profileItems.value =
                ChannelPrefs.profileOptions.map { it.first }.filter { it.isNotBlank() }
        }
        scope.launch {
            val list = withContext(Dispatchers.IO) { Tvh.streamProfiles(srv) }
            if (list.isNotEmpty()) tracks.profileItems.value = list
        }
        tracks.openProfileMenu()
    }

    /** M383: a new profile = a new SERVER default (applies to all further channels,
     *  survives a restart; the same value is in Settings -> server -> Edit).
     *  The stream is restarted with the new URL. */
    fun applyProfileChange(profile: String) {
        val srv = live.server ?: return
        if (profile.isBlank() || profile == srv.profile) return
        // M392: reconcile the wanted state with the actual state before the restart (also covers
        // the case where the user switched subtitles via the touch menu in the meantime)
        if (!stream.htspStream) tracks.captureHttpSpuFromPlayer()
        val updated = srv.copy(profile = profile)
        Tvh.store.upsert(updated)
        live.server = updated
        tracks.currentProfile.value = profile
        hooks.restartCurrentChannel()
    }

    /** D-pad OK in the open track menu: apply the highlighted item and close the menu. */
    fun selectAtNav() {
        val mp = player() ?: return
        val htspStream = stream.htspStream
        val ids = tracks.menuIds(htspStream)
        val id = ids.getOrNull(tracks.navIndex.value) ?: return
        when {
            tracks.menuKind == "profile" -> {
                tracks.profileItems.value.getOrNull(id)?.let { applyProfileChange(it) }
            }
            tracks.menuKind == "audio" -> {
                mp.audioTrack = id
                // M378: remember the manual selection for the channel from the TV menu too (D-pad);
                // previously it was stored only from the touch menu, so on TV the
                // choice was "forgotten" after switching channel
                val sid = Tvh.store.active()?.id
                val uuid = live.uuidState.value
                if (sid != null && uuid != null) {
                    val name = mp.audioTrackItems().firstOrNull { it.id == id }?.name
                    if (!name.isNullOrBlank()) ChannelPrefs.setLastAudio(ctx, sid, uuid, name)
                }
            }
            htspStream -> pickHtspSpu(id)
            else -> {
                mp.spuTrack = id
                tracks.httpSpuUserPick(id)   // M392-fix: overwrites the persistent wanted state
            }
        }
        tracks.closeMenu()
    }
}
