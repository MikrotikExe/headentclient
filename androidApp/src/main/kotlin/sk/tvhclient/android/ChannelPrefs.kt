package sk.tvhclient.android

import android.content.Context

/**
 * Per-channel settings: playback profile (overriding the server) and the remembered
 * last audio track (by name, so it survives a restart). Keyed by serverId+uuid.
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
     * Profile menu (code -> description). "" = the default from the server.
     *
     * M379: the list matches the default stream profiles of a clean Tvheadend
     * server (Configuration -> Stream -> Stream Profiles). Previously there was an
     * invented "mpegts" here (it does not exist on the server — MPEG-TS passthrough is done
     * by "pass") and htsp, webtv-h264-vorbis-mp4 and webtv-vp8-vorbis-webm were missing.
     * The app's default is "pass" (the highest quality, without transcoding).
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
