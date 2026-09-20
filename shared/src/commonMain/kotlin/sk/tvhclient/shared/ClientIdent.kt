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
}
