package sk.tvhclient.android

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.TvhServer

/**
 * The radio mini player (M340) — state shared between RadioPlayerService and the UI.
 * The service fills the state (active/playing/name), MiniRadioBar draws it and controls it
 * via intents. The radio thus plays in the background of the app (a foreground service with
 * a notification) while a person browses channels/EPG/the archive.
 */
object RadioCenter {
    val active = mutableStateOf(false)
    val playing = mutableStateOf(false)
    val stationName = mutableStateOf("")
    val stationUuid = mutableStateOf("")
    val piconUrl = mutableStateOf<String?>(null)
    // the EPG of the station currently playing (if the station has one) — shown in the bar and in the notification
    val nowTitle = mutableStateOf("")
    val nowStart = mutableStateOf(0L)
    val nowStop = mutableStateOf(0L)

    /** The list of stations for switching from the bar (M344-fix3) — a snapshot taken at start. */
    data class RadioStation(
        val uuid: String, val name: String, val picon: String?,
        val nowTitle: String, val nowStart: Long, val nowStop: Long
    )
    var stations: List<RadioStation> = emptyList()

    /** Switches to the next/previous station from the snapshot (wrap). */
    fun switchStation(context: Context, delta: Int) {
        if (stations.isEmpty()) return
        val server = Tvh.store.active() ?: return
        val idx = stations.indexOfFirst { it.uuid == stationUuid.value }
        val next = stations[((if (idx < 0) 0 else idx) + delta + stations.size) % stations.size]
        play(context, server, next.uuid, next.name,
            picon = next.picon, epgTitle = next.nowTitle,
            epgStart = next.nowStart, epgStop = next.nowStop)
    }

    /** Starts a station in the service (phone, modern mode). */
    fun play(
        context: Context, server: TvhServer, uuid: String, name: String,
        picon: String? = null, epgTitle: String = "", epgStart: Long = 0L, epgStop: Long = 0L
    ) {
        // M383: the profile is uniform for the whole server
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

    /** Tap on the bar: closes the mini player and opens the full radio player. */
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
