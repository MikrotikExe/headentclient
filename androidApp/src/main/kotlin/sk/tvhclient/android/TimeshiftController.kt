package sk.tvhclient.android

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M262 / M492 / M508 / M647: HTSP timeshift (pauza a skoky za živým), vyclenené z PlayerActivity.
 *
 * Drží posun za živým ([offsetMs], rastie počas pauzy 1 s/s cez ticker), či je timeshift
 * „zapnutý" ([engaged], prvá pauza) a nazbieraný skok, ktorý sa serveru pošle až po
 * ustálení tukania (350 ms) jedným relatívnym subscriptionSkip — na živé sa vracia
 * skokom dopredu, nie subscriptionLive ani reštartom (zostáva ten istý buffer).
 * Hĺbka buffera: skutočná zo servera (timeshiftStatus end−start, M508-fix2), inak wall-clock.
 */
internal class TimeshiftController(
    private val scope: CoroutineScope,
    private val feeder: () -> HtspTsFeeder?,
    private val onResumePlayback: () -> Unit,
    private val onSeekSpinner: () -> Unit
) {
    val offsetMs = mutableStateOf(0L)
    val engaged = mutableStateOf(false)

    private var accumMs = 0L
    private var pauseStartedAt = 0L
    private var startedAt = 0L
    private var pendingSkipMs = 0L
    private var skipFlushJob: Job? = null
    private var tickerJob: Job? = null

    /** Prvá pauza „zapne" timeshift; vráti true, ak to bola prvá (volajúci re-ukotví fokus). */
    fun onPaused(): Boolean {
        // prva pauza je pri „On-demand" timeshifte moment, kedy server zacne buffer naozaj tvorit
        if (startedAt <= 0L) startedAt = System.currentTimeMillis()
        val first = !engaged.value
        engaged.value = true
        pauseStartedAt = System.currentTimeMillis()
        startTicker()
        return first
    }

    fun onResumed() {
        if (pauseStartedAt > 0L) {
            accumMs += System.currentTimeMillis() - pauseStartedAt
            pauseStartedAt = 0L
        }
        stopTicker()
        offsetMs.value = accumMs
    }

    /** Pocas pauzy rastie posun za zivym (1 s/s); aktualizuje ukazovatel kazdu sekundu. */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                val extra = if (pauseStartedAt > 0L) System.currentTimeMillis() - pauseStartedAt else 0L
                offsetMs.value = accumMs + extra
                delay(1000)
            }
        }
    }

    fun stopTicker() { tickerJob?.cancel(); tickerJob = null }

    /** Doruč nazbieraný skok hneď (pred pauzou/play, nech je server konzistentný). */
    fun flushNow() { skipFlushJob?.cancel(); flushSkip() }

    /** Novy zivy zaciatok (cerstva subscription = na zivo) -> vynuluj timeshift. */
    fun reset() {
        stopTicker()
        skipFlushJob?.cancel(); skipFlushJob = null
        pendingSkipMs = 0L
        accumMs = 0L
        pauseStartedAt = 0L
        startedAt = 0L   // novy kanal = novy buffer od nuly
        engaged.value = false
        offsetMs.value = 0L
    }

    /**
     * Kolko sa da pretocit dozadu. Prednost ma SKUTOCNA dlzka buffera hlasena serverom
     * (M508-fix2); wall-clock je zaloha pre starsi TVH / radio (timeshift info neposiela).
     */
    fun maxRewindMs(): Long {
        val fromServer = (feeder()?.bufferTicks ?: 0L) / 90L   // 90 kHz -> ms
        if (fromServer > 0L) return fromServer.coerceAtMost(3600_000L)
        if (startedAt <= 0L) return 0L
        return (System.currentTimeMillis() - startedAt).coerceAtMost(3600_000L)
    }

    /** Relativny skok v timeshifte (sekundy; zaporne = vzad). Aktualizuje aj ukazovatel. */
    fun skip(seconds: Int) {
        // ak je pauza, po skoku spusti prehravanie (nech vidno vysledok skoku)
        if (pauseStartedAt > 0L) {
            accumMs += System.currentTimeMillis() - pauseStartedAt
            pauseStartedAt = 0L
            stopTicker()
            onResumePlayback()
        }
        // cielova pozicia za zivym, orezana na <0 .. hlbka bufferu>
        val target = (accumMs - seconds.toLong() * 1000L).coerceIn(0L, maxRewindMs())
        val deltaMs = target - accumMs
        if (deltaMs == 0L) return                   // niet kam (zaciatok bufferu alebo zive)
        accumMs = target
        offsetMs.value = accumMs
        // ukazovatel reaguje hned, ale realny skok posli az ked prestane tukanie —
        // viac skokov za sebou inak nuti libVLC stale resynchronizovat (trha to)
        pendingSkipMs += deltaMs
        skipFlushJob?.cancel()
        skipFlushJob = scope.launch {
            delay(350)
            flushSkip()
        }
    }

    private fun flushSkip() {
        val net = pendingSkipMs
        pendingSkipMs = 0L
        if (net != 0L) {
            feeder()?.skip((-net / 1000L).toInt())   // dozadu => zaporne, dopredu => kladne
            onSeekSpinner()
        }
    }

    fun destroy() { stopTicker(); skipFlushJob?.cancel() }
}
