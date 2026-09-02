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
 * M552 — teletext aktuálneho živého kanála: jeden dekodér, dva zdroje dát.
 *
 *  - HTSP: HtspTsFeeder podáva PES payload stopy TELETEXT cez [feedHtsp]
 *    (tečie stále, kým hrá kanál — stránky sa zbierajú aj keď teletext nie je
 *    otvorený, takže po otvorení je väčšina hneď k dispozícii).
 *  - HTTP: TS ide priamo do libVLC, k dátam sa nedostaneme. Pri otvorení
 *    teletextu preto otvoríme druhé spojenie na ten istý kanál (profil pass,
 *    aby server teletextový PID nezahodil) a [TeletextTsTap] z neho vyberá len
 *    teletext. Spojenie žije, kým je teletext otvorený (+ krátky dobeh).
 *
 * Stav pre UI: [availableState] (kanál teletext má), [pageVersion] (rastie pri
 * každej prijatej stránke — Compose sa prekreslí), [httpNoTeletext] (HTTP: PMT
 * prečítaná, teletextový PID chýba).
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
            // M567 (audit): diagnosticky log prvej stranky odstraneny — v beznej prevadzke
            // len zaplnal diagnosticky zaznam pri kazdom kanali
            mainHandler.post { pageVersion.value++ ; if (!availableState.value) availableState.value = true }
        }
    }

    /** Nový kanál / zastavenie: zahoď stránky aj HTTP odbočku. */
    fun reset() {
        stopHttp()
        decoder.clear()
        availableState.value = false
        httpNoTeletext.value = false
        pageVersion.value++
    }

    // ---- HTSP ----

    fun setHtspAvailable(a: Boolean) { mainHandler.post { availableState.value = a } }

    fun feedHtsp(payload: ByteArray) { decoder.feedPes(payload) }

    // ---- HTTP ----

    val httpRunning: Boolean get() = httpJob?.isActive == true

    /**
     * Otvorí odbočku na živý HTTP stream kanála a číta z nej len teletext.
     * Volať pri otvorení teletextu v HTTP režime; [stopHttp] pri zatvorení.
     */
    fun startHttp(server: TvhServer, channelUuid: String, scope: CoroutineScope) {
        if (httpRunning) return
        httpNoTeletext.value = false
        val url = StreamUrlBuilder.liveUrlNoCreds(server, channelUuid, "pass", null, htsp = false)
        val hasCreds = server.username.isNotEmpty()
        val preemptiveBasic: String? = if (hasCreds && server.authMode != "digest") {
            "Basic " + Base64.encodeToString(
                "${server.username}:${server.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
        } else null
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val r = chain.request().newBuilder().apply {
                    header("User-Agent", sk.tvhclient.shared.ClientIdent.userAgent)
                    if (preemptiveBasic != null) header("Authorization", preemptiveBasic)
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
                ok.newCall(Request.Builder().url(url).build()).execute().use { resp ->
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
                        // PMT prečítaná a teletext v nej nie je -> kanál teletext nevysiela
                        if (tap.pmtSeen && tap.teletextPid < 0) {
                            mainHandler.post { httpNoTeletext.value = true }
                            CrashLogger.report(ctx, "Teletext", "http tap: no teletext PID in PMT")
                            break
                        }
                        // pokiaľ bežíme dlho bez PMT (nie TS?), skonči
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
