package sk.tvhclient.android

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.net.DigestAuthenticator
import sk.tvhclient.shared.stream.StreamUrlBuilder
import sk.tvhclient.shared.teletext.TeletextDecoder
import sk.tvhclient.shared.teletext.TeletextTsTap
import java.util.concurrent.TimeUnit

/**
 * M552 — teletext of the current live channel: one decoder, two data sources.
 *
 *  - HTSP: HtspTsFeeder feeds the PES payload of the TELETEXT track via [feedHtsp]
 *    (it flows the whole time the channel is playing — pages are collected even when teletext is not
 *    open, so after opening most of them are available immediately).
 *  - HTTP: the TS goes straight into libVLC, we cannot get at the data. So when teletext
 *    is opened we open a second connection to the same channel (the pass profile,
 *    so the server does not drop the teletext PID) and [TeletextTsTap] extracts only the
 *    teletext from it. The connection lives as long as teletext is open (+ a short run-out).
 *  - M713: HTTP live played through HttpTsFeeder — the teletext is read from the player's own
 *    stream ([attachFeeder]), no second connection. If the side branch is refused because of the
 *    account's connection limit, the player restarts the channel through the feeder.
 *
 * State for the UI: [availableState] (the channel has teletext), [pageVersion] (grows with
 * every received page — Compose redraws), [httpNoTeletext] (HTTP: the PMT was
 * read, the teletext PID is missing).
 */
class TeletextSession(private val ctx: Context) {

    val decoder = TeletextDecoder().also { it.now = { System.currentTimeMillis() } }

    val availableState = mutableStateOf(false)
    val pageVersion = mutableStateOf(0)
    val httpNoTeletext = mutableStateOf(false)

    private var httpJob: Job? = null
    private var mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        decoder.onPageUpdated = { _ ->
            // M567 (audit): the diagnostic log of the first page was removed — in normal operation
            // it only filled up the diagnostic record on every channel
            mainHandler.post { pageVersion.value++ ; if (!availableState.value) availableState.value = true }
        }
    }

    /** New channel / stop: discard the pages and the HTTP side branch. */
    fun reset() {
        stopHttp()
        detachFeeder()   // M713
        decoder.clear()
        availableState.value = false
        httpNoTeletext.value = false
        pageVersion.value++
    }

    // ---- HTSP ----

    fun setHtspAvailable(a: Boolean) { mainHandler.post { availableState.value = a } }

    fun feedHtsp(payload: ByteArray) { decoder.feedPes(payload) }

    // ---- HTTP: the player's own stream (M713) ----

    private var mainFeeder: HttpTsFeeder? = null
    /** 0 = PMT not read yet, 1 = the main stream carries teletext, -1 = unusable (no PMT / no teletext PID). */
    @Volatile private var mainState = 0
    /** The main stream's PMT was read and has no teletext PID (it may still be a profile that drops it). */
    @Volatile private var mainPmtWithoutTtx = false

    /** M713: the main stream stopped being usable for teletext (main thread) — the controller opens the side branch if teletext is open. */
    var onMainTapGaveUp: (() -> Unit)? = null
    /** M713: the side branch was refused because of the account's connection limit (main thread; the channel uuid). */
    var onSideBranchConnLimit: ((String) -> Unit)? = null

    /** The live stream plays through [HttpTsFeeder]: teletext is read from it (also while the overlay is closed, as with HTSP). */
    val mainTapUsable: Boolean get() = mainFeeder != null && mainState >= 0

    /**
     * M713: live HTTP played through the feeder — the teletext is read from the same data, so no
     * second connection is needed (an account with a connection limit of 1 refuses it with a late 405).
     */
    fun attachFeeder(feeder: HttpTsFeeder) {
        detachFeeder()
        val tap = TeletextTsTap(decoder)
        var total = 0L
        mainState = 0
        mainPmtWithoutTtx = false
        mainFeeder = feeder
        feeder.onData = { b, o, n ->
            tap.feed(b, o, n)
            total += n
            if (tap.pmtSeen) {
                if (tap.teletextPid >= 0) {
                    mainState = 1
                } else {
                    mainPmtWithoutTtx = true
                    giveUpMain(feeder, "main stream: no teletext PID in PMT")
                }
            } else if (total > 4L * 1024 * 1024) {
                giveUpMain(feeder, "main stream: no PMT in 4 MB")
            }
        }
    }

    private fun giveUpMain(feeder: HttpTsFeeder, why: String) {
        feeder.onData = null
        if (mainFeeder !== feeder || mainState < 0) return
        mainState = -1
        CrashLogger.report(ctx, "Teletext", why)
        mainHandler.post { if (mainFeeder === feeder) onMainTapGaveUp?.invoke() }
    }

    fun detachFeeder() {
        mainFeeder?.onData = null
        mainFeeder = null
        mainState = 0
        mainPmtWithoutTtx = false
    }

    // ---- HTTP: side branch ----

    val httpRunning: Boolean get() = httpJob?.isActive == true

    /**
     * Opens a side branch onto the channel's live HTTP stream and reads only teletext from it.
     * Call it when teletext is opened in HTTP mode; [stopHttp] on close.
     */
    fun startHttp(server: TvhServer, channelUuid: String, scope: CoroutineScope) {
        if (httpRunning) return
        httpNoTeletext.value = false
        val url = StreamUrlBuilder.liveUrlNoCreds(server, channelUuid, "pass", null, htsp = false)
        val hasCreds = server.username.isNotEmpty()
        val preemptiveBasic: String? = if (hasCreds && server.authMode != "digest") {
            "Basic " + Base64.encodeToString(
                sk.tvhclient.shared.net.TvhCredEscape.basicPair(server.username, server.password)   /* M712 */.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
        } else null
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val r = chain.request().newBuilder().apply {
                    header("User-Agent", sk.tvhclient.shared.ClientIdent.userAgent)
                    if (preemptiveBasic != null && sk.tvhclient.shared.net.AuthSchemeMemo.basicAllowed(server.authMode, chain.request().url))   // M715
                        header("Authorization", preemptiveBasic)
                }.build()
                chain.proceed(r)
            }
        if (hasCreds && server.authMode != "none") {
            builder.authenticator(DigestAuthenticator(server.username, server.password))
        }
        val ok = builder.build()
        val tap = TeletextTsTap(decoder)
        httpJob = scope.launch(Dispatchers.IO) {
            try {
                val sentAt = android.os.SystemClock.elapsedRealtime()
                ok.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    // M713: the account's connection limit (the player's stream holds the only slot)
                    if (HttpTsFeeder.isConnLimitResponse(resp.code, android.os.SystemClock.elapsedRealtime() - sentAt)) {
                        CrashLogger.report(ctx, "Teletext", "http tap refused: connection limit")
                        if (mainPmtWithoutTtx) mainHandler.post { httpNoTeletext.value = true }
                        else mainHandler.post { onSideBranchConnLimit?.invoke(channelUuid) }
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        CrashLogger.report(ctx, "Teletext", "http tap failed: HTTP ${resp.code}")
                        return@use
                    }
                    val src = resp.body?.byteStream() ?: return@use
                    val buf = ByteArray(32 * 1024)
                    var total = 0L
                    while (isActive) {
                        val n = src.read(buf)
                        if (n < 0) break
                        total += n
                        tap.feed(buf, 0, n)
                        // the PMT was read and teletext is not in it -> the channel does not broadcast teletext
                        if (tap.pmtSeen && tap.teletextPid < 0) {
                            mainHandler.post { httpNoTeletext.value = true }
                            CrashLogger.report(ctx, "Teletext", "http tap: no teletext PID in PMT")
                            break
                        }
                        // if we have been running for a long time without a PMT (not TS?), finish
                        if (!tap.pmtSeen && total > 4L * 1024 * 1024) {
                            CrashLogger.report(ctx, "Teletext", "http tap: no PMT in 4 MB, giving up")
                            break
                        }
                    }
                }
            } catch (e: Throwable) {
                if (isActive) CrashLogger.report(ctx, "Teletext", e)
            }
        }
    }

    fun stopHttp() {
        httpJob?.cancel()
        httpJob = null
    }
}
