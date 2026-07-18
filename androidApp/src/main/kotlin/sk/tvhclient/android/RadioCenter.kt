package sk.tvhclient.android

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.TvhServer

/**
 * Mini prehravac radia (M340) — zdielany stav medzi RadioPlayerService a UI.
 * Service stav plni (active/playing/nazov), MiniRadioBar ho kresli a ovlada
 * cez intenty. Radio tak hra na pozadi appky (foreground service s
 * notifikaciou), kym si clovek prezera kanaly/EPG/archiv.
 */
object RadioCenter {
    val active = mutableStateOf(false)
    val playing = mutableStateOf(false)
    val stationName = mutableStateOf("")
    val stationUuid = mutableStateOf("")
    val piconUrl = mutableStateOf<String?>(null)
    // EPG prave hranej stanice (ak ju stanica ma) — zobrazi sa v liste aj notifikacii
    val nowTitle = mutableStateOf("")
    val nowStart = mutableStateOf(0L)
    val nowStop = mutableStateOf(0L)

    /** Zoznam stanic pre prepinanie z panelu (M344-fix3) — snapshot pri spusteni. */
    data class RadioStation(
        val uuid: String, val name: String, val picon: String?,
        val nowTitle: String, val nowStart: Long, val nowStop: Long
    )
    var stations: List<RadioStation> = emptyList()

    /** Prepne na dalsiu/predoslu stanicu zo snapshotu (wrap). */
    fun switchStation(context: Context, delta: Int) {
        if (stations.isEmpty()) return
        val server = Tvh.store.active() ?: return
        val idx = stations.indexOfFirst { it.uuid == stationUuid.value }
        val next = stations[((if (idx < 0) 0 else idx) + delta + stations.size) % stations.size]
        play(context, server, next.uuid, next.name,
            picon = next.picon, epgTitle = next.nowTitle,
            epgStart = next.nowStart, epgStop = next.nowStop)
    }

    /** Spusti stanicu v service (telefon, moderny rezim). */
    fun play(
        context: Context, server: TvhServer, uuid: String, name: String,
        picon: String? = null, epgTitle: String = "", epgStart: Long = 0L, epgStop: Long = 0L
    ) {
        // M383: profil je jednotny pre cely server
        val url = Tvh.liveUrl(server, uuid, name, server.profile.ifBlank { "pass" })
        piconUrl.value = picon
        nowTitle.value = epgTitle
        nowStart.value = epgStart
        nowStop.value = epgStop
        val i = Intent(context, RadioPlayerService::class.java).apply {
            action = RadioPlayerService.ACTION_PLAY
            putExtra(RadioPlayerService.EXTRA_URL, url)
            putExtra(RadioPlayerService.EXTRA_NAME, name)
            putExtra(RadioPlayerService.EXTRA_UUID, uuid)
            putExtra(RadioPlayerService.EXTRA_EPG, epgTitle)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, i)
    }

    fun toggle(context: Context) {
        context.startService(Intent(context, RadioPlayerService::class.java).apply {
            action = RadioPlayerService.ACTION_TOGGLE
        })
    }

    fun stop(context: Context) {
        context.startService(Intent(context, RadioPlayerService::class.java).apply {
            action = RadioPlayerService.ACTION_STOP
        })
    }

    /** Klik na listu: zavrie mini prehravac a otvori plny prehravac radia. */
    fun openFull(context: Context) {
        val uuid = stationUuid.value
        val name = stationName.value
        if (uuid.isBlank()) return
        stop(context)
        context.startActivity(Intent(context, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_UUID, uuid)
            putExtra(PlayerActivity.EXTRA_TITLE, name)
            putExtra(PlayerActivity.EXTRA_KIND, "radio")
        })
    }
}
