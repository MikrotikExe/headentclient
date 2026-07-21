package sk.tvhclient.android

import android.content.Context

/** Používateľská korekcia synchronizácie zvuku ako init voľba libVLC (--audio-desync).
 *  Na rozdiel od setAudioDelay za behu sa aplikuje pri vytváraní prehrávača a správa
 *  sa robustnejšie (rovnaký prístup ako jellyfin-androidtv). Hodnota v milisekundách:
 *  záporná = zvuk skôr, kladná = zvuk neskôr. 0 = vypnuté (predvolené — nič nemení). */
object AudioDesyncPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "audio_desync_ms"

    fun getMs(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0)

    fun setMs(context: Context, ms: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY, ms).apply()
    }
}
