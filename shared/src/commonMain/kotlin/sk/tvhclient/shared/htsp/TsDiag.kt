package sk.tvhclient.shared.htsp

/**
 * M450-diag: prepinac a hooky pre diagnostiku TS muxera.
 * Appka nastavi enabled/nowMillis/log pri starte prehravaca; shared kod tak
 * nepotrebuje platformove API. Docasna vec na ladenie trhania HEVC.
 */
object TsDiag {
    var enabled: Boolean = false
    var nowMillis: () -> Long = { 0L }
    var log: (String) -> Unit = { }
}
