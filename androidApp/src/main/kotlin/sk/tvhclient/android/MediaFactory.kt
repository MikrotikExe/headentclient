package sk.tvhclient.android

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import java.io.FileDescriptor

/**
 * M654: building the libVLC [Media] for the player (split out of PlayerActivity) — both a direct URL and
 * the feeder path (fd from the HTSP/HTTP feeder), HW/SW decoder (M447), deinterlacing, the demuxer for the
 * feeder (M381/M509), User-Agent. Pure stateless helper functions; [libVlc] as a lambda,
 * because recreatePlayer (M539) swaps the instance.
 */
internal class MediaFactory(private val ctx: Context, private val libVlc: () -> LibVLC) {

    fun userAgent(): String = sk.tvhclient.shared.ClientIdent.userAgent

    /** M722: was the last medium built for software decoding? (the one-off restart after the codec is known) */
    @Volatile var lastSoftware: Boolean = false
        private set

    /** M722: software decoding for this [codec] (HTSP name, null = unknown)? OFF mode, or MPEG-2 with the quality option. */
    fun softwareFor(codec: String?): Boolean =
        HwDecodePref.get(ctx) == HwDecodePref.OFF || (Mpeg2SoftwarePref.get(ctx) && codec == "MPEG2VIDEO")

    /** Direct URL (HTTP live / DVR): HW/SW decoder, User-Agent, deinterlacing. [codec] = the channel's known video codec. */
    fun forUrl(url: String, demux: String? = null, codec: String? = null): Media {
        val m = Media(libVlc(), Uri.parse(url))
        if (demux != null) m.addOption(":demux=$demux")   // M703: e.g. "avformat,any" for Matroska recordings
        applyDecoder(m, codec)   // M447 / M722
        // User-Agent: so that the server sees that HeadentClient is connecting
        m.addOption(":http-user-agent=" + userAgent())
        return m
    }

    /**
     * Feeder path (bytes through an fd): HW/SW decoder, [demux] (":demux=ts" or null = VLC probing),
     * file-caching [cachingMs], deinterlacing.
     */
    fun forFeeder(fd: FileDescriptor, demux: String?, cachingMs: Int, codec: String? = null): Media {
        val media = Media(libVlc(), fd)
        applyDecoder(media, codec)   // M447 / M722
        if (demux != null) media.addOption(":demux=$demux")
        media.addOption(":file-caching=$cachingMs")
        return media
    }

    /**
     * M722 (issue #23): the decoder and the deinterlacer for one medium.
     *  - software (OFF, or MPEG-2 with the quality option): libVLC decodes and deinterlaces (yadif).
     *  - hardware DECODE mode, or AUTO with an explicit deinterlacing mode (M718): the hardware
     *    decoder without direct rendering — the frames pass through VLC's filters.
     *  - hardware AUTO: direct rendering as before (4K/HDR untouched; the filter never runs).
     */
    private fun applyDecoder(m: Media, codec: String?) {
        val sw = softwareFor(codec)
        lastSoftware = sw
        m.setHWDecoderEnabled(!sw, false)
        val (en, mode) = deinterlaceSpec(sw)
        m.addOption(":deinterlace=$en")
        if (mode != null) m.addOption(":deinterlace-mode=$mode")
        if (!sw && (HwDecodePref.get(ctx) == HwDecodePref.DECODE || explicitDeinterlaceOnHw())) {
            m.addOption(":no-mediacodec-dr")
            m.addOption(":no-omxil-dr")
        }
    }

    /** Deinterlacing mode from the settings -> (the --deinterlace value, the mode or null).
     *  -1 = automatic (deinterlaces only an interlaced source), 0 = off, 1 = on.
     *
     *  M718 (issue #23): with hardware decoding (without direct rendering) the frames arrive as
     *  NV12, where VLC's yadif / yadif2x / x are not available and VLC silently falls back to the
     *  blurry "blend". That is why in hardware they map to "linear" (yadif) and "bob" (yadif2x, x)
     *  — both work on NV12 and are cheap on the CPU. M722: [sw] = the medium is decoded in software. */
    fun deinterlaceSpec(sw: Boolean = HwDecodePref.get(ctx) == HwDecodePref.OFF): Pair<String, String?> =
        when (DeinterlacePref.get(ctx)) {
            DeinterlacePref.OFF -> "0" to null
            DeinterlacePref.BOB -> "1" to "bob"
            DeinterlacePref.YADIF -> "1" to (if (sw) "yadif" else "linear")
            DeinterlacePref.YADIF2X -> "1" to (if (sw) "yadif2x" else "bob")
            DeinterlacePref.X -> "1" to (if (sw) "x" else "bob")
            else -> "-1" to (if (sw) "yadif" else "linear")   // AUTO: only an interlaced source
        }

    /** True when an explicit deinterlacing mode is chosen (it then needs the frames even in hardware AUTO mode, M718). */
    private fun explicitDeinterlaceOnHw(): Boolean {
        val mode = DeinterlacePref.get(ctx)
        return mode != DeinterlacePref.OFF && mode != DeinterlacePref.AUTO
    }

    /**
     * M381: the demuxer for the feeder path. The feeder sends bytes through an fd, so libVLC
     * has neither a file name nor a MIME type and has to guess the container. With MPEG-TS profiles
     * (pass, htsp, *-mpegts) we give it to it hard-coded — TS can be picked up even mid-stream
     * and probing there tends to be slow. With the others (matroska, webm, mp4) hard-coded ts
     * meant the stream did not open at all; there we leave VLC probing (the EBML /
     * ftyp header is at the start of the stream, so the container is determined reliably).
     * Returns "ts" or null (= probing).
     */
    fun feederDemuxFor(url: String): String? {
        val prof = Regex("[?&]profile=([^&]*)")
            .find(url)?.groupValues?.get(1)?.lowercase().orEmpty()
        // M509: an empty profile NO LONGER MEANS TS. Since M502 an empty value is
        // "as per the server setting" and that may have any container as its
        // default. Forcing ts blindly meant a black picture with matroska.
        // We force it only where we know it really is TS.
        val isTs = prof == "pass" || prof == "htsp" || prof.endsWith("mpegts")
        return if (isTs) "ts" else null
    }

    companion object {
        /** Removes user:pass@ from a URL (for the feeder/probe — auth is handled by OkHttp with a header). */
        fun stripCreds(url: String): String {
            val i = url.indexOf("://")
            if (i < 0) return url
            val rest = url.substring(i + 3)
            val at = rest.indexOf('@')
            val slash = rest.indexOf('/')
            if (at < 0 || (slash in 0 until at)) return url
            return url.substring(0, i + 3) + rest.substring(at + 1)
        }

        /** M390-fix4: does the identifier look like a Tvheadend REST uuid (32 hex characters)? */
        fun looksLikeRestUuid(u: String): Boolean =
            u.length == 32 && u.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
    }
}
