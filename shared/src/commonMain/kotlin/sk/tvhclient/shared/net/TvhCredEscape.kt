package sk.tvhclient.shared.net

/**
 * M712: Tvheadend URL-decodes the user name and password it receives in HTTP Basic auth, and
 * the user name in Digest (http.c: http_deescape — "+" becomes a space, "%xx" is decoded).
 * A password with "+" or "%" therefore did not match and the server answered 403. Escaping
 * exactly those two characters makes the server's de-escape return the original text; other
 * characters pass through http_deescape unchanged.
 */
object TvhCredEscape {
    fun escape(s: String): String =
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) s
        else s.replace("%", "%25").replace("+", "%2B")

    /** "user:pass" for a Basic header, escaped for Tvheadend. */
    fun basicPair(user: String, pass: String): String = escape(user) + ":" + escape(pass)
}
