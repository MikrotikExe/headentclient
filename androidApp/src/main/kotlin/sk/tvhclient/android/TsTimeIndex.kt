package sk.tvhclient.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * M727 (issue #28): the time <-> byte map of a FINISHED MPEG-TS recording played over HTTP.
 *
 * libVLC's TS demuxer cannot start an HTTP stream at a time (M706), so a TS recording is started
 * at a byte position. Until now that position was time / duration × size: with a variable bitrate
 * it landed up to a minute off, the seek bar showed the target time instead of where the stream
 * really was, and the duration itself came from the planned start/stop (a 4 h slot holding a 2 h
 * recording showed 4 h). Here the clock of the file itself is read: the PCR (the 90 kHz program
 * clock every DVB transport stream carries, ≥ 10× a second) at the start and at the end gives the
 * real duration, and a seek target is found by interpolation on a few short Range reads (each
 * ~256 KB), corrected until the PCR at the landing point is within [TOLERANCE_MS] of the target.
 * The seek then starts at that byte and the bar is seeded with the time actually found there.
 *
 * [read] = a Range read of the recording with the server's own authentication (DvrProxy.rangeReader),
 * returns the bytes and the total file size. Any failure (network, the account's connection limit,
 * a clock discontinuity in the file) makes [locate] return null and the caller falls back to the
 * old estimate — never worse than before.
 */
internal class TsTimeIndex(private val read: suspend (from: Long, len: Int) -> RangeRead?) {

    class RangeRead(val bytes: ByteArray, val length: Int, val total: Long)

    /** A point of the map: [byte] in the file at file time [ms]. */
    private class Point(val byte: Long, val ms: Long)

    private val ready = CompletableDeferred<Boolean>()
    @Volatile private var disabled = false
    private var pcrPid = -1
    private var firstPcr = -1L
    @Volatile var sizeBytes = 0L
        private set
    /** The real duration of the file from its own clock (0 = unknown). */
    @Volatile var durationMs = 0L
        private set
    private val points = ArrayList<Point>()

    /** Reads the start and the end of the file once. Call off the main thread; true = usable. */
    suspend fun init(): Boolean {
        if (ready.isCompleted) return ready.await()
        val ok = runCatching { doInit() }.getOrDefault(false)
        if (!ok) disabled = true
        ready.complete(ok)
        return ok
    }

    private suspend fun doInit(): Boolean {
        val head = read(0, PROBE_BYTES) ?: return false
        if (head.total <= 0) return false
        sizeBytes = head.total
        val first = scanPcr(head.bytes, head.length, -1, last = false) ?: return false
        pcrPid = first.first
        firstPcr = first.second
        val tailFrom = (sizeBytes - TAIL_BYTES).coerceAtLeast(0L)
        val tail = read(tailFrom, TAIL_BYTES) ?: return false
        val last = scanPcr(tail.bytes, tail.length, pcrPid, last = true) ?: return false
        val dur = pcrDiffMs(firstPcr, last.second)
        // a negative, zero or absurd span (a clock reset inside the file) -> not usable
        if (dur <= 1000L || dur > 48L * 3600_000L) return false
        durationMs = dur
        synchronized(points) {
            points.add(Point(0L, 0L))
            points.add(Point(sizeBytes, dur))
        }
        return true
    }

    /**
     * The byte to start at for file time [targetMs] and the file time actually found there, or
     * null when the map is not usable (the caller keeps the old estimate). Waits for [init] at most
     * [waitMs]. Call off the main thread.
     */
    suspend fun locate(targetMs: Long, waitMs: Long = 1500L): Pair<Long, Long>? {
        if (disabled) return null
        val ok = withTimeoutOrNull(waitMs) { ready.await() } ?: return null
        if (!ok || disabled) return null
        val dur = durationMs
        if (targetMs <= 0L) return 0L to 0L
        val target = targetMs.coerceAtMost(dur)
        var best: Point? = null
        repeat(MAX_PROBES) {
            val (lo, hi) = bracket(target)
            if (hi.ms <= lo.ms || hi.byte <= lo.byte) return best?.let { it.byte to it.ms }
            val frac = (target - lo.ms).toDouble() / (hi.ms - lo.ms)
            var guess = lo.byte + (frac * (hi.byte - lo.byte)).toLong()
            guess -= guess % TS_PACKET
            if (guess <= lo.byte || guess >= hi.byte) return (best ?: lo).let { it.byte to it.ms }
            val r = runCatching { read(guess, PROBE_BYTES) }.getOrNull() ?: return best?.let { it.byte to it.ms }
            val pcr = scanPcr(r.bytes, r.length, pcrPid, last = false) ?: return best?.let { it.byte to it.ms }
            val ms = pcrDiffMs(firstPcr, pcr.second)
            // outside its neighbours = the clock jumps inside the file: do not trust the map
            if (ms < lo.ms - 2000L || ms > hi.ms + 2000L) { disabled = true; return null }
            val p = Point(guess, ms)
            synchronized(points) { points.add(p); points.sortBy { it.byte } }
            // land a little BEFORE the target rather than after (the viewer would miss a few seconds)
            val b = best
            if (ms <= target && (b == null || ms > b.ms)) best = p
            if (kotlin.math.abs(ms - target) <= TOLERANCE_MS) return p.byte to p.ms
        }
        return best?.let { it.byte to it.ms }
    }

    /** The known points just below and just above [target]. */
    private fun bracket(target: Long): Pair<Point, Point> = synchronized(points) {
        var lo = points.first(); var hi = points.last()
        for (p in points) {
            if (p.ms <= target && p.ms >= lo.ms) lo = p
            if (p.ms > target && p.ms <= hi.ms) hi = p
        }
        lo to hi
    }

    /** Marks the map unusable (e.g. the server refused a probe because of the connection limit). */
    fun disable() { disabled = true; if (!ready.isCompleted) ready.complete(false) }

    companion object {
        const val TS_PACKET = 188
        private const val PROBE_BYTES = 256 * 1024
        private const val TAIL_BYTES = 512 * 1024
        private const val MAX_PROBES = 5
        private const val TOLERANCE_MS = 1500L
        private const val PCR_WRAP = 1L shl 33

        /** File time between two 90 kHz PCR bases, across the 33-bit wrap. */
        fun pcrDiffMs(from: Long, to: Long): Long = ((to - from + PCR_WRAP) % PCR_WRAP) / 90L

        /**
         * The first (or [last]) PCR in [buf]: (pid, 33-bit base). [pid] -1 = any PID carrying a PCR.
         * The buffer may start in the middle of a packet: it syncs on three 0x47 bytes 188 apart.
         */
        fun scanPcr(buf: ByteArray, len: Int, pid: Int, last: Boolean): Pair<Int, Long>? {
            fun u(i: Int) = buf[i].toInt() and 0xFF
            var start = -1
            var i = 0
            while (i + 2 * TS_PACKET < len) {
                if (u(i) == 0x47 && u(i + TS_PACKET) == 0x47 && u(i + 2 * TS_PACKET) == 0x47) { start = i; break }
                i++
            }
            if (start < 0) return null
            var found: Pair<Int, Long>? = null
            var p = start
            while (p + TS_PACKET <= len) {
                if (u(p) != 0x47) {   // lost sync (a damaged packet): resync from here
                    var q = p + 1
                    while (q + 2 * TS_PACKET < len && !(u(q) == 0x47 && u(q + TS_PACKET) == 0x47)) q++
                    if (q + 2 * TS_PACKET >= len) break
                    p = q
                    continue
                }
                val pktPid = ((u(p + 1) and 0x1F) shl 8) or u(p + 2)
                val afc = (u(p + 3) shr 4) and 0x3
                if ((afc == 2 || afc == 3) && (pid < 0 || pktPid == pid)) {
                    val afLen = u(p + 4)
                    if (afLen >= 7 && (u(p + 5) and 0x10) != 0) {
                        val base = (u(p + 6).toLong() shl 25) or (u(p + 7).toLong() shl 17) or
                            (u(p + 8).toLong() shl 9) or (u(p + 9).toLong() shl 1) or (u(p + 10).toLong() shr 7)
                        found = pktPid to base
                        if (!last) return found
                    }
                }
                p += TS_PACKET
            }
            return found
        }
    }
}
