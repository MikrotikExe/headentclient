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
/**
 * M751: "Smooth interlaced HD channels" — a channel that was interlaced last time goes through the
 * hardware decoder without direct rendering and VLC's deinterlacer (see MediaFactory.smoothFor).
 * Off by default: nothing changes until the user turns it on.
 */
object InterlacedSmoothPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "interlaced_smooth"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}

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
 * M722: the video of a channel as seen the last time it played (HTSP subscriptionStart or the
 * libVLC video track), so that the next opening chooses the decoder before the stream starts.
 * Codec values: "MPEG2VIDEO", "H264", "HEVC"… (the HTSP names). M730: the picture height too
 * (the MPEG-2 quality option applies up to 720 lines), and the flag that the hardware decoder
 * gave no picture on this channel (issue #37) — from then on it is decoded in software.
 * Stored as one string: CODEC[@HEIGHT][!sw] (older entries hold the bare codec).
 */
object ChannelCodecMemo {
    private const val PREFS = "codec_memo"

    data class Memo(val codec: String?, val height: Int, val swForced: Boolean) {
        override fun toString(): String = buildString {
            append(codec ?: "")
            if (height > 0) append('@').append(height)
            if (swForced) append("!sw")
        }
        companion object {
            fun parse(raw: String?): Memo? {
                if (raw.isNullOrBlank()) return null
                var s = raw
                val sw = s.endsWith("!sw")
                if (sw) s = s.removeSuffix("!sw")
                val at = s.indexOf('@')
                val codec = (if (at >= 0) s.substring(0, at) else s).ifBlank { null }
                val h = if (at >= 0) s.substring(at + 1).toIntOrNull() ?: 0 else 0
                return Memo(codec, h, sw)
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context, serverId: String?, uuid: String?): Memo? {
        if (serverId == null || uuid == null) return null
        return Memo.parse(prefs(context).getString("$serverId:$uuid", null))
    }

    /** Remembers the codec and, when known (> 0), the height; the software flag of the channel is kept. */
    fun set(context: Context, serverId: String?, uuid: String?, codec: String, height: Int = 0): Memo? {
        if (serverId == null || uuid == null) return null
        val old = get(context, serverId, uuid)
        val m = Memo(codec, if (height > 0) height else old?.height ?: 0, old?.swForced ?: false)
        prefs(context).edit().putString("$serverId:$uuid", m.toString()).apply()
        return m
    }

    /** M730 (issue #37): the hardware decoder gave no picture on this channel -> software from now on. */
    fun forceSoftware(context: Context, serverId: String?, uuid: String?, on: Boolean): Memo? {
        if (serverId == null || uuid == null) return null
        val old = get(context, serverId, uuid)
        val m = Memo(old?.codec, old?.height ?: 0, on)
        prefs(context).edit().putString("$serverId:$uuid", m.toString()).apply()
        return m
    }

    /**
     * libVLC codec -> the HTSP name (mpgv/mp2v = MPEG-2). null = unknown.
     * M734: libVLC-Android's IMedia.Track.codec is the codec DESCRIPTION ("MPEG-H Part2/HEVC (H.265)",
     * "MPEG AAC Audio", "MPEG-1/2 Video"…), not the fourcc — until now nothing matched for an HTTP
     * channel, the memo held the raw description and the MPEG-2 quality option never applied over
     * HTTP. Both forms are recognised now.
     */
    fun fromVlc(codec: String?): String? {
        if (codec.isNullOrBlank()) return null
        val c = codec.lowercase()
        return when {
            c == "mpgv" || c == "mp2v" || c == "mpg2" || c.contains("mpeg-1/2 video") || c.contains("mpeg-2 video") || c.contains("mpeg video") -> "MPEG2VIDEO"
            c == "h264" || c == "avc1" || c.contains("h.264") || c.contains("h264") || c.contains("avc") -> "H264"
            c == "hevc" || c == "hvc1" || c == "hev1" || c == "h265" || c.contains("hevc") || c.contains("h.265") -> "HEVC"
            c == "vp9" || c == "vp90" || c.contains("vp9") -> "VP9"
            c == "av01" || c == "av1" || c.contains("av1") -> "AV1"
            c == "mp4v" || c.contains("mpeg-4 video") -> "MPEG4VIDEO"
            c == "eac3" || c.contains("e-ac-3") || c.contains("eac3") || c.contains("digital plus") -> "EAC3"
            c == "a52" || c == "a52b" || c.contains("a52") || c.contains("ac-3") || c.contains("ac3") || c.contains("dolby digital") -> "AC3"
            c == "mp4a" || c == "aac" || c.contains("aac") -> "AAC"
            c == "mpga" || c == "mp3" || c == "mp2a" || c.contains("mpeg audio") || c.contains("mpeg-1/2 audio") || c.contains("mp3") -> "MPEG2AUDIO"   // M724
            else -> codec.uppercase()
        }
    }
}
