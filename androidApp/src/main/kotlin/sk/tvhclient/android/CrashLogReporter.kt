package sk.tvhclient.android

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Odoslanie diagnostického logu (M353, telo M354) — otvorí systémový share
 *  sheet s e-mailom na support: predmet s dátumom a časom, telo s
 *  predvyplneným zariadením/Androidom/verziou appky a prázdnym riadkom na
 *  popis problému, plus priložený log súbor. */
object CrashLogReporter {
    private const val SUPPORT = "support@headentclient.com"

    fun share(context: Context) {
        val file = CrashLogger.logFile(context)
        if (!file.exists()) return
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return

        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val subject = "HeadentClient log — $now"

        val appVer = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
                else @Suppress("DEPRECATION") pi.versionCode.toLong()
            "${pi.versionName} ($code)"
        }.getOrDefault("?")

        // Predvyplnene telo: zariadenie/Android/verzia automaticky, pouzivatel
        // dopise len co robil ked nastal problem.
        val body = context.getString(R.string.diag_email_body) + "\n\n" +
            "————————————\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "App: $appVer\n" +
            "Date: $now\n"

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, subject)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }
}
