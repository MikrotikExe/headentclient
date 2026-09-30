package sk.tvhclient.shared.net

import okhttp3.HttpUrl

/**
 * M715: servers that refused a Basic request with a Digest challenge (Tvheadend "Digest"
 * authentication; the "both" mode accepts Basic, http.c). In the "auto" mode the app
 * sends Basic pre-emptively (saves a round trip on plain/both servers); a digest-only server
 * rejects it, so the password travelled in base64 on every request for nothing. Once a server
 * has shown it is digest-only, auto mode stops sending Basic to it (for the rest of the process).
 */
object AuthSchemeMemo {
    private val digestOnly = HashSet<String>()

    private fun key(u: HttpUrl) = u.host.lowercase() + ":" + u.port

    fun markDigestOnly(u: HttpUrl) { synchronized(digestOnly) { digestOnly.add(key(u)) } }

    fun unmark(u: HttpUrl) { synchronized(digestOnly) { digestOnly.remove(key(u)) } }

    fun isDigestOnly(u: HttpUrl): Boolean = synchronized(digestOnly) { key(u) in digestOnly }

    /** Pre-emptive Basic to [u]: always in the explicit "basic" mode, in "auto" unless the server is digest-only. */
    fun basicAllowed(authMode: String, u: HttpUrl): Boolean = authMode == "basic" || !isDigestOnly(u)
}
