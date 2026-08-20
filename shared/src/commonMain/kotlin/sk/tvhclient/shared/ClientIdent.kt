package sk.tvhclient.shared

/**
 * M440: jednotna identita klienta pre vsetky spojenia (HTTP User-Agent,
 * HTSP clientname). Verziu nastavuje aplikacia pri starte
 * (TvhApplication.onCreate z PackageInfo) — shared kod k BuildConfig
 * nema pristup. Fallback "?" plati len do inicializacie.
 */
object ClientIdent {
    var version: String = "?"
    val userAgent: String get() = "HeadentClient/" + version

    /**
     * M511: jazykova preferencia klienta pre EPG.
     *
     * Tvheadend zlucuje OTA a XMLTV do jednej udalosti a jednotlive verzie
     * nazvov/popisov drzi ako JAZYKOVE MUTACIE. Ktoru klient dostane, urcuje
     * jeho preferencia; kto ziadnu neposle, dostane serverovu predvolbu
     * (Configuration -> General -> Default Language(s)). Preto sa mohlo stat,
     * ze appka ukazala OTA text, kym Kodi to iste ukazalo z XMLTV.
     *
     * `lang2` je RFC 2616 zoznam pre HTSP ("de,en"), `lang3` je 3-pismenovy
     * ISO-639 kod pre HTTP api (`lang=ger`). Nastavuje aplikacia pri starte.
     */
    var lang2: String = ""
    var lang3: String = ""
}
