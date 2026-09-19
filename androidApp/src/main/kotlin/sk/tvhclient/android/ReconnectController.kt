package sk.tvhclient.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf

/**
 * M636: časovanie a stav opätovného pripojenia (vyclenené z PlayerActivity). Čo presne sa
 * pri pokuse spraví (HTSP resubscribe / feeder / priame HTTP / DVR reopen) ostáva v
 * aktivite — sem chodí len ako lambda, lebo to siaha na mediaPlayer, feedery a stream URL.
 *
 * - Živý stream: [scheduleReconnect] s narastajúcim oneskorením (1,5 s × pokus, max 8 s),
 *   najviac [MAX_LIVE] pokusov, potom Toast; watchdog po 12 s zopakuje, ak sa nehrá.
 * - In-progress nahrávka: [reopenDvrLive] po 2,5 s, najviac [MAX_DVR] pokusov (backoff
 *   proti slučke, keď nič nové nepribúda); [resetDvrReopen] pri Playing / manuálnom play.
 * [reconnecting] číta PlayerUi (spinner „Opätovné pripájanie").
 */
class ReconnectController(
    private val ctx: Context,
    private val playerReady: () -> Boolean,
    private val isPlaying: () -> Boolean
) {
    val reconnecting = mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())
    private var liveAttempts = 0
    private var dvrAttempts = 0

    val attempts: Int get() = liveAttempts

    /** Zruší naplánované znovupripojenie a skryje indikátor. */
    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        liveAttempts = 0
        reconnecting.value = false
    }

    /** Zruší čakajúce úlohy bez zmeny indikátora (seek = krátky reštart streamu, nie výpadok). */
    fun clearPending() {
        handler.removeCallbacksAndMessages(null)
        dvrAttempts = 0
    }

    fun resetDvrReopen() { dvrAttempts = 0 }

    /** Skryje indikátor bez rušenia počítadiel (chyba, ktorú už ďalej neriešime). */
    fun hide() { reconnecting.value = false }

    /** Naplánuje znovupripojenie živého streamu; [retry] dostane číslo pokusu (1..). */
    fun scheduleReconnect(retry: (attempt: Int) -> Unit) {
        if (!playerReady()) return
        if (liveAttempts >= MAX_LIVE) {
            reconnecting.value = false
            Toast.makeText(ctx, ctx.getString(R.string.reconnect_failed), Toast.LENGTH_LONG).show()
            return
        }
        liveAttempts++
        reconnecting.value = true
        val delay = (1500L * liveAttempts).coerceAtMost(8000L)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!playerReady()) return@postDelayed
            runCatching { retry(liveAttempts) }
            // watchdog: ak sa do 12 s neobjavi prehravanie (spinner ostal), skus znova;
            // po vycerpani pokusov scheduleReconnect ohlasi chybu -> ziadne trvale zaseknutie.
            // 12 s nechava HTSP subscription cas nabehnut a nabufrovat (kratsie sa dvojilo)
            handler.postDelayed({
                if (playerReady() && reconnecting.value && !isPlaying()) scheduleReconnect(retry)
            }, 12000)
        }, delay)
    }

    /** Znovu otvorí in-progress nahrávku po 2,5 s; false = pokusy vyčerpané. */
    fun reopenDvrLive(reopen: () -> Unit): Boolean {
        if (!playerReady()) return false
        if (dvrAttempts >= MAX_DVR) {
            reconnecting.value = false
            return false
        }
        dvrAttempts++
        reconnecting.value = true
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!playerReady()) return@postDelayed
            runCatching { reopen() }
        }, 2500)
        return true
    }

    fun destroy() = handler.removeCallbacksAndMessages(null)

    private companion object {
        const val MAX_LIVE = 8
        const val MAX_DVR = 5
    }
}
