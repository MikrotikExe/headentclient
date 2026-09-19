package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.EpgEvent

/**
 * M369 / M541 / M640: filter skupín v zozname kanálov prehrávača (a tým aj CH+/-),
 * vyclenený z PlayerActivity. Prestavuje [live] (uuids/names/index + compose stavy)
 * na zvolenú skupinu: Všetky, Obľúbené (uložené poradie, číslované 1..n), tagy servera,
 * Skryté kanály. Voľba sa pamätá (LastTag, M506). Now/next dopĺňa z EPG cache
 * ([epgUpcoming]), aby prepnutie tagu nikdy nestratilo program.
 */
internal class LiveGroups(
    private val ctx: Context,
    private val live: LiveSession,
    private val epgUpcoming: () -> Map<String, List<EpgEvent>>,
    private val navIndex: MutableState<Int>,
    private val groupLabel: MutableState<String>
) {
    private fun serverId(): String? = (live.server ?: Tvh.store.active())?.id

    fun labelFor(key: String): String = when (key) {
        LivePlaylist.GROUP_ALL -> ctx.getString(R.string.all_channels)
        LivePlaylist.GROUP_FAV -> ctx.getString(R.string.favorites)
        LivePlaylist.GROUP_HIDDEN -> ctx.getString(R.string.hidden_channels)   // M541
        else -> LivePlaylist.groups.firstOrNull { it.key == key }?.label
            ?: ctx.getString(R.string.all_channels)
    }

    /** Poradie skupin pre cyklenie: Vsetky, Oblubene (ak su nejake), tagy,
     *  na konci Skryte kanaly (M541, len ak nejake su). */
    fun keys(): List<String> {
        val keys = mutableListOf(LivePlaylist.GROUP_ALL)
        if (LivePlaylist.favChannels().isNotEmpty()) keys.add(LivePlaylist.GROUP_FAV)
        LivePlaylist.groups.forEach { keys.add(it.key) }
        if (LivePlaylist.hiddenChannels.isNotEmpty()) keys.add(LivePlaylist.GROUP_HIDDEN)
        return keys
    }

    /** M541: aktualizuj poradie oblubenych v LivePlaylist z ulozenych preferencii. */
    fun refreshFavOrder() {
        val srvId = serverId() ?: return
        LivePlaylist.favOrder = Favorites.list(ctx, srvId)
    }

    /** Prestavi live zoznam na zvolenu skupinu; CH+/-, karty aj zoznam potom idu v ramci nej. */
    fun apply(key: String) {
        val all = LivePlaylist.allChannels
        if (all.isEmpty() && key != LivePlaylist.GROUP_HIDDEN) return
        val srvId = serverId()
        // M541: Oblubene = ulozene poradie, cislovane 1..n; Skryte = vlastny zoznam
        val filteredRaw: List<LivePlaylist.LiveChannel> = when (key) {
            LivePlaylist.GROUP_ALL -> all
            LivePlaylist.GROUP_FAV -> LivePlaylist.favChannels()
            LivePlaylist.GROUP_HIDDEN -> LivePlaylist.hiddenChannels
            else -> {
                val allow = LivePlaylist.groups.firstOrNull { it.key == key }?.uuids ?: return
                all.filter { it.uuid in allow }
            }
        }
        if (filteredRaw.isEmpty()) return   // prazdna skupina -> necham stav
        // Dopln "teraz" z procesovej EPG cache — nech prepnutie tagu nikdy nestrati program,
        // aj keby allChannels este nebol obohateny.
        val nowSec = System.currentTimeMillis() / 1000
        val epg = epgUpcoming()
        val filtered = filteredRaw.map { ch ->
            if (ch.nowTitle.isNotBlank()) ch
            else {
                val ev = epg[ch.uuid]?.firstOrNull { it.start <= nowSec && nowSec < it.stop }
                if (ev != null) ch.copy(nowTitle = ev.title, nowStart = ev.start, nowStop = ev.stop) else ch
            }
        }
        LivePlaylist.activeGroupKey = key
        // M506: zapamataj volbu skupiny — po restarte appky sa obnovi. „Vsetky" je
        // prazdno; M541: Oblubene sa pamataju tiez (LastTag.FAV), Skryte nikdy.
        LastTag.set(
            ctx, srvId, live.playKind == "radio",
            if (key == LivePlaylist.GROUP_ALL || key == LivePlaylist.GROUP_HIDDEN) null else LastTag.fromGroupKey(key)
        )
        LivePlaylist.channels = filtered
        live.channelsState.value = filtered
        live.uuids = filtered.map { it.uuid }
        live.names = filtered.map { it.name }
        val ni = live.uuids.indexOf(live.uuidState.value)
        live.index = if (ni >= 0) ni else 0     // ak aktualny kanal nie je v skupine, CH+/- zacne od 0
        live.indexState.value = live.index
        navIndex.value = live.index
        groupLabel.value = labelFor(key)
    }

    /** Prepne na susednu skupinu (dir +1 / -1). */
    fun cycle(dir: Int) {
        val ks = keys()
        if (ks.size < 2) return
        val cur = ks.indexOf(LivePlaylist.activeGroupKey).coerceAtLeast(0)
        val next = ((cur + dir) % ks.size + ks.size) % ks.size
        apply(ks[next])
    }
}
