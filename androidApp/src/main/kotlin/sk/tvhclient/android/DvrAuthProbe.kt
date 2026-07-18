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

    /** Zisti ci treba HttpTsFeeder (digest-only). bareUrl = dvrfile URL bez creds. */
    fun needsFeeder(server: TvhServer, bareUrl: String): Boolean =
        needsFeederOrNull(server, bareUrl) ?: false

    /** M390: null = sonda zlyhala (timeout/siet) — vysledok sa NEsmie cachovat,
     *  inak sa omylom zafixuje priama cesta aj na digest-only serveri. */
    fun needsFeederOrNull(server: TvhServer, bareUrl: String): Boolean? {
        if (server.username.isEmpty()) return false
        return try {
            val ok = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(bareUrl)
                .header("Range", "bytes=0-0")
                .header("User-Agent", "HeadentClient")
                .build()
            ok.newCall(req).execute().use { resp ->
                if (resp.code != 401) return false
                val challenge = resp.headers("WWW-Authenticate")
                    .joinToString(" ").lowercase()
                val hasDigest = challenge.contains("digest")
                val hasBasic = challenge.contains("basic")
                // feeder len ak je server cisto digest (basic by libVLC zvladol cez URL)
                hasDigest && !hasBasic
            }
        } catch (_: Throwable) {
            null
        }
    }
}
