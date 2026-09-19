package sk.tvhclient.android

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import java.io.FileDescriptor

/**
 * M654: stavba libVLC [Media] pre prehrávač (vyclenené z PlayerActivity) — priame URL aj
 * feeder cesta (fd z HTSP/HTTP feedera), HW/SW dekodér (M447), deinterlacing, demuxer pre
 * feeder (M381/M509), User-Agent. Čisté pomocné funkcie bez stavu; [libVlc] ako lambda,
 * lebo recreatePlayer (M539) inštanciu vymieňa.
 */
internal class MediaFactory(private val ctx: Context, private val libVlc: () -> LibVLC) {

    fun userAgent(): String = sk.tvhclient.shared.ClientIdent.userAgent

    /** Priame URL (HTTP live / DVR): HW/SW dekodér, User-Agent, deinterlacing. */
    fun forUrl(url: String): Media {
        val m = Media(libVlc(), Uri.parse(url))
        m.setHWDecoderEnabled(!SwDecodePref.get(ctx), false)  // M447
        // User-Agent: nech server vidi, ze sa pripaja HeadentClient
        m.addOption(":http-user-agent=" + userAgent())
        applyDeinterlace(m)
        return m
    }

    /**
     * Feeder cesta (bajty cez fd): HW/SW dekodér, [demux] (":demux=ts" alebo null = VLC probing),
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

    /** Rezim deinterlacingu z nastaveni -> (hodnota --deinterlace, mod alebo null).
     *  -1 = automaticky (deinterlacuje len prekladany zdroj), 0 = vypnute, 1 = zapnute. */
    fun deinterlaceSpec(): Pair<String, String?> = when (DeinterlacePref.get(ctx)) {
        DeinterlacePref.OFF -> "0" to null
        DeinterlacePref.BOB -> "1" to "bob"
        DeinterlacePref.YADIF -> "1" to "yadif"
        DeinterlacePref.YADIF2X -> "1" to "yadif2x"
        DeinterlacePref.X -> "1" to "x"
        else -> "-1" to "yadif"   // AUTO
    }

    /** Aplikuje deinterlacing na dane medium (riesi hrebenove pasy / combing pri
     *  prekladanom DVB videu na rychlych zaberoch). */
    private fun applyDeinterlace(m: Media) {
        val (en, mode) = deinterlaceSpec()
        m.addOption(":deinterlace=$en")
        if (mode != null) m.addOption(":deinterlace-mode=$mode")
    }

    /**
     * M381: demuxer pre feeder cestu. Feeder posiela bajty cez fd, takze libVLC
     * nema nazov suboru ani MIME a kontajner musi uhadnut. Pri MPEG-TS profiloch
     * (pass, htsp, *-mpegts) mu ho dame natvrdo — TS sa chyta aj uprostred toku
     * a probing tam byva pomaly. Pri ostatnych (matroska, webm, mp4) natvrdo ts
     * znamenalo, ze sa stream vobec neotvoril; tam necháme VLC probing (EBML /
     * ftyp hlavicka je na zaciatku toku, takze sa kontajner urci spolahlivo).
     * Vracia "ts" alebo null (= probing).
     */
    fun feederDemuxFor(url: String): String? {
        val prof = Regex("[?&]profile=([^&]*)")
            .find(url)?.groupValues?.get(1)?.lowercase().orEmpty()
        // M509: prazdny profil UZ NEZNAMENA TS. Od M502 je prazdna hodnota
        // „podla nastavenia servera" a ten moze mat predvoleny hocijaky
        // kontajner. Vnutit ts naslepo znamenalo cierny obraz pri matroske.
        // Vnucujeme ho len tam, kde vieme, ze o TS naozaj ide.
        val isTs = prof == "pass" || prof == "htsp" || prof.endsWith("mpegts")
        return if (isTs) "ts" else null
    }

    companion object {
        /** Odstrani user:pass@ z URL (pre feeder/probe — auth riesi OkHttp hlavickou). */
        fun stripCreds(url: String): String {
            val i = url.indexOf("://")
            if (i < 0) return url
            val rest = url.substring(i + 3)
            val at = rest.indexOf('@')
            val slash = rest.indexOf('/')
            if (at < 0 || (slash in 0 until at)) return url
            return url.substring(0, i + 3) + rest.substring(at + 1)
        }

        /** M390-fix4: vyzera identifikator ako REST uuid Tvheadendu (32 hex znakov)? */
        fun looksLikeRestUuid(u: String): Boolean =
            u.length == 32 && u.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }
    }
}
