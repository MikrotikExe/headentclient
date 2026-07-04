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

    /** Spusti stanicu v service (telefon, moderny rezim). */
    fun play(context: Context, server: TvhServer, uuid: String, name: String) {
        val profile = ChannelPrefs.getProfile(context, server.id, uuid)
        val url = Tvh.liveUrl(
            server, uuid, name,
            profile.ifBlank { server.profile.ifBlank { "pass" } }
        )
        val i = Intent(context, RadioPlayerService::class.java).apply {
            action = RadioPlayerService.ACTION_PLAY
            putExtra(RadioPlayerService.EXTRA_URL, url)
            putExtra(RadioPlayerService.EXTRA_NAME, name)
            putExtra(RadioPlayerService.EXTRA_UUID, uuid)
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
