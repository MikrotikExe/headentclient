package sk.tvhclient.android

import okhttp3.OkHttpClient
import okhttp3.Request
import sk.tvhclient.shared.model.TvhServer
import java.util.concurrent.TimeUnit

/**
 * M254 — automatic detection of whether the DVR (dvrfile) server requires HTTP Digest.
 * Sends a 1-byte GET without authentication and decides from the response:
 *   - 200/206 (no auth) or the challenge contains Basic -> do NOT use the feeder
 *     (libVLC handles it directly via creds in the URL, seek keeps working),
 *   - 401 and the challenge is Digest only -> use the feeder (libVLC cannot do digest via the URL).
 * Independent of what the user has selected in the app as the auth mode.
 */
object DvrAuthProbe {

    /** M394: cache the result per server — the auth mode does not change at runtime and each
     *  extra probe is a needless connection (accounts with a limit of 1 connection). */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Determine whether HttpTsFeeder is needed (digest-only). bareUrl is no longer probed —
     *  the auth challenge is server-wide, see needsFeederOrNull. */
    fun needsFeeder(server: TvhServer, bareUrl: String): Boolean =
        needsFeederOrNull(server, bareUrl) ?: false

    /** M390: null = the probe failed (timeout/network) — the result MUST NOT be cached,
     *  otherwise the direct path would be wrongly pinned even on a digest-only server.
     *  M394: the probe does NOT target the stream URL but /api/serverinfo — the same
     *  WWW-Authenticate challenge, but no subscription. A GET on the stream on an
     *  account with a limit of 1 connection briefly occupied the only slot (Tvheadend holds
     *  the subscription for a while after disconnect) and the server then refused the actual
     *  playback right after the probe — so radio did not start up at all. */
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
                .header("User-Agent", sk.tvhclient.shared.ClientIdent.userAgent)
                .build()
            ok.newCall(req).execute().use { resp ->
                if (resp.code != 401) return false.also { cache[server.id] = it }
                val challenge = resp.headers("WWW-Authenticate")
                    .joinToString(" ").lowercase()
                val hasDigest = challenge.contains("digest")
                val hasBasic = challenge.contains("basic")
                // feeder only if the server is purely digest (basic would be handled by libVLC via the URL)
                (hasDigest && !hasBasic).also { cache[server.id] = it }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
