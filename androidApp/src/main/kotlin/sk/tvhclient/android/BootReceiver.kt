package sk.tvhclient.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Setting for automatic start after the device is powered on. */
object AutostartPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "autostart_enabled"
    private const val KEY_WAKE = "autostart_wake"
    fun isEnabled(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
    fun isWakeEnabled(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WAKE, false)
    fun setWakeEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WAKE, on).apply()
    }
}

/**
 * M535: shared start-up of the app from autostart (both boot and wake-up).
 *
 * If an app task already exists (the process survived standby, or an Amlogic box sent
 * QUICKBOOT_POWERON on wake-up, not on a real boot), it is enough to move it
 * to the front. Originally MainActivity was always started with NEW_TASK — and since it is
 * singleTask, the system closed everything above it in the process, including a running player.
 * Every wake-up thus killed playback and on top of that ran into a stuck
 * libVLC shutdown (see PlayerActivity.teardownPlayerAsync). We start a new MainActivity
 * only when no app task is running.
 */
object AutostartLaunch {
    fun bringToFrontOrStart(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val task = runCatching {
            am?.appTasks?.firstOrNull { t -> runCatching { t.taskInfo.numActivities > 0 }.getOrDefault(false) }
        }.getOrNull()
        if (task != null) {
            if (runCatching { task.moveToFront() }.isSuccess) return
        }
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(launch) }
    }
}

/** After the set-top box boots, start the app if it is enabled in settings. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val boot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!boot) return
        if (!AutostartPref.isEnabled(context)) return
        AutostartLaunch.bringToFrontOrStart(context)   // M535
    }
}
