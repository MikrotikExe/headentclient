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

    /**
     * M722: software decoding for a channel that looked like [memo] last time (null = unknown)?
     * OFF mode; the channel on which the hardware decoder gave no picture (M730, issue #37); or
     * MPEG-2 with the quality option — M730 (audit R16): only up to 720 lines, a 1080i MPEG-2
     * channel would cost a weak box a software yadif at full HD.
     */
    fun softwareFor(memo: ChannelCodecMemo.Memo?): Boolean =
        HwDecodePref.get(ctx) == HwDecodePref.OFF || memo?.swForced == true ||
            (Mpeg2SoftwarePref.get(ctx) && memo?.codec == "MPEG2VIDEO" && (memo?.height ?: 0) <= 720)

    /** Direct URL (HTTP live / DVR): HW/SW decoder, User-Agent, deinterlacing. [codec] = what the channel's video looked like last time. */
    fun forUrl(url: String, demux: String? = null, codec: ChannelCodecMemo.Memo? = null): Media {
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
    fun forFeeder(fd: FileDescriptor, demux: String?, cachingMs: Int, codec: ChannelCodecMemo.Memo? = null): Media {
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
    private fun applyDecoder(m: Media, codec: ChannelCodecMemo.Memo?) {
        val sw = softwareFor(codec)
        val smooth = !sw && smoothFor(codec)
        lastSoftware = sw
        lastSmooth = smooth
        // M730: the decision goes into the diagnostic log with the verbose player log (issue #37)
        if (VlcVerbosePref.get(ctx)) CrashLogger.report(ctx, "MediaFactory.decoder",
            "decoder=${if (sw) "software" else "hardware"} mode=${HwDecodePref.get(ctx)} smooth=$smooth memo=${codec ?: "none"}")
        m.setHWDecoderEnabled(!sw, false)
        // M752 (performance audit P2): software H.264/HEVC skips the loop filter on non-reference
        // frames — a weak box keeps up with less CPU, the picture loses next to nothing (MPEG-2 has no
        // loop filter, so it is unaffected). VLC's frame skipping is already on by default.
        if (sw) {
            // M753 (dream Player's software path, read from its libVLC configuration): deblocking by
            // CPU class (1 = non-reference frames on 4+ cores, 3 = non-key frames on weaker chips),
            // allowed speed-ups, IDCT skipped on non-reference frames; libVLC's threads stay automatic
            val cores = Runtime.getRuntime().availableProcessors()
            m.addOption(":avcodec-skiploopfilter=" + if (cores >= 4) 1 else 3)
            m.addOption(":avcodec-fast")
            m.addOption(":avcodec-skip-idct=1")
        }
        var (en, mode) = deinterlaceSpec(sw)
        // M751: an interlaced channel with "Smooth interlaced HD channels" on — the hardware still
        // decodes, but the frames pass through VLC's deinterlacer instead of going straight to the
        // screen (where the filter never runs and a chip that does not deinterlace shows combs).
        // An explicit "Off" in the deinterlacing setting is respected.
        if (smooth && DeinterlacePref.get(ctx) != DeinterlacePref.OFF) {
            en = "1"
            if (mode == null) mode = "linear"
        }
        m.addOption(":deinterlace=$en")
        if (mode != null) m.addOption(":deinterlace-mode=$mode")
        if (!sw && (smooth || noDirectRendering || HwDecodePref.get(ctx) == HwDecodePref.DECODE || explicitDeinterlaceOnHw())) {
            m.addOption(":no-mediacodec-dr")
            m.addOption(":no-omxil-dr")
        }
    }

    /**
     * M753 (issue #37; dream Player does the same for this device): on Google Pixel 10 the hardware
     * decoder with direct rendering gives no picture on some streams — hardware decoding stays, the
     * frames just go through the player (no direct rendering), which works there. Until now the
     * channel first showed black for seconds and then fell back to software (M730).
     */
    val noDirectRendering: Boolean by lazy {
        android.os.Build.BRAND.equals("google", ignoreCase = true) &&
            android.os.Build.MODEL.lowercase().contains("pixel 10")
    }

    /** M751: was the last medium built with the interlaced-channel path ([smoothFor])? */
    @Volatile var lastSmooth: Boolean = false
        private set

    /**
     * M751: "Smooth interlaced HD channels" applies to a channel that looked interlaced last time —
     * 480/576/1080 lines (1088 = 1080 from the decoder). Only in hardware AUTO mode: DECODE already
     * passes the frames through the filters, OFF decodes in software. 720p, 4K and an unknown channel
     * stay on direct rendering.
     */
    fun smoothFor(memo: ChannelCodecMemo.Memo?): Boolean =
        memo != null && memo.height in INTERLACED_HEIGHTS &&
            HwDecodePref.get(ctx) == HwDecodePref.AUTO && InterlacedSmoothPref.get(ctx)

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
        /** M751: frame heights of interlaced broadcasts (SD 480i/576i, HD 1080i; 1088 = coded 1080). */
        private val INTERLACED_HEIGHTS = setOf(480, 576, 1080, 1088)

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
