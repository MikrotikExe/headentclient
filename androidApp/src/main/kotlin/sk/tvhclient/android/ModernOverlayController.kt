package sk.tvhclient.android

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf

/**
 * M327 / M328 / M642: moderný TV overlay (karty kanálov + ovládacia lišta) a jeho menu
 * „Viac" — stav a klávesy, vyclenené z PlayerActivity. Vykreslenie robí ModernTvOverlay
 * a ModernMoreMenu v PlayerUi; sem chodia len signály (poke/exec) a indexy.
 *
 * [live] dáva počet kanálov a aktuálny index; [seekable]/[timeshiftEngaged] ovplyvňujú
 * položky lišty; akcie idú cez [Actions] (prehrávanie, prepínanie, zoznam, menu…).
 */
internal class ModernOverlayController(
    private val live: LiveSession,
    private val seekable: () -> Boolean,
    private val timeshiftEngaged: () -> Boolean,
    private val profileSwitchAvailable: () -> Boolean,
    private val dvrRecordVisible: () -> Boolean,
    private val teletextVisible: () -> Boolean,
    private val actions: Actions
) {
    interface Actions {
        fun hideZapBar()
        fun togglePlayPause()
        fun timeshiftSkip(seconds: Int)
        fun switchLive(dir: Int)
        fun openChannelList()
        fun openSleepMenu()
        fun toggleInfo()
        fun openProfileMenu()
        fun toggleRecordCurrent()
        fun openTeletext()
        /** Podržanie OK v overlayi = menu fokusovaného kanála (M338). Aktivita nastaví okLongFired. */
        fun openChannelContextMenu(cardIndex: Int)
    }

    val visible = mutableStateOf(false)
    val row = mutableStateOf(0)      // 0 = karty, 1 = lista
    val card = mutableStateOf(0)
    val strip = mutableStateOf(0)
    val poke = mutableStateOf(0)
    val exec = mutableStateOf(0)     // signal pre composable
    val execId = mutableStateOf("")
    private var okLong = false
    // OK z prehravania: overlay otvarame az na OK-UP, aby pri podrzani nepreblikol (M328)
    private var okPending = false

    // "Viac" menu listy (M327): menej pouzivane polozky — rezerva pre dlhsie preklady
    val moreVisible = mutableStateOf(false)
    val moreIdx = mutableStateOf(0)

    val isOpen: Boolean get() = visible.value
    val isMoreOpen: Boolean get() = moreVisible.value

    /** Polozky ovladacej listy overlayu (transport v strede; pretacanie len pri timeshiftu). */
    fun stripIds(): List<String> = buildList {
        add("epg"); add("audio")
        // prepinanie kanalov priamo z listy (M323) — len pri live s viac kanalmi
        val zap = !seekable() && live.uuids.size > 1
        if (zap) add("chprev")
        if (timeshiftEngaged()) add("tsrew")
        add("play")
        if (timeshiftEngaged()) add("tsff")
        if (zap) add("chnext")
        add("subs"); add("more")
    }

    // M383: "profile" pribudne len ked je prepinac dostupny (HTTP live)
    fun moreIds(): List<String> = buildList {
        add("list"); add("sleep"); add("info")
        if (profileSwitchAvailable()) add("profile")
        if (dvrRecordVisible()) add("rec")   // M490
        if (teletextVisible()) add("teletext")   // M553
    }

    fun moreActivate() {
        val id = moreIds().getOrNull(moreIdx.value) ?: return
        moreVisible.value = false
        when (id) {
            "list" -> { close(); actions.openChannelList() }
            "sleep" -> { close(); actions.openSleepMenu() }
            "info" -> { close(); actions.toggleInfo() }
            "profile" -> { close(); actions.openProfileMenu() }
            "rec" -> { close(); actions.toggleRecordCurrent() }   // M490
            "teletext" -> actions.openTeletext()   // M553
        }
    }

    fun morePick(i: Int) { moreIdx.value = i; moreActivate() }
    fun moreDismiss() { moreVisible.value = false }

    fun open() {
        actions.hideZapBar()  // M446
        card.value = live.indexState.value.coerceAtLeast(0)
        strip.value = stripIds().indexOf("play").coerceAtLeast(0)
        row.value = 0
        poke.value++
        visible.value = true
    }

    /** Otvorí overlay s kartou na aktuálnom kanáli (po prepnutí CH+/- so zapnutým prekryvom). */
    fun openAtCurrent() { card.value = live.indexState.value.coerceAtLeast(0); open() }

    fun close() { visible.value = false }

    private fun syncCard() { card.value = live.indexState.value.coerceAtLeast(0); poke.value++ }

    /** OK v overlayi: karta -> prepni kanal; lista -> vykonaj akciu. */
    private fun activate() {
        if (row.value == 0) {
            execId.value = "card"; exec.value++
            close()
        } else when (stripIds().getOrNull(strip.value)) {
            "play" -> { actions.togglePlayPause(); poke.value++ }
            "tsrew" -> { actions.timeshiftSkip(-30); poke.value++ }
            "tsff" -> { actions.timeshiftSkip(+30); poke.value++ }
            "more" -> { moreIdx.value = 0; moreVisible.value = true }
            "chprev" -> { actions.switchLive(-1); syncCard() }
            "chnext" -> { actions.switchLive(+1); syncCard() }
            null -> {}
            else -> {
                execId.value = stripIds()[strip.value]; exec.value++
                close()
            }
        }
    }

    /** Klávesy menu „Viac" (M327). Vždy spotrebuje. */
    fun handleMoreKey(kc: Int, down: Boolean, event: KeyEvent): Boolean {
        if (down) when (kc) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { moreIdx.value = (moreIdx.value + 1) % moreIds().size; return true }
            KeyEvent.KEYCODE_DPAD_UP -> { moreIdx.value = (moreIdx.value - 1 + moreIds().size) % moreIds().size; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) moreActivate(); return true
            }
            KeyEvent.KEYCODE_BACK -> { moreVisible.value = false; return true }
        }
        return true
    }

    /**
     * Klávesy otvoreného overlayu. Vráti false len pre hlasitosť (nech ide systému),
     * všetko ostatné spotrebuje.
     */
    fun handleKey(kc: Int, down: Boolean, event: KeyEvent): Boolean {
        val ids = stripIds()
        if (down) {
            when (kc) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (row.value == 0) {
                        val n = live.uuids.size
                        if (n > 0) card.value = (card.value - 1 + n) % n
                    } else strip.value = (strip.value - 1 + ids.size) % ids.size
                    poke.value++; return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (row.value == 0) {
                        val n = live.uuids.size
                        if (n > 0) card.value = (card.value + 1) % n
                    } else strip.value = (strip.value + 1) % ids.size
                    poke.value++; return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (row.value == 0) {
                        row.value = 1
                        strip.value = ids.indexOf("play").coerceAtLeast(0)
                    }
                    poke.value++; return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (row.value == 1) row.value = 0
                    poke.value++; return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.repeatCount == 1) {
                        // Podrzanie OK v overlay = moznosti FOKUSOVANEHO kanala
                        // (Info / Prehrat od zaciatku / Zamok) — M338. Velky zoznam
                        // ostava cez Viac -> Kanaly a dlhe OK z cisteho prehravania.
                        okLong = false
                        actions.openChannelContextMenu(card.value)
                    }
                    return true
                }
                // M407-fix2: CH+/- a Page+/- prepinaju kanal aj v modernom overlay
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (!seekable() && live.uuids.size > 1) { actions.switchLive(+1); syncCard() }
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (!seekable() && live.uuids.size > 1) { actions.switchLive(-1); syncCard() }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> { close(); return true }
            }
        } else {
            when (kc) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!okLong) activate()
                    okLong = false
                    return true
                }
                KeyEvent.KEYCODE_BACK -> return true
            }
        }
        when (kc) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return false
        }
        return true
    }

    /**
     * OK z čistého prehrávania na TV (M328): krátke OK -> overlay až na UP; podržanie
     * (repeatCount 1) -> rovno veľký zoznam bez prebliku overlayu. Vždy spotrebuje.
     * [onLongOpenList] volá aktivita (nastaví okLongFired a otvorí zoznam).
     */
    fun handlePlaybackOk(down: Boolean, event: KeyEvent, onLongOpenList: () -> Unit): Boolean {
        if (down && event.repeatCount == 0) { okPending = true; return true }
        if (down && event.repeatCount == 1 && okPending) {
            okPending = false
            onLongOpenList()
            return true
        }
        if (down) return true
        if (okPending) { okPending = false; open() }
        return true
    }
}
