package sk.tvhclient.android

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * M663: reťaz BackHandler-ov PlayerUi (vyclenená kvôli 64 KB limitu metódy). PORADIE JE
 * SPRÁVANIE: skomponovaný ako prvý má najnižšiu prioritu, špecifickejšie handlery nižšie
 * majú prednosť. Podmienky sú 1:1 s pôvodným kódom; zápisy do stavov PlayerUi idú cez settery.
 */
@Composable
internal fun PlayerBackHandlers(
    autoPipEnabled: Boolean,
    pipSupported: Boolean,
    playing: Boolean,
    seekable: Boolean,
    controlsVisible: Boolean,
    menu: String?,
    showChannelList: Boolean,
    showOptions: Boolean,
    showInfo: Boolean,
    returnLiveOnBack: Boolean,
    onEnterPip: () -> Unit,
    onClose: () -> Unit,
    onRequestExit: () -> Unit,
    setShowChannelList: (Boolean) -> Unit,
    setMenu: (String?) -> Unit,
    setControlsVisible: (Boolean) -> Unit
) {
    // telefon: BACK z cisteho prehravania -> PiP (odkryje domovsku obrazovku), nie ukoncenie.
    // skomponovany ako prvy => ma najnizsiu prioritu, specifickejsie handlery nizsie maju prednost.
    // riadi sa nastavenim automatickeho PiP.
    BackHandler(
        enabled = autoPipEnabled && pipSupported && playing && !controlsVisible && menu == null && !showChannelList && !showOptions
    ) { onEnterPip() }
    BackHandler(enabled = showChannelList) { setShowChannelList(false) }
    BackHandler(enabled = menu != null) { setMenu(null) }
    BackHandler(
        enabled = controlsVisible && menu == null && !showChannelList && !showOptions
    ) { setControlsVisible(false) }
    // "Prehrat od zaciatku" zo zivej TV: Spat (ked nie je nic otvorene) vrati na povodny zivy kanal
    BackHandler(
        enabled = returnLiveOnBack && !controlsVisible && menu == null && !showChannelList && !showOptions
    ) { onClose() }
    // M280: BACK pri cistom zivom prehravani (mimo PiP) -> potvrdenie ukoncenia (ako exit v menu),
    // aby nechcene stlacenie Spat hned neukoncilo prehravanie.
    // M280-fix: LEN na TV (zariadenia bez PiP). Na mobile/tablete (pipSupported) sa
    // potvrdenie nezobrazuje vobec — BACK tam riesi PiP / bezne spravanie.
    // M537: „TV" sa NESMIE odvodzovat z !pipSupported — TV boxy s PiP (Homatics,
    // Shield, Raspberry Pi; pozri M429) potvrdenie nedostali a BACK ukoncil
    // prehravanie hned. Rozhoduje rezim UI (leanback): na TV sa potvrdenie
    // zobrazi vzdy, okrem pripadu, ked ma prednost auto-PiP handler vyssie
    // (zapnuty auto-PiP na boxe s PiP -> BACK = miniatura, ako doteraz).
    // (M537-fix: vypocet je v samostatnej composable — PlayerUi je na 64 KB limite metody.)
    BackHandler(
        enabled = exitConfirmOnBack(pipSupported, autoPipEnabled) && !seekable && !controlsVisible && menu == null
                  && !showChannelList && !showOptions && !returnLiveOnBack && !showInfo
    ) { onRequestExit() }
}
