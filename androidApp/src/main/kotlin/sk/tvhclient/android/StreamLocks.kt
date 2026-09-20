package sk.tvhclient.android

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * M452 / M626: locks for smooth stream reception — Wi-Fi lock + partial wake lock.
 *
 * Without a WifiLock, Android on Wi-Fi devices (Xiaomi Mi Box and friends) puts the
 * Wi-Fi chip to sleep and delivers packets in batches — HTSP data then arrives once a
 * second all at once and the picture stutters (libVLC reports "picture is too late to be
 * displayed"). Measurements: recvWait ~950 ms, tsWrite 0 ms — the wait was purely
 * on the network. Devices on ethernet (Strong, Raspberry Pi) did not feel it.
 * The WakeLock holds the CPU so the receive loop does not go to sleep.
 *
 * One instance per owner (PlayerActivity, RadioPlayerService); [tag] is
 * shown in battery stats. The calls are idempotent and never throw.
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
