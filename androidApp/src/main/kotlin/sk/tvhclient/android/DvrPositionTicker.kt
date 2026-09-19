package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import org.videolan.libvlc.MediaPlayer

/**
 * M662: sekundovy ticker DVR pozicie vyclaneny z PlayerUi (JVM 64 KB limit metody).
 * Telo LaunchedEffect je 1:1 z PlayerUi; stav PlayerUi sa cita cez gettery a zapisuje
 * cez settery, aby slucka videla svoje vlastne zapisy v ramci jedneho tiku.
 */
@Composable
internal fun DvrPositionTicker(
    player: MediaPlayer,
    ctx: Context,
    lengthMsLive: State<Long>,
    offsetMsLive: State<Long>,
    seekSeedLive: State<Long>,
    onSeekSeedHandledLive: State<() -> Unit>,
    recordingLive: Boolean,
    liveMarginMs: Long,
    dvrUuid: String?,
    serverId: String?,
    posTimeMs: () -> Long,
    onPosTimeMsSet: (Long) -> Unit,
    posFraction: () -> Float,
    onPosFractionSet: (Float) -> Unit,
    initialSeekDone: () -> Boolean,
    onInitialSeekDoneSet: (Boolean) -> Unit,
    rebuiltBySeek: () -> Boolean,
    onRebuiltBySeekSet: (Boolean) -> Unit,
    pendingResumeMs: () -> Long,
    onPendingResumeMsSet: (Long) -> Unit,
    lastPlayTickMs: () -> Long,
    onLastPlayTickMsSet: (Long) -> Unit,
    askResume: () -> Boolean,
    dragging: () -> Boolean,
    onSeekToMs: (Long) -> Unit,
    onPlayheadMs: (Long) -> Unit,
) {
    LaunchedEffect(Unit) {
        var sinceSave = 0
        while (true) {
            val nowMs = System.currentTimeMillis()
            val curLen = lengthMsLive.value
            val curOff = offsetMsLive.value
            // dosiahnutelny rozsah (bez rezervy) - playhead ani znovu-otvorenie nejde do nej
            val curBar = if (recordingLive) (curLen - liveMarginMs).coerceAtLeast(1L) else curLen
            // Po pretoceni (seekDvrTo): prevezmi cielovy cas ako playhead. Pri feeder/pipe
            // je player.position po restarte neplatna, tak ju nasledny resync nesmie citat -
            // seed da hodinam spravny bod a tikaju dalej z neho (initialSeekDone=true zaroven
            // umlci jednorazovy skok na zaciatok relacie).
            val seed = seekSeedLive.value          // M495-fix: cerstva hodnota
            // M495-fix2: pockaj na znamu dlzku. Ked ju prehravac este nepozna
            // (curLen == 0 — pomale nacitanie media alebo zlyhany pokus),
            // coerceIn(0, curBar) by ciel orezal na NULU a seed by sa navyse
            // spotreboval — hodiny by tikali od zaciatku. Seed nechame cakat
            // na dalsi tik; dovtedy ho nikto iny neprepise.
            if (seed >= 0L && curLen > 0) {
                onPosTimeMsSet(seed.coerceIn(0L, curBar))
                val denom = (curOff + curLen).coerceAtLeast(1L)
                onPosFractionSet(((curOff + posTimeMs()).toFloat() / denom).coerceIn(0f, 1f))
                onInitialSeekDoneSet(true)
                onRebuiltBySeekSet(true)      // M495
                onSeekSeedHandledLive.value()
            }
            // obnovenie po potvrdeni (ma prednost pred skokom na zaciatok relacie).
            // POZOR: priame player.position pri pipe/feeder DVR nefunguje (a
            // isSeekable byva false, takze sa skok nikdy nevykonal a nahravka
            // isla od zaciatku) — pouzi tu istu cestu ako pouzivatelske
            // pretocenie (onSeekToMs -> seekDvrAbsolute: restart s :start-time,
            // playhead hodiny prevezmu seed).
            if (pendingResumeMs() > 0 && curLen > 0) {
                val tgt = pendingResumeMs().coerceIn(0L, curBar)
                onSeekToMs(tgt)
                onPosTimeMsSet(tgt)
                onPosFractionSet(((curOff + tgt).toFloat() / (curOff + curLen).coerceAtLeast(1L)).coerceIn(0f, 1f))
                onPendingResumeMsSet(0)
                onInitialSeekDoneSet(true)
            }
            // Jednorazovy skok na zaciatok relacie v subore (prebiehajuca nahravka s
            // predprogramovym obsahom). Seekujeme POZICIOU (zlomok), nie setTime - na
            // rastucom TS je to spolahlivejsie. Zlomok = offset / (offset + uplynuty cas
            // relacie) = poloha zaciatku relacie v aktualnom buffri.
            if (!initialSeekDone() && recordingLive && curOff > 0 &&
                !askResume() && pendingResumeMs() == 0L && curLen > 0 && player.isSeekable) {
                val f = (curOff.toFloat() / (curOff + curLen)).coerceIn(0f, 1f)
                player.position = f
                onPosFractionSet(f)
                onPosTimeMsSet(0L)
                onInitialSeekDoneSet(true)
            }
            if (!dragging()) {
                val p = player.position
                if (p in 0f..1f) {
                    // Skok pozicie = doslo k seeku (slider/D-pad/dvojklik) -> zosulad
                    // prehravacie hodiny so skutocnou poziciou. Mapujeme subor->cas relacie:
                    // (p * (offset + dlzka)) - offset. Pri normalnom prehravani sa p meni
                    // plynulo (<<5%), takze sa to nespusti a hodiny tikaju z wall-clocku.
                    // p > 0.02: ignoruj falosne nulove citanie pozicie (caste na rastucom TS
                    // aj tesne po znovu-otvoreni), nech hodiny neskocia na 0.
                    // M495: len kym je medium povodne. Po pretoceni (prebudovane
                    // s :start-time) by tento prepocet hodiny zresetoval takmer na
                    // nulu a tikali by od zleho bodu — presne to sposobovalo, ze
                    // dalsie pretocenie islo z davno prehratej pozicie.
                    if (!rebuiltBySeek() && initialSeekDone() && curLen > 0 && p > 0.02f &&
                        player.isSeekable && kotlin.math.abs(p - posFraction()) > 0.05f) {
                        onPosTimeMsSet((p * (curOff + curLen) - curOff).toLong()
                            .coerceIn(0L, curBar))
                    }
                    if (!rebuiltBySeek()) onPosFractionSet(p)
                }
                // Prehravacie hodiny: kym sa prehrava, pridavaj realny uplynuly cas.
                if (lastPlayTickMs() > 0L && player.isPlaying) {
                    val d = (nowMs - lastPlayTickMs()).coerceIn(0L, 3000L)
                    onPosTimeMsSet((posTimeMs() + d).coerceIn(0L, curBar))
                }
                // M495: po prebudovani media je zlomok z player.position neplatny,
                // takze poloha na lište musi vychadzat z hodin (inak by ukazovatel
                // skocil na zaciatok a nesedel by s casom).
                if (rebuiltBySeek()) {
                    val denom = (curOff + curLen).coerceAtLeast(1L)
                    onPosFractionSet(((curOff + posTimeMs()).toFloat() / denom).coerceIn(0f, 1f))
                }
                // Zrkadli playhead do Activity — potrebuju ho znovu-otvorenie
                // in-progress streamu AJ pretacanie (seekRelative/dvojklik z neho
                // beru vychodziu poziciu).
                // M492: bolo `if (recordingLive)`, takze pri DOKONCENEJ nahravke
                // z archivu playhead v Activity zamrzol na hodnote posledneho seeku
                // a kazde dalsie pretocenie islo od nej, nie od miesta, kde sa hra.
                onPlayheadMs(posTimeMs())
            }
            onLastPlayTickMsSet(nowMs)
            // Koniec dostupnych dat in-progress nahravky (EOF) riesi reopenDvrLive (znovu
            // otvori stream a pokracuje do novsich dat). Seek clamp (45 s) drzi playhead
            // bezpecne za zivou hranou, takze pri normalnom prehravani sa na EOF nenarazi.
            // priebezne ukladaj poziciu (kazdych ~5s) - z prehravacich hodin (spolahlive)
            sinceSave++
            if (sinceSave >= 5 && !askResume()) {
                sinceSave = 0
                if (posTimeMs() > 1000L && curLen > 0 && dvrUuid != null && serverId != null) {
                    WatchProgress.save(ctx, serverId, dvrUuid, posTimeMs().coerceAtMost(curLen), curLen)
                }
            }
            kotlinx.coroutines.delay(1000)
        }
    }
}
