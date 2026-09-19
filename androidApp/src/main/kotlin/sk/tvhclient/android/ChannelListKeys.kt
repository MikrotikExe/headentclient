package sk.tvhclient.android

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.MutableState

/**
 * M645: klávesy otvoreného zoznamu kanálov v prehrávači (vyclenené z dispatchKeyEvent).
 * Poradie stráží je správanie: hľadanie (výsledky) → pilulka skupiny (M369) → režim
 * usporiadania (M541) → OK (ochranné okno po otvorení, podržanie = menu kanála,
 * režim „jedno OK" M596) → navigácia (hore z vrchu = pilulka; vľavo/vpravo po 7; BACK).
 * Vráti false len pre hlasitosť (nech ide systému), inak vždy spotrebuje.
 */
internal class ChannelListKeys(
    private val ctx: Context,
    private val live: LiveSession,
    private val groups: LiveGroups,
    private val search: ChannelSearch,
    private val reorder: FavReorder,
    private val navIndex: MutableState<Int>,
    private val groupPicker: MutableState<Boolean>,
    private val actions: Actions
) {
    interface Actions {
        var okLongFired: Boolean
        fun openContextMenu(idx: Int)
        fun closeList()
        /** M600-fix: „jedno OK" — zoznam sa zavrie a prepnutie príde s oneskorením (video späť na celú obrazovku). */
        fun switchDelayed(idx: Int)
        fun selectOrArchive(idx: Int)
        /** OK na už hrajúcom kanáli: zavri zoznam a ukáž ovládanie / moderný overlay. */
        fun reselectCurrent()
    }

    /** Kedy sa zoznam otvoril — OK eventy tesne po otvorení (zvyšky otváracieho dlhého
     *  stlačenia, ghost DOWN/UP páry z IR/CEC ovládačov) sa ignorujú (M330-fix2). */
    var openedAt = 0L

    fun handleKey(kc: Int, down: Boolean, event: KeyEvent): Boolean {
        val n = live.uuids.size
        // M370: aktivne hladanie, fokus na vysledkoch (pole riesi skory bypass v aktivite)
        if (search.isActive) {
            if (search.handleResultsKey(kc, down)) return true
            return !DialogKeys.isVolume(kc)
        }
        val isOk = DialogKeys.isOk(kc)
        // M369: fokus na pilulke skupiny (nad zoznamom) — VLAVO/VPRAVO meni skupinu,
        // DOLE/OK naspat do zoznamu, HORE hladanie, BACK zavrie pilulku.
        if (groupPicker.value) {
            if (down) when (kc) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { groups.cycle(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { groups.cycle(+1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> { groupPicker.value = false; return true }
                KeyEvent.KEYCODE_DPAD_UP -> { search.open(); return true }
                KeyEvent.KEYCODE_BACK -> { groupPicker.value = false; return true }
            }
            return !DialogKeys.isVolume(kc)
        }
        // M541: rezim usporiadania oblubenych ma prednost pred beznou navigaciou
        if (reorder.active) {
            if (DialogKeys.isVolume(kc)) return false
            reorder.handleKey(kc, down, isOk)
            return true
        }
        if (isOk) {
            // pocas drzania otvaracieho OK (a jeho opakovani) nereaguj
            if (actions.okLongFired) return true
            // M596: rezim „jedno OK" — kratsie ochranne okno a prepnutie hned
            val oneOk = OneOkPref.get(ctx)
            // debounce po otvoreni: niektore ovladace (IR/CEC) poslu po dlhom
            // stlaceni este ghost DOWN/UP par — ten by okamzite potvrdil kanal
            // a zoznam zavrel; vsetko OK do 400 ms od otvorenia sa zahodi
            val guardMs = if (oneOk) 150L else 400L
            if (SystemClock.uptimeMillis() - openedAt < guardMs) return true
            if (down) {
                // podrzanie OK v zozname = kontextove menu kanala (Info / od zaciatku / zamok)
                if (event.isLongPress && n > 0) {
                    actions.okLongFired = true               // OK-up sa potom prehltne (nevyberie kanal)
                    actions.openContextMenu(navIndex.value)
                    return true
                }
                return true                          // na DOWN nevyberaj (cakame na uvolnenie)
            } else if (n > 0) {
                if (oneOk) {
                    // M596-fix/M596-fix2/M600-fix: jedno OK = kanal rovno na celu obrazovku,
                    // prepina sa az pri UVOLNENI a s oneskorenim po zatvoreni zoznamu
                    val idx = navIndex.value
                    actions.closeList()
                    if (idx != live.index) actions.switchDelayed(idx)
                    return true
                }
                // uvolnenie OK (kratky klik) = vyber/prepnutie kanala
                if (navIndex.value == live.indexState.value) actions.reselectCurrent()
                else actions.selectOrArchive(navIndex.value)
            }
            return true
        }
        if (down && n > 0) when (kc) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                // z vrchu zoznamu HORE -> fokus na pilulku skupiny (ak su nejake skupiny)
                if (navIndex.value == 0 && groups.keys().size > 1) groupPicker.value = true
                else navIndex.value = (navIndex.value - 1 + n) % n
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> { navIndex.value = (navIndex.value + 1) % n; return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { navIndex.value = (navIndex.value - 7).coerceIn(0, n - 1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { navIndex.value = (navIndex.value + 7).coerceIn(0, n - 1); return true }
            KeyEvent.KEYCODE_BACK -> { actions.closeList(); return true }
        }
        return !DialogKeys.isVolume(kc)
    }
}
