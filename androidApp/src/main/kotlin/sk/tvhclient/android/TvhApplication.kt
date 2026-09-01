package sk.tvhclient.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import sk.tvhclient.shared.storage.initSecureStorage

class TvhApplication : Application() {
    // Prebudenie obrazovky -> ak je v nastaveniach zapnute, otvor appku.
    // Funguje, len ak box pocas spanku nezabije proces (preto "nemusi fungovat vsade").
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val a = intent?.action ?: return
            if (a != Intent.ACTION_SCREEN_ON && a != Intent.ACTION_USER_PRESENT) return
            if (!AutostartPref.isWakeEnabled(context)) return
            // M535: beziacu ulohu len presun dopredu (nezhadzuj prehravac), inak start
            AutostartLaunch.bringToFrontOrStart(context)
        }
    }

    // Zmena systemoveho nastavenia 12/24 hodin (M423-fix). Android ju hlasi
    // cez ACTION_TIME_CHANGED — rovnako to riesi aj systemovy TextClock.
    private val timeFormatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_TIME_CHANGED) return
            ClockPref.onSystemFormatChanged(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // M440: verzia do jednotnej identity klienta (User-Agent, HTSP clientname)
        sk.tvhclient.shared.ClientIdent.version =
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
                .getOrNull() ?: "?"
        // M511: jazykova preferencia pre EPG — Tvheadend podla nej vybera jazykovu
        // mutaciu udalosti (OTA vs XMLTV). Bez nej dostaneme serverovu predvolbu.
        runCatching {
            val loc = java.util.Locale.getDefault()
            val l2 = loc.language.lowercase()
            if (l2.isNotBlank()) {
                // RFC 2616 zoznam: vlastny jazyk, anglictina ako zaloha
                sk.tvhclient.shared.ClientIdent.lang2 = if (l2 == "en") "en" else "$l2,en"
                sk.tvhclient.shared.ClientIdent.lang3 = loc.isO3Language.lowercase()
            }
        }
        CrashLogger.install(this)   // diagnostika pádov (M353)
        initSecureStorage(this)
        ClockPref.apply(this)       // format hodin do zdielaneho modulu (M423)
        // SCREEN_ON sa od Androidu 8 nedá registrovať v manifeste — len za behu.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { registerReceiver(screenOnReceiver, filter) }
        runCatching {
            registerReceiver(timeFormatReceiver, IntentFilter(Intent.ACTION_TIME_CHANGED))
        }
    }
}
