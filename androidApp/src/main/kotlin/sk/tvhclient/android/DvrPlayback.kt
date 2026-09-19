package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.videolan.libvlc.MediaPlayer

/**
 * M665: stav prehrávanej DVR nahrávky (vyclenené z PlayerActivity) — identita (uuid, server),
 * dĺžka, hranice relácie pri prebiehajúcej nahrávke, playhead hodiny, seed po pretočení,
 * príznak dosiahnutého konca — a ukladanie rozpozeranosti ([saveProgress], WatchProgress).
 * Držiak dát; aktivita k poliam pristupuje cez delegáty s pôvodnými názvami.
 */
internal class DvrPlayback(private val ctx: Context) {
    var uuid: String? = null
    var serverId: String? = null
    var durationMs: Long = 0
    // Prebiehajuca relacia: dlzku dopocitavame relativne k zaciatku RELACIE (nie suboru),
    // obmedzenu dlzkou relacie. Seekbar tak ukazuje uplynutu cast relacie, nie cely archiv.
    var recording = false
    var progStartSec: Long = 0
    var progStopSec: Long = 0
    var realStartSec: Long = 0
    val durationState = mutableStateOf(0L)
    var reachedEnd = false
    // Playhead v case relacie (ms) zrkadleny z wall-clock prehravacich hodin v PlayerUi -
    // spolahlivy zdroj pre znovu-otvorenie streamu (player.time je pre rastuci TS nestabilny).
    val playheadMsState = mutableStateOf(0L)
    // Po pretoceni DVR: cielovy (program-relativny) cas, ktory maju playhead hodiny prevziat.
    // -1 = ziadny cakajuci seek. Pri feeder/pipe je player.position po restarte neplatna,
    // takze hodiny sa nemozu resync-nut z pozicie - seed im da spravny vychodzi bod.
    val seekSeedState = mutableStateOf(-1L)

    /** Uloží pozíciu (alebo „dopozerané"); [player] = živý prehrávač alebo null (M535 už uvoľnený). */
    fun saveProgress(player: MediaPlayer?) {
        val uuid = uuid ?: return
        val sid = serverId ?: return
        val mp = player ?: return   // M535: player uz moze byt uvolneny
        val dur = if (durationMs > 0) durationMs else mp.length
        if (dur <= 0) return
        if (reachedEnd && !recording) {
            WatchProgress.markCompleted(ctx, sid, uuid, dur)
            return
        }
        // Program-relativny cas z playhead hodin je jediny spolahlivy zdroj:
        // po pretoceni sa stream restartuje (:start-time) a mediaPlayer.position
        // je relativna k NOVEMU streamu — ukladali sa nezmyselne male pozicie,
        // rozpozeranost sa stracala a 95% prah "dopozerane" sa nikdy nedosiahol.
        val playheadMs = playheadMsState.value
        val posMs = if (playheadMs > 0) {
            playheadMs.coerceAtMost(dur)
        } else {
            val pos = mp.position
            // neprepisuj dobru poziciu nulou (napr. ked sa media este nenacitala)
            if (pos > 0.001f && pos <= 1f) (pos * dur).toLong() else return
        }
        if (posMs > 0) WatchProgress.save(ctx, sid, uuid, posMs, dur)
    }
}
