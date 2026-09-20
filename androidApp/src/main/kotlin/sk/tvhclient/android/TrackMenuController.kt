package sk.tvhclient.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import sk.tvhclient.shared.Tvh

/**
 * M671: akcie track menu (zvuk / titulky / profil) vyclenené z PlayerActivity — stav drží
 * [TrackState] (M637), toto sú operácie nad prehrávačom a serverom: výber HTSP titulkov
 * (vlastný dekodér), otvorenie menu profilu so zoznamom zo servera (M383), zmena profilu
 * = nová predvoľba servera + reštart streamu, a D-pad výber položky v otvorenom menu.
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
        /** Reštart aktuálneho kanála po zmene profilu (liveIndex = -1; switchToIndex(i, poke = false)). */
        fun restartCurrentChannel()
    }

    /** HTSP vyber titulku: zapamataj zelany jazyk a skus ho hned nastavit v libVLC; ak stopa
     *  este nie je (jazyk nehovoril), aplikuje sa pri ESAdded. id < 0 = Vypnute. */
    fun pickHtspSpu(esIndex: Int) {
        tracks.selectedSubEs.value = esIndex
        // DVB titulky dekódujeme a renderujeme sami; do libVLC nejdu. Vyber = ktory ES dekódovat.
        hooks.subtitleReset()
        stream.htspFeeder?.selectSubtitle(esIndex)
    }

    fun openProfileMenu() {
        val srv = live.server ?: return
        // okamzity fallback, server moze zoznam vzapati nahradit vlastnym
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

    /** M383: novy profil = nova predvolba SERVERA (plati pre vsetky dalsie kanaly,
     *  drzi po restarte; ta ista hodnota je v Nastavenia -> server -> Upravit).
     *  Stream sa restartuje s novou URL. */
    fun applyProfileChange(profile: String) {
        val srv = live.server ?: return
        if (profile.isBlank() || profile == srv.profile) return
        // M392: zosulad zelanie so skutocnym stavom pred restartom (pokryva aj
        // pripad, ked pouzivatel medzitym prepol titulky dotykovym menu)
        if (!stream.htspStream) tracks.captureHttpSpuFromPlayer()
        val updated = srv.copy(profile = profile)
        Tvh.store.upsert(updated)
        live.server = updated
        tracks.currentProfile.value = profile
        hooks.restartCurrentChannel()
    }

    /** D-pad OK v otvorenom track menu: aplikuj zvyraznenu polozku a zavri menu. */
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
                // M378: zapamataj rucny vyber pre kanal aj z TV menu (D-pad);
                // predtym sa ukladal len z dotykoveho menu, takze na TV sa
                // volba po prepnuti kanala "zabudla"
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
                tracks.httpSpuUserPick(id)   // M392-fix: prepise trvale zelanie
            }
        }
        tracks.closeMenu()
    }
}
