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
}
