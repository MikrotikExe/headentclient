package sk.tvhclient.android

import androidx.compose.runtime.mutableStateOf

/**
 * M655: stav otvoreného streamu (vyclenené z PlayerActivity) — feedery, HTSP príznaky,
 * aktuálna URL a cache „potrebuje live HTTP na tomto serveri feeder?" (M390). Držiak dát;
 * aktivita k nemu pristupuje cez delegáty s pôvodnými názvami, otváranie rieši [StreamOpener].
 */
internal class StreamState {
    var htspFeeder: HtspTsFeeder? = null
    var httpFeeder: HttpTsFeeder? = null
    /** DVR nahrávka ide cez HttpTsFeeder (digest-only server), nie priamo cez URL. */
    var dvrViaFeeder = false
    /** HTSP subscription s timeshiftom (server ho podporuje a je zapnutý). */
    var htspLive = false
    val htspStreamState = mutableStateOf(false)
    /** Aktuálny stream ide cez HTSP (feeder), nie HTTP. */
    var htspStream: Boolean
        get() = htspStreamState.value
        set(v) { htspStreamState.value = v }
    val htspLiveState = mutableStateOf(false)
    var currentStreamUrl: String? = null
    /** Cache: vyžaduje live HTTP na tomto serveri feeder (digest-only)? null = nezistené. */
    var liveNeedsFeeder: Boolean? = null

    /** Spoločný začiatok každého HTTP otvorenia: zastav feedery, zruš HTSP príznaky. */
    fun resetForHttp(keepHttpFeeder: Boolean) {
        htspFeeder?.stop(); htspFeeder = null
        httpFeeder?.stop()
        if (!keepHttpFeeder) httpFeeder = null
        htspStream = false
        htspLive = false
        htspLiveState.value = false
    }
}
