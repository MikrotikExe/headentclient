package sk.tvhclient.android

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash and error diagnostics (M353). Writes both uncaught exceptions and
 * manually reported errors from critical places into a file in the app's
 * internal storage (filesDir/diag/crash.log). The user can view the log and
 * send it by e-mail from Settings → About → Diagnostic log.
 *
 * The file is in the app's private storage and is shared via FileProvider (no
 * storage permissions needed).
 */
object CrashLogger {
    private const val DIR = "diag"
    private const val FILE = "crash.log"
    private const val MAX_BYTES = 256 * 1024   // rotation after 256 kB

    fun logFile(context: Context): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, FILE)
    }

    /** Installs the global uncaught exception handler (called from Application). */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                write(appContext, "FATAL", "thread=${thread.name}", throwable)
            }
            // hand over to the original handler (system "app crashed" dialog)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Manual report of a caught error from a critical place (the app does not crash). */
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
            // simple rotation — keep only the header, discard the old content
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
