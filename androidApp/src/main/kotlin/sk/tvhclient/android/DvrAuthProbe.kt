package sk.tvhclient.android

import okhttp3.OkHttpClient
import okhttp3.Request
import sk.tvhclient.shared.model.TvhServer
import java.util.concurrent.TimeUnit

/**
 * M254 — automaticka detekcia, ci DVR (dvrfile) server vyzaduje HTTP Digest.
 * Posle 1-bajtovy GET bez autentifikacie a podla odpovede rozhodne:
 *   - 200/206 (ziadna auth) alebo challenge obsahuje Basic -> NEpouzivaj feeder
 *     (libVLC zvladne priamo cez creds v URL, seek ostava funkcny),
 *   - 401 a challenge je len Digest -> pouzij feeder (libVLC digest cez URL nevie).
 * Nezavisle od toho, co ma pouzivatel v appke zvolene ako auth rezim.
 */
object DvrAuthProbe {

    /** M394: cache vysledku per server — auth rezim sa pocas behu nemeni a kazda
     *  sonda navyse je zbytocne spojenie (ucty s limitom 1 pripojenia). */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Zisti ci treba HttpTsFeeder (digest-only). bareUrl sa uz nesonduje —
     *  auth vyzva je serverova, pozri needsFeederOrNull. */
    fun needsFeeder(server: TvhServer, bareUrl: String): Boolean =
        needsFeederOrNull(server, bareUrl) ?: false

    /** M390: null = sonda zlyhala (timeout/siet) — vysledok sa NEsmie cachovat,
     *  inak sa omylom zafixuje priama cesta aj na digest-only serveri.
     *  M394: sonda NEmieri na stream URL, ale na /api/serverinfo — rovnaka
     *  WWW-Authenticate vyzva, ale ziadna subscription. GET na stream totiz na
     *  ucte s limitom 1 pripojenia na okamih obsadil jediny slot (Tvheadend drzi
     *  subscription chvilu po odpojeni) a skutocne prehravanie hned za sondou
     *  server odmietol — radio tak nenabehlo vobec. */
    fun needsFeederOrNull(server: TvhServer, bareUrl: String): Boolean? {
        if (server.username.isEmpty()) return false
        cache[server.id]?.let { return it }
        val probeUrl = server.baseUrl.trimEnd('/') + "/api/serverinfo"
        return try {
            val ok = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(probeUrl)
                .header("User-Agent", "HeadentClient")
                .build()
            ok.newCall(req).execute().use { resp ->
                if (resp.code != 401) return false.also { cache[server.id] = it }
                val challenge = resp.headers("WWW-Authenticate")
                    .joinToString(" ").lowercase()
                val hasDigest = challenge.contains("digest")
                val hasBasic = challenge.contains("basic")
                // feeder len ak je server cisto digest (basic by libVLC zvladol cez URL)
                (hasDigest && !hasBasic).also { cache[server.id] = it }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
