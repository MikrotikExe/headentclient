package sk.tvhclient.android

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import org.videolan.libvlc.MediaPlayer

/**
 * M650: spracovanie udalostí libVLC MediaPlayer-a (vyclenené z PlayerActivity.createPlayer).
 *
 * Poradie a význam vetiev je zhodný s pôvodným listenerom: chyba/koniec živého streamu ->
 * reconnect, in-progress nahrávka -> reopen, dokončená nahrávka -> ústup po pretočení (M594)
 * alebo koniec; Playing nuluje pokusy, spúšťa AFR, hlídača a obnovu stôp; ES* udalosti
 * presadzujú voľbu titulkov. Drží aj [hasVideo] (rozhlas = logo namiesto videa) a jeho
 * oneskorenú kontrolu po Playing (videoTracksCount je spoľahlivý až po ~1,5 s).
 *
 * Všetko, čo siaha na stream / feedery / UI aktivity, chodí cez [Actions] — beží na
 * libVLC vlákne rovnako ako predtým, coroutine ošetrenie (lifecycleScope) rieši aktivita.
 */
internal class VlcEvents(
    private val player: () -> MediaPlayer?,
    private val seekable: () -> Boolean,
    private val dvrRecording: () -> Boolean,
    private val htspStream: () -> Boolean,
    private val dvrProgStopSec: () -> Long,
    private val actions: Actions
) : MediaPlayer.EventListener {

    interface Actions {
        fun scheduleReconnect()
        fun cancelReconnect()
        fun resetDvrReopen()
        fun hideReconnecting()
        fun reopenDvrLive()
        /** M594: false = nič na zotavenie (chyba nie je následkom pretočenia). */
        fun recoverAfterSeek(): Boolean
        fun setPlaying(playing: Boolean)
        fun refreshPipIfActive()
        fun onPlayingForSeek()      // DvrSeek.onPlaying
        fun onPlayingForStall()     // StallWatchdog.onPlaying
        fun applyPendingSpuRestore()
        fun applyDesiredSpu()
        fun maybeApplyAfr()
        fun keepScreenOn(on: Boolean)
        fun hideSeekSpinner()
        fun scheduleTrackRefresh()
        fun maybeReparseForTracks()
        fun bumpTrackList()
        fun saveDvrProgress()
        fun onReachedEnd()
        fun showPlaybackError()
    }

    /** true = stream má video; false = rozhlas (PlayerUi ukáže logo). */
    val hasVideo = mutableStateOf(true)
    private val videoCheckHandler = Handler(Looper.getMainLooper())

    override fun onEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.EncounteredError -> {
                // zivé vysielanie: skus znovu pripojit (vypadok siete)
                if (!seekable()) {
                    actions.scheduleReconnect()
                } else if (dvrRecording()) {
                    // DVR seek/feeder zlyhal -> znovu otvor stream na aktualnom playheade
                    // (reopenDvrLive ma backoff a po vycerpani pokusov vycisti spinner),
                    // nech to neostane zaseknute na "Opatovne pripajanie"
                    actions.reopenDvrLive()
                } else if (actions.recoverAfterSeek()) {
                    // M594: dokoncena nahravka — chyba hned po pretoceni znamena
                    // ciel za koncom suboru; ustup a skus znova namiesto zastavenia
                } else {
                    actions.hideReconnecting()
                    actions.showPlaybackError()
                }
            }
            MediaPlayer.Event.Playing -> {
                actions.setPlaying(true); actions.refreshPipIfActive()
                actions.onPlayingForSeek()   // M594
                actions.onPlayingForStall()   // M539
                if (!htspStream()) actions.applyPendingSpuRestore()  // M392-fix2
                actions.maybeApplyAfr()  // AFR (M346): prepni Hz displeja podla fps streamu
                actions.keepScreenOn(true)  // pocas prehravania nedovol setric/ambient na boxoch
                actions.cancelReconnect()  // uspesne pripojenie -> vynuluj pokusy
                actions.resetDvrReopen()  // uspesne pokracovanie -> vynuluj pokusy o znovu-otvorenie
                actions.hideSeekSpinner()  // resync po skoku dobehol
                // po nabehnuti zisti ci stream ma video; ak nie -> rozhlas (logo)
                videoCheckHandler.removeCallbacksAndMessages(null)
                videoCheckHandler.postDelayed({
                    val n = runCatching { player()?.videoTracksCount }.getOrNull()
                    if (n != null && n >= 0) hasVideo.value = n > 0
                }, 1500)
                // doplnenie audio jazykov / DVB titulkov, ktore libVLC doparsuje az po starte
                actions.scheduleTrackRefresh()
                actions.maybeReparseForTracks()
            }
            MediaPlayer.Event.Buffering -> {
                if (event.buffering >= 100f) actions.hideSeekSpinner()
            }
            MediaPlayer.Event.Paused -> { actions.setPlaying(false); actions.keepScreenOn(false); actions.refreshPipIfActive() }
            MediaPlayer.Event.Stopped -> { actions.setPlaying(false); actions.keepScreenOn(false); actions.refreshPipIfActive() }
            MediaPlayer.Event.Vout -> { if (event.voutCount > 0) hasVideo.value = true }
            MediaPlayer.Event.ESSelected -> {
                // M392-fix2: libVLC si prave sam zvolil stopu (napr. default titulky
                // v matroske) — presad zelanie pouzivatela (vypnute / konkretny jazyk)
                if (!htspStream()) actions.applyPendingSpuRestore()
            }
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted -> {
                // libVLC priebezne registruje stopy (DVB titulky / audio jazyky sa
                // objavia az par sekund po starte) -> obnov otvorene track menu
                actions.bumpTrackList()
                // ak pouzivatel zvolil titulkovy jazyk, ktory este nebol k dispozicii,
                // nastav ho hned ako jeho stopa pribudne (mimo libVLC callbacku)
                if (htspStream()) actions.applyDesiredSpu()
                // M392: HTTP live po zmene profilu — obnov povodnu volbu titulkov
                if (!htspStream()) actions.applyPendingSpuRestore()
            }
            MediaPlayer.Event.EndReached -> {
                actions.setPlaying(false)
                if (!seekable()) {
                    // zivý stream "skoncil" = vypadok -> znovu pripojit
                    actions.scheduleReconnect()
                } else if (dvrRecording() &&
                    (dvrProgStopSec() <= 0 || System.currentTimeMillis() / 1000 < dvrProgStopSec())) {
                    // prebiehajuca nahravka dobehla na koniec zapisanych dat -> znovu otvor
                    // stream (novy GET prinesie novsie data), nie koniec prehravania
                    actions.saveDvrProgress()
                    actions.reopenDvrLive()
                } else if (actions.recoverAfterSeek()) {
                    // M594: skok trafil koniec suboru — ustup a hraj dalej
                } else {
                    actions.onReachedEnd()
                    actions.keepScreenOn(false)
                    actions.saveDvrProgress()
                }
            }
        }
    }

    fun destroy() = videoCheckHandler.removeCallbacksAndMessages(null)
}
