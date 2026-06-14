package sk.tvhclient.android

import android.content.Context

/**
 * Per-kanal nastavenia: profil prehravania (override servera) a zapamatana
 * posledna audio stopa (podla nazvu, aby prezila restart). Klucovane serverId+uuid.
 */
object ChannelPrefs {
    private const val PREFS = "channel_prefs"
    private fun profKey(sid: String, uuid: String) = "prof:$sid:$uuid"
    private fun audKey(sid: String, uuid: String) = "aud:$sid:$uuid"

    /** "" = pouzi profil servera. */
    fun getProfile(context: Context, serverId: String, uuid: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(profKey(serverId, uuid), "") ?: ""

    fun setProfile(context: Context, serverId: String, uuid: String, profile: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(profKey(serverId, uuid), profile).apply()
    }

    fun getLastAudio(context: Context, serverId: String, uuid: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(audKey(serverId, uuid), "") ?: ""

    fun setLastAudio(context: Context, serverId: String, uuid: String, trackName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(audKey(serverId, uuid), trackName).apply()
    }

    /** Ponuka profilov (kod -> popis). "" = predvolený zo servera. */
    val profileOptions: List<Pair<String, String>> = listOf(
        "" to "—",
        "pass" to "pass",
        "mpegts" to "mpegts",
        "matroska" to "matroska",
        "webtv-h264-aac-matroska" to "webtv-h264-aac-matroska",
        "webtv-h264-aac-mpegts" to "webtv-h264-aac-mpegts"
    )
}
