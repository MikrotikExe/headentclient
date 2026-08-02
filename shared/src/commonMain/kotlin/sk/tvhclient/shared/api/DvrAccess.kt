package sk.tvhclient.shared.api

/**
 * M471: prava pouzivatela vo vztahu k nahravaniu — spolocny tvar pre obe cesty
 * (HTSP `accessUpdate`, HTTP `api/access/whoami`).
 *
 * Appka podla `canRecord` zobrazi alebo skryje moznosti nahravania. Nie je to
 * bezpecnostny mechanizmus — server prava vynuti sam (HTSP vrati chybu,
 * HTTP odpovie 403) — ide o to, aby sa pouzivatelovi neponukalo nieco,
 * co mu server aj tak odmietne.
 */
data class DvrAccess(
    val canRecord: Boolean = false,
    val canSeeFailed: Boolean = false,
    val isAdmin: Boolean = false,
    /** 0 = bez limitu; inak max. sucasnych nahravani pre tohto pouzivatela. */
    val recordingLimit: Int = 0,
    /** false = server prava neoznamil (stary server) — treba skusit a chytit chybu. */
    val known: Boolean = false
) {
    companion object {
        /** Stary server / neznamy stav: nahravanie ponukneme, chybu ohlasime az zo servera. */
        val UNKNOWN = DvrAccess(canRecord = true, known = false)
        val DENIED = DvrAccess(canRecord = false, known = true)
    }
}
