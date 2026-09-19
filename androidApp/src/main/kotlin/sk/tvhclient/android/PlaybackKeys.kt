package sk.tvhclient.android

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.MutableState

/**
 * M651: klávesy pri bežnom prehrávaní (blok 4 dispatchKeyEvent), vyclenené z PlayerActivity.
 *
 * Volá sa až po všetkých prekryvoch (dialógy, zoznam kanálov, menu, moderný overlay…),
 * keď je prehrávač vytvorený. Rieši: zap (CH+/-, Page+/-, šípky hore/dole pri živom
 * vysielaní), číslice = voľba kanála číslom (+ OK potvrdí hneď), navigáciu ovládacej lišty
 * (D-pad + OK, pri archíve aj plynulé pretáčanie kurzorom — M597/M598) a klávesy pri skrytom
 * ovládaní (OK = play/pause, zoznam kanálov alebo moderný overlay; šípky = lišta/pretáčanie).
 *
 * [handleKey] vráti true/false, keď kláves spracoval, alebo null = nech ho dostane systém
 * (super.dispatchKeyEvent: hlasitosť, BACK pre Compose BackHandler…). Logika aj poradie
 * podmienok sú zhodné s pôvodným blokom.
 */
internal class PlaybackKeys(
    private val ctx: Context,
    private val live: LiveSession,
    private val scrub: ScrubController,
    private val numEntry: ChannelNumberEntry,
    private val controlNav: MutableState<Int>,
    private val seekable: () -> Boolean,
    private val controlsShown: () -> Boolean,
    private val modernTvActive: () -> Boolean,
    /** Poradie prvkov ovládacej lišty pre daný stav (playerControlOrder). */
    private val controlOrder: (canZap: Boolean) -> List<String>,
    private val actions: Actions
) {
    interface Actions {
        fun switchLive(delta: Int)
        fun showZapBar()
        fun openModernOverlayAtCurrent()
        fun openModernOverlay()
        fun showControlsFocused()
        fun pokeControls()
        fun activateControl(id: String?)
        fun togglePlayPause()
        /** Otvorí zoznam kanálov a nastaví okLongFired (prehltne OK-up). */
        fun openChannelListLong()
        /** Moderný TV overlay: krátke OK -> overlay na UP, podržanie -> zoznam (M328/M642). */
        fun modernPlaybackOk(down: Boolean, event: KeyEvent): Boolean
        fun beginScrub(dir: Int)
        fun initScrub()
    }

    private fun afterZap() {
        if (!ZapOverlayPref.get(ctx)) actions.showZapBar()
        else if (modernTvActive()) actions.openModernOverlayAtCurrent()
        else actions.showControlsFocused()
    }

    fun handleKey(kc: Int, down: Boolean, event: KeyEvent): Boolean? {
        val seekablePlayback = seekable()
        // M598-fix2: pustenie sipky ukonci plynule pretacanie a naplanuje skok
        if (!down && seekablePlayback &&
            (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) &&
            scrub.holding
        ) { scrub.stopHold(); return true }
        val canZap = !seekablePlayback && live.uuids.size > 1
        // prepinanie kanalov: Channel+/-, Page+/-, aj sipky hore/dole = zap
        // M407-fix: CH+/- a Page+/- uz NEfiltruju repeatCount — vdaka debounce
        // v switchLive() rychle stisky len posuvaju ciel a nacitanie ide az po
        // zastaveni, takze prepinanie ide svizne aj ked je bar zobrazeny a aj
        // pri drzani/rychlom klikani. D-pad hore/dole ostava na prvy stisk
        // (repeatCount==0), lebo tam koliduje s navigaciou v bare.
        val zapDelta = when (kc) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> if (down && canZap) +1 else 0
            KeyEvent.KEYCODE_DPAD_UP -> if (down && canZap && event.repeatCount == 0) +1 else 0
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> if (down && canZap) -1 else 0
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down && canZap && event.repeatCount == 0) -1 else 0
            else -> 0
        }
        if (zapDelta != 0) {
            actions.switchLive(zapDelta)
            afterZap()
            return true
        }
        // cislice 0-9 (aj numericka klavesnica) = volba kanala cislom
        val digit = when (kc) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> kc - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> kc - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0) { if (down && live.uuids.isNotEmpty()) numEntry.digit(digit); return true }
        // rozpisane cislo kanala + OK => potvrd hned (rychlejsie prepnutie,
        // netreba cakat na 1,5 s casovac)
        if (numEntry.isPending && DialogKeys.isOk(kc)) {
            if (down && event.repeatCount == 0) numEntry.commitNow()
            return true
        }
        // ovladanie zobrazene -> vlavo/vpravo naviguju panel, OK aktivuje
        // zvyrazneny prvok (hore/dole prepinaju kanal vyssie)
        if (controlsShown()) {
            val order = controlOrder(canZap)
            val n = order.size
            fun moveNav(delta: Int) {
                controlNav.value = (controlNav.value + delta + n) % n
                if (order.getOrNull(controlNav.value) == "seek") actions.initScrub()
            }
            if (seekablePlayback) {
                val onSeek = order.getOrNull(controlNav.value) == "seek"
                when (kc) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (down) {
                        scrub.cancelAuto()   // M597
                        moveNav(-1)
                        actions.pokeControls(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                        scrub.cancelAuto()   // M597
                        moveNav(+1)
                        actions.pokeControls(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (down) {
                        if (onSeek) {
                            if (event.repeatCount == 0) scrub.tapOrHold(-1)   // M598-fix2/fix4: klik + plynule drzanie
                        } else {
                            scrub.cancelAuto()
                            moveNav(-1)
                        }
                        actions.pokeControls(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                        if (onSeek) {
                            if (event.repeatCount == 0) scrub.tapOrHold(+1)   // M598-fix2/fix4: klik + plynule drzanie
                        } else {
                            scrub.cancelAuto()
                            moveNav(+1)
                        }
                        actions.pokeControls(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (down && event.repeatCount == 0) {
                            if (onSeek) {
                                scrub.commit()   // M597: OK potvrdi hned (rovnaka cesta)
                                actions.pokeControls()
                            } else actions.activateControl(order.getOrNull(controlNav.value))
                        }
                        return true
                    }
                }
            } else {
                // live: vlavo/vpravo naviguju panel (hore/dole prepinaju kanal vyssie)
                when (kc) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (down) {
                        controlNav.value = (controlNav.value - 1 + n) % n
                        actions.pokeControls(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                        controlNav.value = (controlNav.value + 1) % n
                        actions.pokeControls(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (down && event.repeatCount == 0) actions.activateControl(order.getOrNull(controlNav.value))
                        return true
                    }
                }
            }
            // BACK necháme Compose BackHandler (skryje ovladanie); volume/ostatne tiez
            return null
        }
        // ovladanie skryte
        when (kc) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (seekablePlayback) {
                    if (down && event.repeatCount == 0) { actions.togglePlayPause(); actions.showControlsFocused() }
                    return true
                }
                if (modernTvActive()) {
                    // Kratke OK -> overlay az na UP; podrzanie -> rovno velky zoznam (M328, M642).
                    return actions.modernPlaybackOk(down, event)
                }
                if (down && event.repeatCount == 0) {
                    actions.openChannelListLong()  // okLongFired prehltne nasledne OK-up
                    return true
                }
                if (down) return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) {
                if (seekablePlayback) {
                    if (event.repeatCount == 0) {
                        if (!scrub.continues(-1)) actions.beginScrub(-1)   // M598-fix4
                        scrub.startHold(-1)
                    }
                    return true
                }
                if (modernTvActive()) actions.openModernOverlay() else actions.showControlsFocused(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                if (seekablePlayback) {
                    if (event.repeatCount == 0) {
                        if (!scrub.continues(+1)) actions.beginScrub(+1)   // M598-fix4
                        scrub.startHold(+1)
                    }
                    return true
                }
                if (modernTvActive()) actions.openModernOverlay() else actions.showControlsFocused(); return true
            }
            // hore/dole sem prides len ak sa neda zapovat (napr. DVR) -> otvor panel
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                if (down) { actions.showControlsFocused(); return true }
        }
        return null
    }
}
