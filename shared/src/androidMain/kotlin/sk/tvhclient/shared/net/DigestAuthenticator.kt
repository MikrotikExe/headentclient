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
        if (response.request.header("Authorization")?.startsWith("Digest") == true) return null
        if (priorResponseCount(response) >= 3) return null

        val header = response.headers("WWW-Authenticate")
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
        sb.append("username=\"").append(username).append("\", ")
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
