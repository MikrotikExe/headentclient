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

    /** Direct URL (HTTP live / DVR): HW/SW decoder, User-Agent, deinterlacing. */
    fun forUrl(url: String): Media {
        val m = Media(libVlc(), Uri.parse(url))
        m.setHWDecoderEnabled(!SwDecodePref.get(ctx), false)  // M447
        // User-Agent: so that the server sees that HeadentClient is connecting
        m.addOption(":http-user-agent=" + userAgent())
        applyDeinterlace(m)
        return m
    }

    /**
     * Feeder path (bytes through an fd): HW/SW decoder, [demux] (":demux=ts" or null = VLC probing),
     * file-caching [cachingMs], deinterlacing.
     */
    fun forFeeder(fd: FileDescriptor, demux: String?, cachingMs: Int): Media {
        val media = Media(libVlc(), fd)
        media.setHWDecoderEnabled(!SwDecodePref.get(ctx), false)  // M447
        if (demux != null) media.addOption(":demux=$demux")
        media.addOption(":file-caching=$cachingMs")
        applyDeinterlace(media)
        return media
    }

    /** Deinterlacing mode from the settings -> (the --deinterlace value, the mode or null).
     *  -1 = automatic (deinterlaces only an interlaced source), 0 = off, 1 = on. */
    fun deinterlaceSpec(): Pair<String, String?> = when (DeinterlacePref.get(ctx)) {
        DeinterlacePref.OFF -> "0" to null
        DeinterlacePref.BOB -> "1" to "bob"
        DeinterlacePref.YADIF -> "1" to "yadif"
        DeinterlacePref.YADIF2X -> "1" to "yadif2x"
        DeinterlacePref.X -> "1" to "x"
        else -> "-1" to "yadif"   // AUTO
    }

    /** Applies deinterlacing to the given medium (deals with comb lines / combing on
     *  interlaced DVB video in fast shots). */
    private fun applyDeinterlace(m: Media) {
        val (en, mode) = deinterlaceSpec()
        m.addOption(":deinterlace=$en")
        if (mode != null) m.addOption(":deinterlace-mode=$mode")
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
