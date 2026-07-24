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
            val launch = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            runCatching { context.startActivity(launch) }
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
