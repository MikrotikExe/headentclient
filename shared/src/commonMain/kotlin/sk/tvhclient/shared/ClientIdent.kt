package sk.tvhclient.shared

/**
 * M440: a unified client identity for all connections (HTTP User-Agent,
 * HTSP clientname). The version is set by the application at start-up
 * (TvhApplication.onCreate from PackageInfo) — the shared code has no
 * access to BuildConfig. The "?" fallback applies only until initialisation.
 */
object ClientIdent {
    var version: String = "?"
    val userAgent: String get() = "HeadentClient/" + version

    /**
     * M511: the client's language preference for the EPG.
     *
     * Tvheadend merges OTA and XMLTV into a single event and keeps the individual versions
     * of the titles/descriptions as LANGUAGE VARIANTS. Which one the client gets is decided by
     * its preference; whoever sends none gets the server default
     * (Configuration -> General -> Default Language(s)). That is why it could happen
     * that the app showed the OTA text while Kodi showed the same thing from XMLTV.
     *
     * `lang2` is the RFC 2616 list for HTSP ("de,en"), `lang3` is the 3-letter
     * ISO-639 code for the HTTP api (`lang=ger`). Set by the application at start-up.
     */
    var lang2: String = ""
    var lang3: String = ""

    /**
     * M715: the EPG language list for HTSP, the same as the HTTP api builds it. Over HTTP the
     * server puts the client's language first and then its own default languages
     * (lang_codes.c lang_code_user); over HTSP it takes only what the client sends (htsp_server.c
     * getEvents/epgQuery). With [lang2] = "sk,en" a programme with Czech and English variants
     * therefore came in English over HTSP and in Czech over HTTP (server languages "cze,eng").
     * [serverLangs] = the server's default languages from the HTSP hello reply ("language").
     * Without them the list stays [lang2] (M511).
     */
    fun htspEpgLanguage(serverLangs: String?): String {
        val srv = serverLangs?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (srv.isEmpty()) return lang2
        val dev = lang2.substringBefore(',').trim()
        return (listOf(dev).filter { it.isNotEmpty() } + srv).joinToString(",")
    }
}
