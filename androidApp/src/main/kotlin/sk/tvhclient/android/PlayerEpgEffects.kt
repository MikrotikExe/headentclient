package sk.tvhclient.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/*
 * M662: EPG efekty (prefetch + periodický refresh pri otvorenom zozname/páse) vyčlenené
 * z PlayerUi (PlayerActivity.kt) — limit 64 kB na metódu. Telá 1:1; stav drží volajúci.
 */

/** Predbežné načítanie EPG po starte a periodický refresh, kým je otvorený zoznam / pás / ovládanie. */
@Composable
internal fun PlayerEpgEffects(
    showChannelList: Boolean,
    controlsVisible: Boolean,
    modernOvVisible: Boolean,
    onPrefetchEpg: () -> Unit,
    onRefreshEpgInitial: () -> Unit,
    onRefreshEpg: () -> Unit
) {
    // M266: predbezne nacitanie EPG (now/next) na pozadi kratko po starte prehravaca,
    // aby prvy otvoreny zoznam kanalov mal data uz z cache (epgUpcomingState) bez sietoveho
    // cakania. Bezi na IO (refreshOverlayEpg), stream nabehne prvy a UI sa neblokuje.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1200)
        onPrefetchEpg()   // M274: refresh len ak je cache prazdna/zastarana
    }

    // Kym je zoznam kanalov otvoreny, obnovuj EPG (now/next) aby relacie
    // postupne prechadzali na dalsie
    // M522: obnovuj EPG a nahravaci priznak (cervena bodka), kym je otvoreny plny
    // zoznam kanalov ALEBO vodorovny pas. Doteraz to platilo len pre plny zoznam,
    // takze v pase sa bodky objavovali neskoro alebo vobec — a stav tlacidla
    // nahravania v „Viac" bol podla toho tiez nespolahlivy.
    // Jeden efekt namiesto dvoch: PlayerUi je tesne pod 64 KB limitom metody.
    // M525: aj MODERNY PAS (modernOvVisible) — ten sa neriadi `controlsVisible`,
    // takze podmienka z M522 sa nan vobec nevztahovala a cervene bodky v nom
    // nabiehali az potom, co ich stiahol velky zoznam kanalov.
    LaunchedEffect(showChannelList || controlsVisible || modernOvVisible) {
        if (showChannelList || controlsVisible || modernOvVisible) {
            onRefreshEpgInitial()   // M270: prve nacitanie so spinnerom (len ak je cache prazdna/zastarana)
            while (true) {
                kotlinx.coroutines.delay(60_000)
                onRefreshEpg()      // periodicky refresh bez spinnera
            }
        }
    }
}
