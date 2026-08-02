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
    private var tsQueue: java.util.concurrent.LinkedBlockingQueue<ByteArray>? = null
    private var tsWriter: Thread? = null
    private var readPfd: ParcelFileDescriptor? = null
    private var writePfd: ParcelFileDescriptor? = null
    private var out: OutputStream? = null
    private var client: HtspClient? = null
    private var scope: CoroutineScope? = null

    /** Posledny posun za zivym v 90kHz tikoch (z timeshiftStatus). 0 = zive. */
    @Volatile var shiftTicks: Long = 0L
        private set


    /** Kompletny zoznam DVB titulkovych stop kanala zo subscriptionStart (esIndex + jazyk).
     *  Nezavisi od libVLC, takze je rovnaky na kazdom zariadeni. Nastavi sa po subscriptionStart. */
    @Volatile var subtitleStreams: List<sk.tvhclient.shared.htsp.TsMuxer.SubtitleInfo> = emptyList()
        private set

    /** Callback pre hotovú titulkovú stránku (vlastný renderer). page + cieľový čas v ms. */
    @Volatile var onSubtitlePage: ((sk.tvhclient.shared.htsp.DvbSubtitleDecoder.DecodedPage, Long) -> Unit)? = null

    /** Spusti feed pre kanal a vrati read FileDescriptor pre libVLC. */
    /**
     * M476: `profile` sa odovzdava do HTSP subscribe. HTSP profily podporuje od
     * v16 (getProfiles + pole `profile` v subscribe), appka ich vsak doteraz
     * posielala len na HTTP ceste — cez HTSP sa preto vzdy hralo so serverovou
     * predvolbou. Prazdny/`null` = nechaj rozhodnut server (povodne spravanie).
     */
    fun start(channelId: Long, scope: CoroutineScope, profile: String? = null): FileDescriptor {
        this.scope = scope
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        readPfd = read
        writePfd = write
        val os = ParcelFileDescriptor.AutoCloseOutputStream(write)
        out = os

        // M458: zapis do pipe bezi vo VLASTNOM vlakne s frontou.
        //
        // Linuxova pipe ma buffer 64 kB. Klucovy snimok 10-bit HEVC ma bezne
        // 70-100 kB, takze sa do pipe naraz nezmesti a `os.write(bytes)` sa
        // zablokuje, kym libVLC druhy koniec nevycita. Prijmacia HTSP slucka
        // dovtedy stoji — a obraz sekol RAZ ZA GOP, teda raz za sekundu.
        // Preto to postihovalo len HEVC (H.264 ma mensie klucove snimky),
        // preto bol zvuk plynuly (male pakety) a preto HTTP cesta problem nema
        // (libVLC tam cita priamo zo siete, ziadna pipe). V `top` sa to
        // neprejavilo — blokovany zapis nespotrebuva procesor.
        val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(256)
        tsQueue = queue
        val writer = Thread({
            try {
                while (true) {
                    val b = queue.take()
                    if (b.isEmpty()) break          // signal na ukoncenie
                    os.write(b)
                }
            } catch (_: InterruptedException) {
            } catch (_: Throwable) {
            }
        }, "HeadentClient:tsWriter")
        writer.isDaemon = true
        writer.priority = Thread.MAX_PRIORITY
        writer.start()
        tsWriter = writer
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
                    profile = profile?.takeIf { it.isNotBlank() },
                    timeshiftPeriodSec = timeshiftPeriodSec,
                    onTs = { bytes ->
                        // ak by sa fronta zaplnila (libVLC dlho necita), radsej
                        // pockame — je to ta ista spatna vazba ako predtym, len
                        // s 256-blokovou rezervou navyse
                        if (bytes.isNotEmpty()) queue.put(bytes)
                    },
                    onStatus = { shift, _ -> shiftTicks = shift },
                    onSubtitles = { subs -> subtitleStreams = subs },
                    onSubtitlePage = { page, targetMs -> onSubtitlePage?.invoke(page, targetMs) }
                )
            } catch (_: Throwable) {
                // zrusenie / zlomeny pipe / chyba spojenia
            } finally {
                keepAlive.cancel()
                c.close()
                runCatching { queue.put(ByteArray(0)) }   // M458: ukonci writer
                runCatching { writer.join(500) }
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
        // M458: prebud a ukonci zapisovacie vlakno, nech neostane visiet na fronte
        runCatching { tsQueue?.put(ByteArray(0)) }
        runCatching { tsWriter?.interrupt() }
        tsQueue = null
        tsWriter = null
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
