package sk.tvhclient.shared.net

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import kotlin.random.Random

/**
 * OkHttp Authenticator for HTTP Digest (RFC 2617/7616). Covers all the hash
 * types Tvheadend offers: MD5, SHA-256, SHA-512-256 (including the -sess
 * variants), qop=auth. It is used uniformly for the API (channel/EPG/DVR list
 * via the Ktor OkHttp engine), for picons and for playback (DVR/live via the feeder).
 *
 * SHA-512/256 has been available in JCA since Android 8 (API 26); on older devices
 * this one hash type will not work (MD5/SHA-256 always work).
 */
class DigestAuthenticator(
    private val username: String,
    private val password: String,
    /**
     * M712: answer only challenges from this host (and port, when > 0). Picons can point to
     * external logo servers (icon_public_url with the image cache off, imagecache.c) — the
     * Tvheadend credentials must never be sent there. null = no restriction (old behaviour).
     */
    private val onlyHost: String? = null,
    private val onlyPort: Int = -1,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        return try {
            buildAuth(response)
        } catch (_: Throwable) {
            // never crash the app over auth/hashing — a silent failure is preferable
            null
        }
    }

    private fun buildAuth(response: Response): Request? {
        val u = response.request.url
        if (onlyHost != null && (!u.host.equals(onlyHost, ignoreCase = true) || (onlyPort > 0 && u.port != onlyPort))) return null   // M712
        if (response.request.header("Authorization")?.startsWith("Digest") == true) return null
        if (priorResponseCount(response) >= 3) return null

        val challenges = response.headers("WWW-Authenticate")
        val sentBasic = response.request.header("Authorization")?.startsWith("Basic") == true
        val offersBasic = challenges.any { it.trim().startsWith("Basic", ignoreCase = true) }
        val offersDigest = challenges.any { it.trim().startsWith("Digest", ignoreCase = true) }
        // M715: Tvheadend offers only "Digest" both in the digest and the "both" mode (http.c), so the
        // challenge alone cannot tell them apart. Only a REFUSED Basic marks the server as digest-only
        // (the "both" mode accepts Basic and never gets here).
        if (sentBasic && offersDigest && !offersBasic) AuthSchemeMemo.markDigestOnly(u)
        // M715: the server was switched to plain meanwhile — Basic again (the memo is dropped)
        if (!offersDigest && offersBasic && AuthSchemeMemo.isDigestOnly(u) &&
            response.request.header("Authorization") == null) {
            AuthSchemeMemo.unmark(u)
            val basic = "Basic " + android.util.Base64.encodeToString(
                TvhCredEscape.basicPair(username, password).toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            return response.request.newBuilder().header("Authorization", basic).build()
        }
        val header = challenges
            .firstOrNull { it.trim().startsWith("Digest", ignoreCase = true) } ?: return null

        val p = parseChallenge(header)
        val realm = p["realm"] ?: return null
        val nonce = p["nonce"] ?: return null
        val opaque = p["opaque"]
        val algorithm = (p["algorithm"] ?: "MD5").uppercase()
        val sess = algorithm.endsWith("-SESS")
        val qopRaw = p["qop"]
        val qop = when {
            qopRaw == null -> null
            qopRaw.split(",").any { it.trim().equals("auth", true) } -> "auth"
            else -> null
        }

        val req = response.request
        val method = req.method
        val uri = req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")
        val cnonce = randomHex(16)
        val nc = "00000001"

        val a1base = h(algorithm, "$username:$realm:$password")
        val ha1 = if (sess) h(algorithm, "$a1base:$nonce:$cnonce") else a1base
        val ha2 = h(algorithm, "$method:$uri")

        val resp = if (qop != null) {
            h(algorithm, "$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            h(algorithm, "$ha1:$nonce:$ha2")
        }

        val sb = StringBuilder("Digest ")
        // M712: Tvheadend de-escapes the user name from the header (http_deescape) but hashes
        // with the real one — the header carries the escaped form, HA1 the real name
        sb.append("username=\"").append(TvhCredEscape.escape(username)).append("\", ")
        sb.append("realm=\"").append(realm).append("\", ")
        sb.append("nonce=\"").append(nonce).append("\", ")
        sb.append("uri=\"").append(uri).append("\", ")
        if (qop != null) {
            sb.append("qop=").append(qop).append(", ")
            sb.append("nc=").append(nc).append(", ")
            sb.append("cnonce=\"").append(cnonce).append("\", ")
        }
        sb.append("response=\"").append(resp).append("\"")
        p["algorithm"]?.let { sb.append(", algorithm=").append(it) }
        if (opaque != null) sb.append(", opaque=\"").append(opaque).append("\"")

        return req.newBuilder().header("Authorization", sb.toString()).build()
    }

    private fun priorResponseCount(response: Response): Int {
        var n = 1
        var r = response.priorResponse
        while (r != null) { n++; r = r.priorResponse }
        return n
    }

    /** Hash according to the challenge's algorithm. Returns hex. */
    private fun h(algorithm: String, data: String): String {
        val bytes = data.toByteArray(Charsets.UTF_8)
        return when {
            algorithm.startsWith("SHA-512-256") -> Sha512_256.hex(bytes)
            algorithm.startsWith("SHA-256") ->
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            else ->
                MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }

    private fun randomHex(bytes: Int): String =
        (0 until bytes).joinToString("") { "%02x".format(Random.nextInt(0, 256)) }

    private fun parseChallenge(header: String): Map<String, String> {
        val body = header.trim().removePrefix("Digest").removePrefix("DIGEST").trim()
        val map = HashMap<String, String>()
        var i = 0
        val n = body.length
        while (i < n) {
            while (i < n && (body[i] == ' ' || body[i] == ',')) i++
            val keyStart = i
            while (i < n && body[i] != '=') i++
            if (i >= n) break
            val key = body.substring(keyStart, i).trim().lowercase()
            i++
            val value: String
            if (i < n && body[i] == '"') {
                i++
                val vs = i
                while (i < n && body[i] != '"') i++
                value = body.substring(vs, i)
                if (i < n) i++
            } else {
                val vs = i
                while (i < n && body[i] != ',') i++
                value = body.substring(vs, i).trim()
            }
            if (key.isNotEmpty()) map[key] = value
        }
        return map
    }
}
