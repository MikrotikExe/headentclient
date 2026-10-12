package sk.tvhclient.android

import android.content.Context

/**
 * M724: "Stream details in the info window" (Settings → Advanced → Display). With it on, the
 * player's info window shows a "Stream" section like Kodi's PlayerProcessInfo: codec, resolution,
 * frame rate, audio, decoder, and over HTSP the service, provider, network / mux, adapter and the
 * signal (sourceinfo + signalStatus). Off by default — the window then looks as before.
 */
object StreamInfoPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "stream_info"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
