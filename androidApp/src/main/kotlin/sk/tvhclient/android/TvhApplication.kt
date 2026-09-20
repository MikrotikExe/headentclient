package sk.tvhclient.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import sk.tvhclient.shared.storage.initSecureStorage

class TvhApplication : Application() {
    // Screen wake-up -> if enabled in the settings, open the app.
    // Works only if the box does not kill the process during sleep (hence "may not work everywhere").
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val a = intent?.action ?: return
            if (a == Intent.ACTION_SCREEN_OFF) {
                WakeTracker.lastScreenOffAt = android.os.SystemClock.elapsedRealtime()   // M540
                return
            }
            if (a != Intent.ACTION_SCREEN_ON && a != Intent.ACTION_USER_PRESENT) return
            if (!AutostartPref.isWakeEnabled(context)) return
            // M535: just bring a running task to the front (do not tear the player down), otherwise start
            AutostartLaunch.bringToFrontOrStart(context)
        }
    }

    // Change of the system 12/24 hour setting (M423-fix). Android reports it
    // via ACTION_TIME_CHANGED — the system TextClock handles it the same way.
    private val timeFormatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_TIME_CHANGED) return
            ClockPref.onSystemFormatChanged(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // M440: version into the unified client identity (User-Agent, HTSP clientname)
        sk.tvhclient.shared.ClientIdent.version =
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
                .getOrNull() ?: "?"
        // M511: language preference for the EPG — Tvheadend uses it to pick the language
        // variant of an event (OTA vs XMLTV). Without it we get the server default.
        runCatching {
            val loc = java.util.Locale.getDefault()
            val l2 = loc.language.lowercase()
            if (l2.isNotBlank()) {
                // RFC 2616 list: own language, English as the fallback
                sk.tvhclient.shared.ClientIdent.lang2 = if (l2 == "en") "en" else "$l2,en"
                sk.tvhclient.shared.ClientIdent.lang3 = loc.isO3Language.lowercase()
            }
        }
        CrashLogger.install(this)   // crash diagnostics (M353)
        initSecureStorage(this)
        ClockPref.apply(this)       // clock format into the shared module (M423)
        // Since Android 8 SCREEN_ON cannot be registered in the manifest — only at runtime.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)   // M540: when the box went to sleep (the player swaps the audio output)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { registerReceiver(screenOnReceiver, filter) }
        runCatching {
            registerReceiver(timeFormatReceiver, IntentFilter(Intent.ACTION_TIME_CHANGED))
        }
    }
}

/**
 * M540: the time the screen last went off (standby). PlayerActivity uses it
 * in onStart to tell a return from the background after standby (after which AudioTrack is
 * dead on Amlogic -> straight to a new player, without waiting for the watchdog) from an ordinary
 * return (TV guide, PiP, another app).
 */
object WakeTracker {
    @Volatile var lastScreenOffAt = 0L
    /** Did the screen go off since (or just before) the given time? */
    fun screenWentOffSince(sinceElapsed: Long): Boolean =
        lastScreenOffAt > 0L && lastScreenOffAt >= sinceElapsed - 3000L
}
