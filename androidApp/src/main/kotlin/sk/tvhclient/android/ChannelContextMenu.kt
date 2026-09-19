package sk.tvhclient.android

import android.content.Context
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.DvrEntry
import sk.tvhclient.shared.model.EpgEvent

/**
 * M641: kontextové menu kanála v prehrávači (long-press OK / dlhý klik), vyclenené
 * z PlayerActivity. Položky podľa kanála: info, prehrať od začiatku (ak sa nahráva),
 * nahrať (M607), obľúbený (M368), usporiadať (M541), zámok, skryť/odkryť (M541).
 * Skrytie/odkrytie priamo prestavuje [live] (zoznam, index) a LivePlaylist.
 *
 * Akcie, ktoré siahajú do prehrávača alebo DVR, dostáva ako lambdy ([actions]).
 */
internal class ChannelContextMenu(
    private val ctx: Context,
    private val live: LiveSession,
    private val groups: LiveGroups,
    private val epgUpcoming: () -> Map<String, List<EpgEvent>>,
    private val recInProgress: () -> Map<String, DvrEntry>,
    private val canRecord: () -> Boolean,
    private val navIndex: MutableState<Int>,
    private val okLongFired: () -> Boolean,
    private val actions: Actions
) {
    interface Actions {
        fun showInfo(idx: Int)
        fun playFromStart(rec: DvrEntry, nowStart: Long, nowStop: Long)
        fun switchTo(idx: Int)
        fun toggleLock(idx: Int)
        fun record(ch: LivePlaylist.LiveChannel, ev: EpgEvent)
        fun enterReorder()
    }

    val idxState = mutableStateOf(-1)  // index kanala, -1 = zatvorene
    val selState = mutableStateOf(0)   // zvyraznena polozka

    val isOpen: Boolean get() = idxState.value >= 0

    private fun serverId(): String? = (live.server ?: Tvh.store.active())?.id

    /** Polozky menu pre dany kanal (v poradi). "lock" len ak je zamok zapnuty,
     *  "fromstart" len ak sa relacia prave nahrava (da sa prehrat od zaciatku). */
    fun keys(idx: Int): List<String> {
        val ch = live.channelsState.value.getOrNull(idx) ?: return emptyList()
        val keys = mutableListOf("info")
        if (recInProgress().let { it[ch.uuid] ?: it[ch.name] } != null) keys.add("fromstart")
        // M607: nahrat prave beziacu relaciu vybrateho kanala priamo zo zoznamu
        // (kanal nemusi hrat) — len ked mame jej EPG s eventId a este sa nenahrava
        else if (canRecord() && eventOf(ch) != null) keys.add("rec")
        // M368: oblubene a skrytie kanala aj na TV (predtym len na telefone)
        keys.add("fav")
        // M541: usporiadanie oblubenych (len v skupine Oblubene, len D-pad)
        if (LivePlaylist.activeGroupKey == LivePlaylist.GROUP_FAV && live.channelsState.value.size > 1) keys.add("reorder")
        if (ParentalLock.isEnabled(ctx)) keys.add("lock")
        // M541-fix: skryty kanal (kdekolvek, nie len v skupine Skryte) -> „Odkryt kanal"
        val hiddenCh = LivePlaylist.activeGroupKey == LivePlaylist.GROUP_HIDDEN ||
            HiddenChannels.isHidden(ctx, serverId(), ch.uuid)
        keys.add(if (hiddenCh) "unhide" else "hide")
        return keys
    }

    /** M607: prave beziaca relacia kanala (z now/next cache), ak ma eventId. */
    fun eventOf(ch: LivePlaylist.LiveChannel): EpgEvent? {
        val nowSec = System.currentTimeMillis() / 1000
        return epgUpcoming()[ch.uuid]?.firstOrNull { it.start <= nowSec && nowSec < it.stop && it.eventId != null }
    }

    fun open(idx: Int) {
        if (idx < 0 || idx >= live.channelsState.value.size) return
        if (keys(idx).isEmpty()) return
        selState.value = 0
        idxState.value = idx
    }

    fun close() { idxState.value = -1 }

    /**
     * Pridanie/odobratie kanala z oblubenych (kontextova ponuka, M579: klaves ZALOZKA
     * na ovladaci). [announce] ukaze potvrdenie — pri klavese bez ponuky by inak
     * pouzivatel nevidel, co sa stalo.
     */
    fun toggleFavoriteAt(idx: Int, announce: Boolean) {
        val ch = live.channelsState.value.getOrNull(idx) ?: return
        val sid = serverId() ?: return
        Favorites.toggle(ctx, sid, ch.uuid)
        val nowFav = Favorites.isFav(ctx, sid, ch.uuid)
        groups.refreshFavOrder()   // M541
        // M541: v skupine Oblubene sa zoznam zmenil (odobrany kanal / precislovanie)
        if (LivePlaylist.activeGroupKey == LivePlaylist.GROUP_FAV) {
            if (LivePlaylist.favChannels().isEmpty()) groups.apply(LivePlaylist.GROUP_ALL) else groups.apply(LivePlaylist.GROUP_FAV)
            navIndex.value = navIndex.value.coerceIn(0, (live.uuids.size - 1).coerceAtLeast(0))
        }
        if (announce) {
            Toast.makeText(ctx, ctx.getString(if (nowFav) R.string.fav_added else R.string.fav_removed, ch.name), Toast.LENGTH_SHORT).show()
        }
    }

    fun activate(key: String) {
        val idx = idxState.value
        val ch = live.channelsState.value.getOrNull(idx)
        close()
        if (ch == null) return
        when (key) {
            "info" -> actions.showInfo(idx)                       // detail relacie priamo v prehravaci
            "fromstart" -> {
                val rec = recInProgress().let { it[ch.uuid] ?: it[ch.name] }
                if (rec != null) actions.playFromStart(rec, ch.nowStart, ch.nowStop)
                else if (idx != live.index) actions.switchTo(idx)   // ak kanal este nehra a nie je archiv -> aspon prepni nazivo
            }
            "lock" -> actions.toggleLock(idx)                     // uz riesi PIN + grace okno
            "fav" -> toggleFavoriteAt(idx, announce = false)
            "rec" -> eventOf(ch)?.let { actions.record(ch, it) }   // M607
            "reorder" -> actions.enterReorder()   // M541
            "unhide" -> unhide(ch, idx)
            "hide" -> hide(ch, idx)
        }
    }

    /** M541: odkryt kanal — spat medzi vsetky kanaly (podla cisla), von zo Skrytych. */
    private fun unhide(ch: LivePlaylist.LiveChannel, idx: Int) {
        val sid = serverId() ?: return
        HiddenChannels.setHidden(ctx, sid, ch.uuid, false)
        LivePlaylist.hiddenChannels = LivePlaylist.hiddenChannels.filter { it.uuid != ch.uuid }
        if (LivePlaylist.allChannels.none { it.uuid == ch.uuid }) {
            LivePlaylist.allChannels = (LivePlaylist.allChannels + ch)
                .sortedWith(compareBy({ if (it.number > 0) it.number else Int.MAX_VALUE }, { it.name.lowercase() }))
        }
        if (LivePlaylist.activeGroupKey == LivePlaylist.GROUP_HIDDEN) {
            if (LivePlaylist.hiddenChannels.isEmpty()) groups.apply(LivePlaylist.GROUP_ALL)
            else {
                groups.apply(LivePlaylist.GROUP_HIDDEN)
                navIndex.value = idx.coerceIn(0, (live.uuids.size - 1).coerceAtLeast(0))
            }
        } else if (LivePlaylist.activeGroupKey == LivePlaylist.GROUP_ALL) {
            groups.apply(LivePlaylist.GROUP_ALL)   // odkryty kanal sa objavi na svojom mieste
            navIndex.value = live.uuids.indexOf(ch.uuid).coerceAtLeast(0)
        }
    }

    private fun hide(ch: LivePlaylist.LiveChannel, idx: Int) {
        val sid = serverId() ?: return
        HiddenChannels.setHidden(ctx, sid, ch.uuid, true)
        // M541: presun do zoznamu skrytych (pseudo-skupina), von zo vsetkych.
        // Povodne cislo vezmi z allChannels (v Oblubenych je `ch.number` poradie 1..n).
        val orig = LivePlaylist.allChannels.firstOrNull { it.uuid == ch.uuid } ?: ch
        LivePlaylist.allChannels = LivePlaylist.allChannels.filter { it.uuid != ch.uuid }
        if (LivePlaylist.hiddenChannels.none { it.uuid == ch.uuid }) {
            LivePlaylist.hiddenChannels = LivePlaylist.hiddenChannels + orig
        }
        // Skryty kanal hned odstranit zo zap zoznamu (ak prave nehra);
        // posun liveIndex, aby CH+/- dalej sedeli.
        if (idx != live.index) {
            val cur = live.channelsState.value.toMutableList()
            if (idx in cur.indices) {
                cur.removeAt(idx)
                live.channelsState.value = cur
                LivePlaylist.channels = cur
                live.uuids = cur.map { it.uuid }
                live.names = cur.map { it.name }
                if (idx < live.index) live.index--
                live.indexState.value = live.index
                navIndex.value = navIndex.value.coerceIn(0, (cur.size - 1).coerceAtLeast(0))
            }
        }
    }

    /** Klavesy pri otvorenom menu: hore/dole + OK (na uvolnenie) + BACK/VLAVO. Vzdy spotrebuje. */
    fun handleKey(kc: Int, down: Boolean): Boolean {
        val ks = keys(idxState.value)
        val cnt = ks.size
        val isOk = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isOk) {
            if (okLongFired()) return true                 // prehltni up z otvaracieho long-pressu
            if (!down && cnt > 0) activate(ks.getOrElse(selState.value) { ks.first() })
            return true                                    // OK aktivuje az na uvolnenie
        }
        if (down && cnt > 0) {
            when (kc) {
                KeyEvent.KEYCODE_DPAD_UP -> { selState.value = (selState.value - 1 + cnt) % cnt; return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { selState.value = (selState.value + 1) % cnt; return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> { close(); return true }
            }
        }
        return true
    }
}
