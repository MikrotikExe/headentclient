package sk.tvhclient.android

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import sk.tvhclient.shared.htsp.HtspSessions
import sk.tvhclient.shared.htsp.HtspSubscription
import sk.tvhclient.shared.model.TvhServer
import java.io.FileDescriptor
import java.io.OutputStream

/**
 * M162/M163 — bridges the HTSP live stream (remuxed to MPEG-TS) into libVLC through a local pipe.
 * `start` creates the pipe, launches a coroutine that writes TS into the write end and returns the read FileDescriptor
 * for Media(libVlc, fd). It subscribes with a timeshift buffer, so it can be paused via
 * subscriptionSpeed. On `stop`/closing the read end the write breaks and the loop ends.
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
    // M693: the stream is a subscription on the app's shared HTSP connection (like Kodi)
    @Volatile private var sub: HtspSubscription? = null
    @Volatile private var pendingSubEs = -1   // a subtitle choice made before the subscription existed
    private var scope: CoroutineScope? = null

    /** Last offset behind live in 90kHz ticks (from timeshiftStatus). 0 = live. */
    /**
     * M508-fix2: LENGTH of the timeshift buffer in 90 kHz ticks (end - start).
     *
     * This is the only reliable indication of how far back one can rewind.
     * An estimate from elapsed time does not hold when the server has timeshift set to "On-demand" —
     * the buffer then only starts forming once a client asks for it (pause/seek), so
     * a seek right after tuning a channel would go into the void and the picture freezes.
     * 0 = the server does not report the range (older TVH, radio) -> the fallback applies.
     */
    @Volatile var bufferTicks: Long = 0L
        private set


    /** Complete list of the channel's DVB subtitle tracks from subscriptionStart (esIndex + language).
     *  Independent of libVLC, so it is the same on every device. Set after subscriptionStart. */
    @Volatile var subtitleStreams: List<sk.tvhclient.shared.htsp.TsMuxer.SubtitleInfo> = emptyList()
        private set

    /** Callback for a finished subtitle page (custom renderer). page + target time in ms. */
    @Volatile var onSubtitlePage: ((sk.tvhclient.shared.htsp.DvbSubtitleDecoder.DecodedPage, Long) -> Unit)? = null

    /** M552: the channel broadcasts teletext (from subscriptionStart). */
    @Volatile var hasTeletext: Boolean = false
        private set
    /** M552: teletext PES payload for TeletextSession. */
    @Volatile var onTeletext: ((ByteArray) -> Unit)? = null
    @Volatile var onTeletextAvailable: ((Boolean) -> Unit)? = null

    /** Starts the feed for a channel and returns the read FileDescriptor for libVLC. */
    /**
     * M476: `profile` is passed into the HTSP subscribe. HTSP has supported profiles since
     * v16 (getProfiles + the `profile` field in subscribe), yet until now the app
     * only sent them on the HTTP path — over HTSP it therefore always played with the server
     * default. Empty/`null` = let the server decide (the original behaviour).
     */
    // M595: while a transfer is running the app opens no further HTSP connections (the server may
    // limit them to one per user and it would drop playback)
    private var streamMarked = false

    fun start(channelId: Long, scope: CoroutineScope, profile: String? = null): FileDescriptor {
        this.scope = scope
        if (!streamMarked) { streamMarked = true; sk.tvhclient.shared.htsp.HtspData.streamStarted() }   // M595
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        readPfd = read
        writePfd = write
        val os = ParcelFileDescriptor.AutoCloseOutputStream(write)
        out = os

        // M458: the write into the pipe runs on its OWN thread with a queue.
        //
        // A Linux pipe has a 64 kB buffer. A 10-bit HEVC key frame commonly has
        // 70-100 kB, so it does not fit into the pipe at once and `os.write(bytes)`
        // blocks until libVLC reads the other end out. The receiving HTSP loop
        // stands still meanwhile — and the picture stuttered ONCE PER GOP, i.e. once a second.
        // That is why it only affected HEVC (H.264 has smaller key frames),
        // why the audio was smooth (small packets) and why the HTTP path does not have the problem
        // (libVLC reads straight off the network there, no pipe). In `top` it did
        // not show up — a blocked write consumes no CPU.
        val queue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(256)
        tsQueue = queue
        val writer = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val b = queue.take()
                    if (b.isEmpty()) break          // signal to finish
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
        job = scope.launch(Dispatchers.IO) {
            // M408: the keepalive is now done by the shared session itself
            try {
                val session = HtspSessions.get(server)
                val subscription = session.subscribe(
                    channelId = channelId,
                    timeshiftPeriodSec = timeshiftPeriodSec,
                    profile = profile?.takeIf { it.isNotBlank() }
                )
                sub = subscription
                if (pendingSubEs >= 0) subscription.selectSubtitle(pendingSubEs)
                subscription.run(
                    onTs = { bytes ->
                        // should the queue fill up (libVLC not reading for a long time), we would
                        // rather wait — it is the same back pressure as before, just
                        // with an extra 256-block reserve
                        // M481: waits at most 10 s. Blocking is deliberate here (back
                        // pressure when libVLC is not reading), but once playback has stopped
                        // nobody drains the queue any more and the loop would hang forever.
                        if (bytes.isNotEmpty()) {
                            if (!queue.offer(bytes, 10, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw java.io.IOException("TS fronta sa neuvolnila")
                            }
                        }
                    },
                    onStatus = { _, _, startPts, endPts ->
                        if (endPts > startPts) bufferTicks = endPts - startPts
                    },
                    onSubtitles = { subs -> subtitleStreams = subs },
                    onSubtitlePage = { page, targetMs -> onSubtitlePage?.invoke(page, targetMs) },
                    onTeletextAvailable = { a -> hasTeletext = a; onTeletextAvailable?.invoke(a) },   // M552
                    onTeletext = { es -> onTeletext?.invoke(es) }
                )
            } catch (e: Throwable) {
                // cancellation / broken pipe / connection error
                // M692: the stream itself was refused because of the account's connection limit
                // (usually another device playing on the same account) — into the diagnostic log
                if (e is sk.tvhclient.shared.htsp.HtspConnLimitException)
                    sk.tvhclient.shared.htsp.HtspData.reportConnLimit(server)
            } finally {
                sub = null
                runCatching { queue.offer(ByteArray(0)) }   // M481
                runCatching { writer.interrupt() }
                runCatching { writer.join(500) }
                try { os.close() } catch (_: Throwable) {}
            }
        }
        return read.fileDescriptor
    }

    /** Selection of the subtitle track sent into libVLC (esIndex; -1 = none). */
    fun selectSubtitle(esIndex: Int) {
        pendingSubEs = esIndex
        sub?.selectSubtitle(esIndex)
    }

    /** Pause of live playback (the server holds the buffer). */
    fun pause() {
        val c = sub ?: return
        scope?.launch { runCatching { c.setSpeed(0) } }
    }

    /** Resume playback from the point of the pause (timeshift). */
    fun resume() {
        val c = sub ?: return
        scope?.launch { runCatching { c.setSpeed(100) } }
    }

    /** Relative seek within the buffer (seconds; negative = backwards). */
    fun skip(seconds: Int) {
        val c = sub ?: return
        scope?.launch { runCatching { c.skip(seconds) } }
    }

    fun stop() {
        if (streamMarked) { streamMarked = false; sk.tvhclient.shared.htsp.HtspData.streamStopped() }   // M595
        job?.cancel()
        job = null
        // M481: terminate the writer thread WITHOUT blocking.
        //
        // Originally there was `tsQueue?.put(ByteArray(0))` here — put() on a full queue
        // WAITS. stop() meanwhile runs on the main thread (channel switch, opening
        // a recording, end of playback), so when the queue was full and the writer
        // thread hung on a full pipe, the main thread got stuck there and the app stopped
        // responding. offer() does not wait for room; the wake-up is handled by the interrupt.
        runCatching { tsQueue?.offer(ByteArray(0)) }
        runCatching { tsWriter?.interrupt() }
        tsQueue = null
        tsWriter = null
        try { out?.close() } catch (_: Throwable) {}
        try { readPfd?.close() } catch (_: Throwable) {}
        try { writePfd?.close() } catch (_: Throwable) {}
        out = null
        readPfd = null
        writePfd = null
        sub = null
        scope = null
    }
}
