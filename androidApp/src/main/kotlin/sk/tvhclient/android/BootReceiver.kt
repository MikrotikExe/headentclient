package sk.tvhclient.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Nastavenie automatickeho spustenia po zapnuti zariadenia. */
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
 * M535: spolocne spustenie appky z autostartu (boot aj prebudenie).
 *
 * Ak uloha appky uz existuje (proces prezil standby, alebo Amlogic box poslal
 * QUICKBOOT_POWERON pri prebudeni, nie pri skutocnom boote), staci ju presunut
 * dopredu. Povodne sa vzdy startovala MainActivity s NEW_TASK — a kedze je
 * singleTask, system pri tom zavrel vsetko nad nou, teda aj beziaci prehravac.
 * Kazde prebudenie tak zhodilo prehravanie a k tomu narazilo na zaseknute
 * ukoncenie libVLC (pozri PlayerActivity.teardownPlayerAsync). Novu MainActivity
 * startujeme len vtedy, ked ziadna uloha appky nebezi.
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

/** Po nabootovani setoboxu spusti appku, ak je to v nastaveniach zapnute. */
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
