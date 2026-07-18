package sk.tvhclient.android

import android.content.Context

/**
 * Per-kanal nastavenia: profil prehravania (override servera) a zapamatana
 * posledna audio stopa (podla nazvu, aby prezila restart). Klucovane serverId+uuid.
 */
object ChannelPrefs {
    private const val PREFS = "channel_prefs"
    private fun audKey(sid: String, uuid: String) = "aud:$sid:$uuid"

    fun getLastAudio(context: Context, serverId: String, uuid: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(audKey(serverId, uuid), "") ?: ""

    fun setLastAudio(context: Context, serverId: String, uuid: String, trackName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(audKey(serverId, uuid), trackName).apply()
    }

    /**
     * Ponuka profilov (kod -> popis). "" = predvolený zo servera.
     *
     * M379: zoznam zodpoveda predvolenym stream profilom cisteho Tvheadend
     * servera (Configuration -> Stream -> Stream Profiles). Predtym tu bol
     * vymysleny "mpegts" (na servery neexistuje — MPEG-TS passthrough robi
     * prave "pass") a chybali htsp, webtv-h264-vorbis-mp4 a webtv-vp8-vorbis-webm.
     * Predvolba appky je "pass" (najvyssia kvalita, bez transkodovania).
     */
    val profileOptions: List<Pair<String, String>> = listOf(
        "" to "—",
        "pass" to "pass",
        "htsp" to "htsp",
        "matroska" to "matroska",
        "webtv-h264-aac-matroska" to "webtv-h264-aac-matroska",
        "webtv-h264-aac-mpegts" to "webtv-h264-aac-mpegts",
        "webtv-h264-vorbis-mp4" to "webtv-h264-vorbis-mp4",
        "webtv-vp8-vorbis-webm" to "webtv-vp8-vorbis-webm"
    )
}
