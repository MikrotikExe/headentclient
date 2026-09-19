package sk.tvhclient.android

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * M452 / M626: zamky pre plynuly prijem streamu — Wi-Fi lock + partial wake lock.
 *
 * Bez WifiLocku Android na Wi-Fi zariadeniach (Xiaomi Mi Box a spol.) uspava
 * Wi-Fi cip a pakety dorucuje v davkach — HTSP data potom prichadzaju raz za
 * sekundu naraz a obraz sa trha (libVLC hlasi "picture is too late to be
 * displayed"). Merania: recvWait ~950 ms, tsWrite 0 ms — cakalo sa vylucne
 * na siet. Zariadenia na ethernete (Strong, Raspberry Pi) to nepocitili.
 * WakeLock drzi procesor, aby sa prijmacia slucka neuspala.
 *
 * Jedna instancia na vlastnika (PlayerActivity, RadioPlayerService); [tag] sa
 * zobrazi v battery stats. Volania su idempotentne a nikdy nehodia vynimku.
 */
class StreamLocks(context: Context, private val tag: String) {
    private val appCtx = context.applicationContext
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null

    fun acquire() {
        runCatching {
            if (wifiLock == null) {
                val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag)
                    ?.apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        }
        runCatching {
            if (cpuLock == null) {
                val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                cpuLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
                    ?.apply { setReferenceCounted(false) }
            }
            if (cpuLock?.isHeld == false) cpuLock?.acquire()
        }
    }

    fun release() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (cpuLock?.isHeld == true) cpuLock?.release() }
    }
}
