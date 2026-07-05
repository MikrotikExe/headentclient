package sk.tvhclient.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider

/** Odoslanie diagnostického logu (M353) — otvorí systémový share sheet
 *  s predvyplneným e-mailom na support a priloženým log súborom. */
object CrashLogReporter {
    private const val SUPPORT = "support@headentclient.com"

    fun share(context: Context) {
        val file = CrashLogger.logFile(context)
        if (!file.exists()) return
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return

        val subject = "HeadentClient diagnostic log"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.diag_email_body))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, subject)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }
}
