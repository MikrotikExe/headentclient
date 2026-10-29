package sk.tvhclient.shared.stream

import sk.tvhclient.shared.model.TvhServer

/**
 * Building the stream URL for both live and DVR. Taken from the plugin (_stream_urls.py):
 *  - live: stream/channel/{uuid}?profile=pass (PREFER_CHANNEL_STREAM)
 *  - DVR:  dvrfile/{id}
 *  - credentials inserted straight into the URL (user:pass@host) — works for plain
 *    auth; with digest-only the player handles them through an auth handler (M3).
 *  - optional title param.
 *
 * Note: inserting the credentials into the URL is necessary because the player (ExoPlayer/
 * VLCKit) opens the stream on its own, outside the Ktor client and its auth.
 */
object StreamUrlBuilder {

    // M678: no %s and no String.format — that one is JVM only, in commonMain Kotlin/Native does not know it
    // (the iOS target compilations were failing with "Unresolved reference 'format'").
    private const val STREAM_CH = "stream/channel/"
    private const val STREAM_CHID = "stream/channelid/"

    /**
     * RFC 3986 percent-encoding of the UTF-8 bytes. M737 (audit K5): it encoded the UTF-16 code of a
     * character ("€" -> "%20AC" = " AC", "§" -> "%A7" = a Latin-1 byte) and let non-ASCII letters
     * through raw, so a password with such characters failed (401) on direct HTTP playback and
     * recordings while the API (header auth) worked.
     */
    internal fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '_' || c == '.' || c == '~') append(c)
            else { append('%'); append(HEX[v ushr 4]); append(HEX[v and 0x0F]) }
        }
    }
    private const val HEX = "0123456789ABCDEF"

    private fun withCreds(server: TvhServer, fullUrl: String): String {
        if (server.username.isEmpty()) return fullUrl
        val scheme = if (server.useHttps) "https://" else "http://"
        if (!fullUrl.startsWith(scheme)) return fullUrl
        val rest = fullUrl.substring(scheme.length)
        // M712: libVLC URL-decodes the userinfo and sends it as Basic; Tvheadend URL-decodes it
        // once more (http_deescape) — escape "+"/"%" first so both decodings end at the original
        val creds = encode(sk.tvhclient.shared.net.TvhCredEscape.escape(server.username)) + ":" +
            encode(sk.tvhclient.shared.net.TvhCredEscape.escape(server.password)) + "@"
        return scheme + creds + rest
    }

    private fun build(
        server: TvhServer,
        endpoint: String,
        profile: String?,
        title: String?
    ): String {
        var url = server.baseUrl.trimEnd('/') + "/" + endpoint
        val q = mutableListOf<String>()
        if (!profile.isNullOrBlank()) q.add("profile=" + encode(profile))
        if (!title.isNullOrBlank()) q.add("title=" + encode(title))
        if (q.isNotEmpty()) url += "?" + q.joinToString("&")
        return withCreds(server, url)
    }

    fun liveUrl(
        server: TvhServer,
        channelUuid: String,
        profile: String = "pass",
        channelTitle: String? = null,
        htsp: Boolean = false
    ): String {
        val ep = (if (htsp) STREAM_CHID else STREAM_CH) + channelUuid
        return build(server, ep, profile, channelTitle)
    }

    /**
     * Live URL without creds — for ExoPlayer/VLCKit where auth goes through a header.
     */
    fun liveUrlNoCreds(
        server: TvhServer,
        channelUuid: String,
        profile: String = "pass",
        channelTitle: String? = null,
        htsp: Boolean = false
    ): String {
        val ep = (if (htsp) STREAM_CHID else STREAM_CH) + channelUuid
        var url = server.baseUrl.trimEnd('/') + "/" + ep
        val q = mutableListOf<String>()
        if (profile.isNotBlank()) q.add("profile=" + encode(profile))
        if (!channelTitle.isNullOrBlank()) q.add("title=" + encode(channelTitle))
        if (q.isNotEmpty()) url += "?" + q.joinToString("&")
        return url
    }

    fun dvrUrl(server: TvhServer, dvrFileId: String): String =
        withCreds(server, server.baseUrl.trimEnd('/') + "/dvrfile/" + dvrFileId)

    /**
     * Picon URL without creds — for Coil/OkHttp where auth goes through the Authorization
     * header (OkHttp does not send the userinfo from the URL automatically).
     */
    fun piconUrlNoCreds(server: TvhServer, iconPublicUrl: String?): String? {
        val raw = iconPublicUrl?.trim() ?: return null
        if (raw.isEmpty()) return null
        // HTSP often returns full URLs (http://.../imagecache/N) — return it as it is
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val ipu = raw.trimStart('/')
        if (!ipu.startsWith("imagecache/")) return null
        return server.baseUrl.trimEnd('/') + "/" + ipu
    }
}
