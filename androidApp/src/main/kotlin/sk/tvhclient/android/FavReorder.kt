package sk.tvhclient.android

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.MutableState

/**
 * M541 / M638: režim usporiadania obľúbených D-padom v zozname kanálov prehrávača
 * (vyclenené z PlayerActivity). Zapína sa z menu kanála v skupine Obľúbené. OK uchopí /
 * položí kanál pod kurzorom, HORE/DOLE uchopený kanál posúvajú (poradie sa uloží hneď a
 * zoznam sa prečísluje), BACK režim ukončí. Nápoveda je v pilulke skupiny
 * ([groupLabel]) — žiadny nový UI kód v PlayerUi.
 *
 * M541-fix: uchopený riadok je odlíšený dátami — pred názvom „↕" a namiesto relácie
 * nápoveda; zobrazovaný stav ([liveChannels]) je kópia, LivePlaylist.channels ostáva čistý.
 */
class FavReorder(
    private val ctx: Context,
    private val serverId: () -> String?,
    private val liveUuids: () -> List<String>,
    private val liveChannels: MutableState<List<LivePlaylist.LiveChannel>>,
    private val navIndex: MutableState<Int>,
    private val groupLabel: MutableState<String>,
    private val groupLabelFor: (String) -> String,
    private val reapplyFavGroup: () -> Unit,
    private val okLongFired: () -> Boolean
) {
    var active = false
        private set
    private var grabbed = false

    fun enter() {
        if (LivePlaylist.activeGroupKey != LivePlaylist.GROUP_FAV) return
        active = true
        grabbed = false
        updateLabel()
    }

    fun exit() {
        if (!active) return
        active = false
        grabbed = false
        groupLabel.value = groupLabelFor(LivePlaylist.activeGroupKey)
        liveChannels.value = LivePlaylist.channels   // M541-fix: zrus dekoraciu
    }

    private fun updateLabel() {
        groupLabel.value = if (grabbed) {
            val name = LivePlaylist.channels.getOrNull(navIndex.value)?.name ?: ""
            ctx.getString(R.string.fav_reorder_grabbed, name)
        } else ctx.getString(R.string.fav_reorder_hint)
        decorateGrabbedRow()
    }

    private fun decorateGrabbedRow() {
        val base = LivePlaylist.channels
        if (!active || !grabbed) { liveChannels.value = base; return }
        val g = navIndex.value
        val hint = ctx.getString(R.string.fav_reorder_row)
        liveChannels.value = base.mapIndexed { i, ch ->
            if (i == g) ch.copy(name = "↕ " + ch.name, nowTitle = hint) else ch
        }
    }

    /** Posun uchopeneho kanala o [dir] (+1 dole / -1 hore) v poradi oblubenych. */
    private fun moveGrabbed(dir: Int) {
        val sid = serverId() ?: return
        val from = navIndex.value
        val to = from + dir
        val order = Favorites.list(ctx, sid)
        if (from !in order.indices || to !in order.indices) return
        // zobrazovany zoznam Oblubenych = favOrder v tom istom poradi (favChannels),
        // ale kanaly, ktore server uz nema, v nom chybaju -> mapuj cez uuid
        val uuid = liveChannels.value.getOrNull(from)?.uuid ?: return
        val target = liveChannels.value.getOrNull(to)?.uuid ?: return
        val fi = order.indexOf(uuid); val ti = order.indexOf(target)
        if (fi < 0 || ti < 0) return
        Favorites.move(ctx, sid, fi, ti)
        reapplyFavGroup()   // refreshFavOrder + applyGroup(FAV): precisluje 1..n a prepocita liveIndex
        navIndex.value = liveUuids().indexOf(uuid).coerceAtLeast(0)
        updateLabel()
    }

    /** Klavesy v rezime usporiadania. Vracia true, ak bola udalost spracovana. */
    fun handleKey(kc: Int, down: Boolean, isOk: Boolean): Boolean {
        if (!active) return false
        val n = liveUuids().size
        if (isOk) {
            if (okLongFired()) return true
            if (!down && n > 0) {
                grabbed = !grabbed
                updateLabel()
            }
            return true
        }
        if (!down) return true
        when (kc) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (grabbed) moveGrabbed(-1)
                else if (n > 0) navIndex.value = (navIndex.value - 1 + n) % n
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (grabbed) moveGrabbed(+1)
                else if (n > 0) navIndex.value = (navIndex.value + 1) % n
                return true
            }
            KeyEvent.KEYCODE_BACK -> { exit(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
        }
        return false
    }
}
