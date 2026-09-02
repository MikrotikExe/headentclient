package sk.tvhclient.shared.stream

import sk.tvhclient.shared.model.TvhServer

/**
 * Stavba stream URL pre live aj DVR. Prebrate z pluginu (_stream_urls.py):
 *  - live: stream/channel/{uuid}?profile=pass (PREFER_CHANNEL_STREAM)
 *  - DVR:  dvrfile/{id}
 *  - credentials vlozene priamo do URL (user:pass@host) — funguje pre plain
 *    auth; pri digest-only ich player riesi cez auth handler (M3).
 *  - volitelny title param.
 *
 * Pozn.: vkladanie credentials do URL je nutne lebo prehravac (ExoPlayer/
 * VLCKit) otvara stream samostatne, mimo Ktor klienta s jeho auth.
 */
object StreamUrlBuilder {

    private const val STREAM_CH = "stream/channel/%s"
    private const val STREAM_CHID = "stream/channelid/%s"

    private fun encode(s: String): String = buildString {
        for (c in s) {
            when {
                c.isLetterOrDigit() || c in "-_.~" -> append(c)
                else -> append('%').append(
                    c.code.toString(16).uppercase().padStart(2, '0')
                )
            }
        }
    }

    private fun withCreds(server: TvhServer, fullUrl: String): String {
        if (server.username.isEmpty()) return fullUrl
        val scheme = if (server.useHttps) "https://" else "http://"
        if (!fullUrl.startsWith(scheme)) return fullUrl
        val rest = fullUrl.substring(scheme.length)
        val creds = encode(server.username) + ":" + encode(server.password) + "@"
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
        val ep = if (htsp) STREAM_CHID.format(channelUuid) else STREAM_CH.format(channelUuid)
        return build(server, ep, profile, channelTitle)
    }

    /**
     * Live URL bez creds — pre ExoPlayer/VLCKit kde auth ide cez hlavicku.
     */
    fun liveUrlNoCreds(
        server: TvhServer,
        channelUuid: String,
        profile: String = "pass",
        channelTitle: String? = null,
        htsp: Boolean = false
    ): String {
        val ep = if (htsp) STREAM_CHID.format(channelUuid) else STREAM_CH.format(channelUuid)
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
     * Lokalna URL piconu (imagecache). Vracia plnu URL aj s creds pre stiahnutie.
     */
    fun piconUrl(server: TvhServer, iconPublicUrl: String?): String? {
        val ipu = iconPublicUrl?.trim()?.trimStart('/') ?: return null
        if (!ipu.startsWith("imagecache/")) return null
        return withCreds(server, server.baseUrl.trimEnd('/') + "/" + ipu)
    }

    /**
     * Picon URL bez creds — pre Coil/OkHttp kde auth ide cez Authorization
     * hlavicku (OkHttp neposle userinfo z URL automaticky).
     */
    fun piconUrlNoCreds(server: TvhServer, iconPublicUrl: String?): String? {
        val raw = iconPublicUrl?.trim() ?: return null
        if (raw.isEmpty()) return null
        // HTSP casto vracia plne URL (http://.../imagecache/N) — vrat tak ako je
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val ipu = raw.trimStart('/')
        if (!ipu.startsWith("imagecache/")) return null
        return server.baseUrl.trimEnd('/') + "/" + ipu
    }
}
