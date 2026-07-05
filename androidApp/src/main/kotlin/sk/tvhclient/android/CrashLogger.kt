package sk.tvhclient.android

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostika pádov a chýb (M353). Zapisuje neodchytené výnimky aj ručne
 * hlásené chyby z kritických miest do súboru v internom úložisku appky
 * (filesDir/diag/crash.log). Log si používateľ vie zobraziť a odoslať
 * e-mailom z Nastavenia → O aplikácii → Diagnostický log.
 *
 * Súbor je v privátnom úložisku appky, zdieľa sa cez FileProvider (žiadne
 * povolenia na úložisko netreba).
 */
object CrashLogger {
    private const val DIR = "diag"
    private const val FILE = "crash.log"
    private const val MAX_BYTES = 256 * 1024   // rotácia po 256 kB

    fun logFile(context: Context): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, FILE)
    }

    /** Nainštaluje globálny handler neodchytených výnimiek (volané z Application). */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                write(appContext, "FATAL", "thread=${thread.name}", throwable)
            }
            // odovzdaj povodnemu handleru (systemovy dialog "appka spadla")
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Ručné hlásenie zachytenej chyby z kritického miesta (nepadne appka). */
    fun report(context: Context, where: String, throwable: Throwable) {
        runCatching { write(context.applicationContext, "ERROR", where, throwable) }
    }

    fun report(context: Context, where: String, message: String) {
        runCatching { write(context.applicationContext, "WARN", where, null, message) }
    }

    private fun write(
        context: Context, level: String, where: String,
        throwable: Throwable?, message: String? = null
    ) {
        val f = logFile(context)
        if (f.exists() && f.length() > MAX_BYTES) {
            // jednoduchá rotácia — ponechaj len hlavičku, staré zahoď
            runCatching { f.writeText(header(context) + "\n[log rotated]\n") }
        } else if (!f.exists()) {
            runCatching { f.writeText(header(context) + "\n") }
        }
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("\n[$ts] $level @ $where\n")
        message?.let { sb.append("  $it\n") }
        throwable?.let {
            sb.append("  ").append(it.toString()).append("\n")
            it.stackTrace.take(12).forEach { st -> sb.append("    at $st\n") }
            it.cause?.let { c ->
                sb.append("  caused by: ").append(c.toString()).append("\n")
                c.stackTrace.take(6).forEach { st -> sb.append("    at $st\n") }
            }
        }
        runCatching { f.appendText(sb.toString()) }
    }

    private fun header(context: Context): String {
        val v = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
        }.getOrDefault("?")
        return "HeadentClient diagnostic log\n" +
            "app=$v\n" +
            "device=${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "tv=${context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)}"
    }

    fun readText(context: Context): String =
        runCatching { logFile(context).readText() }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { logFile(context).delete() }
    }
}
