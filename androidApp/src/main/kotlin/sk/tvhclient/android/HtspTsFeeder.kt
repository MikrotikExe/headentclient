package sk.tvhclient.android

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import sk.tvhclient.shared.htsp.HtspClient
import sk.tvhclient.shared.model.TvhServer
import java.io.FileDescriptor
import java.io.OutputStream

/**
 * M162/M163 — premostí HTSP zivy stream (premuxovany na MPEG-TS) do libVLC cez lokalny pipe.
 * `start` vytvori pipe, spusti korutinu ktora pise TS do write-endu a vrati read FileDescriptor
 * pre Media(libVlc, fd). Subscribuje s timeshift bufferom, takze sa da pauzovat cez
 * subscriptionSpeed. Pri `stop`/zatvoreni read-endu sa write zlomi a slucka skonci.
 */
class HtspTsFeeder(
    private val server: TvhServer,
    private val timeshiftPeriodSec: Int = 0
) {

    private var job: Job? = null
    private var readPfd: ParcelFileDescriptor? = null
    private var writePfd: ParcelFileDescriptor? = null
    private var out: OutputStream? = null
    private var client: HtspClient? = null
    private var scope: CoroutineScope? = null

    /** Posledny posun za zivym v 90kHz tikoch (z timeshiftStatus). 0 = zive. */
    @Volatile var shiftTicks: Long = 0L
    // M412: namerane A/V odsadenie (mikrosekundy) z aktualneho streamu
    fun avOffsetUs(): Long? = client?.currentAvOffsetUs()
        private set

    /** Kompletny zoznam DVB titulkovych stop kanala zo subscriptionStart (esIndex + jazyk).
     *  Nezavisi od libVLC, takze je rovnaky na kazdom zariadeni. Nastavi sa po subscriptionStart. */
    @Volatile var subtitleStreams: List<sk.tvhclient.shared.htsp.TsMuxer.SubtitleInfo> = emptyList()
        private set

    /** Callback pre hotovú titulkovú stránku (vlastný renderer). page + cieľový čas v ms. */
    @Volatile var onSubtitlePage: ((sk.tvhclient.shared.htsp.DvbSubtitleDecoder.DecodedPage, Long) -> Unit)? = null

    /** Spusti feed pre kanal a vrati read FileDescriptor pre libVLC. */
    fun start(channelId: Long, scope: CoroutineScope): FileDescriptor {
        this.scope = scope
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        readPfd = read
        writePfd = write
        val os = ParcelFileDescriptor.AutoCloseOutputStream(write)
        out = os
        val c = HtspClient(server.host, server.htspPort, server.username, server.password)
        client = c
        job = scope.launch(Dispatchers.IO) {
            // M408: keepalive job — kazdych 10 s posle lahku HTSP poziadavku, aby
            // router/operator/NAT nezahodil necinne spojenie (pricina nahodnych
            // zamrznuti na wifi/mobile). Bezi paralelne, zrusi sa vo finally.
            val keepAlive = launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        delay(10_000)
                        c.keepAlive()
                    }
                } catch (_: Throwable) {}
            }
            try {
                c.connect()
                c.streamSubscribe(
                    channelId = channelId,
                    timeshiftPeriodSec = timeshiftPeriodSec,
                    onTs = { bytes -> os.write(bytes) },
                    onStatus = { shift, _ -> shiftTicks = shift },
                    onSubtitles = { subs -> subtitleStreams = subs },
                    onSubtitlePage = { page, targetMs -> onSubtitlePage?.invoke(page, targetMs) }
                )
            } catch (_: Throwable) {
                // zrusenie / zlomeny pipe / chyba spojenia
            } finally {
                keepAlive.cancel()
                c.close()
                try { os.close() } catch (_: Throwable) {}
            }
        }
        return read.fileDescriptor
    }

    /** Vyber titulkovej stopy posielanej do libVLC (esIndex; -1 = ziadna). */
    fun selectSubtitle(esIndex: Int) {
        client?.selectSubtitle(esIndex)
    }

    /** Pauza zivého prehravania (server drzi buffer). */
    fun pause() {
        val c = client ?: return
        scope?.launch { runCatching { c.setSpeed(0) } }
    }

    /** Obnovenie prehravania z miesta pauzy (timeshift). */
    fun resume() {
        val c = client ?: return
        scope?.launch { runCatching { c.setSpeed(100) } }
    }

    /** Relativny skok v bufferi (sekundy; zaporne = vzad). */
    fun skip(seconds: Int) {
        val c = client ?: return
        scope?.launch { runCatching { c.skip(seconds) } }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { out?.close() } catch (_: Throwable) {}
        try { readPfd?.close() } catch (_: Throwable) {}
        try { writePfd?.close() } catch (_: Throwable) {}
        out = null
        readPfd = null
        writePfd = null
        client = null
        scope = null
    }
}
