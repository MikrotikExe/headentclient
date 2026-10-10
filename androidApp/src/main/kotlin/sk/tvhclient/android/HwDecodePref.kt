package sk.tvhclient.android

import android.content.Context

/**
 * M722 (issue #23): how the video is decoded — the three modes of the official VLC app
 * (and of Dream Player, whose "Hardware acceleration" is the same list):
 *  - AUTO: the hardware decoder with direct rendering — the decoded picture goes from the chip
 *    straight to the screen (fastest; VLC's deinterlacer never sees the frames).
 *  - DECODE: the hardware decoder without direct rendering (`:no-mediacodec-dr`, VLC's
 *    "Decoding acceleration") — the frames pass through VLC, so deinterlacing works and the
 *    Amlogic half-height field output is not shown as is. One picture copy per frame.
 *  - OFF: software decoding (the former "Software video decoding" switch, M447 — migrated).
 */
object HwDecodePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "hw_decode"

    const val AUTO = "auto"
    const val DECODE = "decode"
    const val OFF = "off"

    val options = listOf(AUTO, DECODE, OFF)

    fun get(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY, null)?.let { return it }
        return if (SwDecodePref.get(context)) OFF else AUTO   // M447 migration
    }

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}

/**
 * M722 (issue #23): "MPEG-2 (SD channels): best picture quality" — MPEG-2 is decoded in
 * software with deinterlacing (what Dream Player does by default: its "MPEG2: Playback type =
 * Image quality"). MPEG-2 SD is cheap to decode even on a weak box, and only the software path
 * gives a properly deinterlaced, sharp picture on boxes whose hardware decoder outputs an
 * interlaced or half-height picture. H.264 / HEVC stay in hardware. On by default.
 */
object Mpeg2SoftwarePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "mpeg2_sw"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}

/**
 * M722: the video codec of a channel as seen the last time it played (HTSP subscriptionStart
 * or the libVLC video track), so that the next opening chooses the decoder before the stream
 * starts. Values: "MPEG2VIDEO", "H264", "HEVC"… (the HTSP names).
 */
object ChannelCodecMemo {
    private const val PREFS = "codec_memo"

    fun get(context: Context, serverId: String?, uuid: String?): String? {
        if (serverId == null || uuid == null) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("$serverId:$uuid", null)
    }

    fun set(context: Context, serverId: String?, uuid: String?, codec: String) {
        if (serverId == null || uuid == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("$serverId:$uuid", codec).apply()
    }

    /** libVLC codec fourcc -> the HTSP name (mpgv/mp2v = MPEG-2). null = unknown. */
    fun fromVlc(fourcc: String?): String? = when (fourcc?.lowercase()) {
        "mpgv", "mp2v", "mpg2" -> "MPEG2VIDEO"
        "h264", "avc1" -> "H264"
        "hevc", "hvc1", "hev1", "h265" -> "HEVC"
        "vp9", "vp90" -> "VP9"
        "av01", "av1" -> "AV1"
        null -> null
        else -> fourcc.uppercase()
    }
}
