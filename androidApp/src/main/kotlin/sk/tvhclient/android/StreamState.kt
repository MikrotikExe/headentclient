package sk.tvhclient.android

import androidx.compose.runtime.mutableStateOf

/**
 * M655: state of the opened stream (extracted from PlayerActivity) — feeders, HTSP flags,
 * the current URL and the cache "does live HTTP on this server need a feeder?" (M390). A data holder;
 * the activity accesses it through delegates with the original names, opening is handled by [StreamOpener].
 */
internal class StreamState {
    var htspFeeder: HtspTsFeeder? = null
    var httpFeeder: HttpTsFeeder? = null
    /** The DVR recording goes through HttpTsFeeder (digest-only server), not directly via the URL. */
    var dvrViaFeeder = false
    /** HTSP subscription with timeshift (the server supports it and it is enabled). */
    var htspLive = false
    val htspStreamState = mutableStateOf(false)
    /** The current stream goes through HTSP (feeder), not HTTP. */
    var htspStream: Boolean
        get() = htspStreamState.value
        set(v) { htspStreamState.value = v }
    val htspLiveState = mutableStateOf(false)
    var currentStreamUrl: String? = null
    /** Cache: does live HTTP on this server require a feeder (digest-only)? null = not determined. */
    var liveNeedsFeeder: Boolean? = null

    /** Common start of every HTTP open: stop the feeders, clear the HTSP flags. */
    fun resetForHttp(keepHttpFeeder: Boolean) {
        htspFeeder?.stop(); htspFeeder = null
        httpFeeder?.stop()
        if (!keepHttpFeeder) httpFeeder = null
        htspStream = false
        htspLive = false
        htspLiveState.value = false
    }
}
