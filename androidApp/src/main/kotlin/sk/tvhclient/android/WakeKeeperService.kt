package sk.tvhclient.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * M716: keeps the app awake for "Start app on wake from sleep".
 *
 * Since Android 14 the system freezes a cached app in the background (seen on a Homatics box:
 * `dumpsys activity processes` -> isFrozen=true). A frozen process does not get the SCREEN_ON
 * broadcast that TvhApplication listens to, so the box woke up and the app never learnt about it.
 * A foreground service makes the process "perceptible": it is not frozen, the receiver runs and
 * AutostartLaunch brings the app to the front. It runs ONLY while the wake option is on and does
 * nothing else (no network, no timers); on a TV the notification is not shown.
 *
 * Started from the foreground (an activity resumed — see TvhApplication) or after boot (a
 * BOOT_COMPLETED receiver may start a foreground service), never from the background.
 */
class WakeKeeperService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AutostartPref.isWakeEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification())
            }
        }
        if (ok.isFailure) {
            CrashLogger.report(this, "WakeKeeper", ok.exceptionOrNull() ?: Exception("startForeground failed"))
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")   // the pre-26 builder and priority
    private fun notification(): Notification {
        val title = getString(R.string.autostart_wake)
        val builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_MIN))
            builder = Notification.Builder(this, CHANNEL_ID)
        } else {
            builder = Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle(title)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "wake_keeper"
        private const val NOTIF_ID = 7160

        /** Starts the service when the wake option is on (call from the foreground or after boot). */
        fun ensure(ctx: Context) {
            if (!AutostartPref.isWakeEnabled(ctx)) return
            val i = Intent(ctx, WakeKeeperService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }.onFailure { CrashLogger.report(ctx, "WakeKeeper", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, WakeKeeperService::class.java)) }
        }
    }
}
