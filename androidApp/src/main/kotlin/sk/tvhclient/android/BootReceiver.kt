package sk.tvhclient.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Nastavenie automatickeho spustenia po zapnuti zariadenia. */
object AutostartPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "autostart_enabled"
    fun isEnabled(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
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
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(launch) }
    }
}
