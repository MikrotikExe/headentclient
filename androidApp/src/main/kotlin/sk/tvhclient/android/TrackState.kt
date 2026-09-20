package sk.tvhclient.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import org.videolan.libvlc.MediaPlayer

/**
 * M637: track state (audio / subtitles / profile) in the player, extracted from PlayerActivity.
 *
 * Holds the compose states for the track menu (open signals, navigation, list version),
 * the HTSP subtitle selection (our own DVB decoder — [selectedSubEs]), the persistent HTTP
 * subtitle selection (M392-fix: [httpSpuWantOff]/[httpSpuWantName], enforced on every ESAdded),
 * the profile switcher state (M383) and two timers: repeated refresh of the track list after start
 * (ESAdded does not arrive reliably) and a one-off re-parse when the audio tracks have no language.
 *
 * Whatever changes the stream (profile selection, writing the last audio track, switching channel) stays
 * in the activity — only [player] and [htspFeeder] come in here, as lambdas.
 */
internal class TrackState(
    private val ctx: Context,
    private val player: () -> MediaPlayer?,
    private val htspFeeder: () -> HtspTsFeeder?
) {
    // HTSP subtitles: we take the complete language list from the metadata (feeder.subtitleStreams),
    // not from libVLC (which only has the languages that have already "spoken"). We map the selection to the real
    // libVLC track by the English language name (libVLC does not tag DVB subtitles with a code).
    val selectedSubEs = mutableStateOf(-1)  // -1 = Off
    var desiredSubName: String? = null   // English name of the selected language (null = off)
    // M392-fix: the user's persistent choice for HTTP live subtitles. Default OFF (same as HTSP).
    var httpSpuWantOff = true
    var httpSpuWantName: String? = null

    // signals for PlayerUi (increment = open)
    val openAudioSignal = mutableStateOf(0)
    val openSpuSignal = mutableStateOf(0)
    val openProfileSignal = mutableStateOf(0)
    val closeMenuSignal = mutableStateOf(0)
    // M383: stream profile switcher in the player (HTTP live only)
    val profileItems = mutableStateOf<List<String>>(emptyList())
    val currentProfile = mutableStateOf("")
    // M383-fix: availability MUST be compose state — otherwise the UI would not learn about the change
    val profileSwitch = mutableStateOf(false)
    // Track menu navigation (audio/subtitles) from the Activity
    val navIndex = mutableStateOf(0)
    // Track list version — incremented when libVLC adds/removes a track (ESAdded/ESDeleted).
    val listVersion = mutableStateOf(0)
    var menuKind = "audio"

    fun bumpListVersion() { listVersion.value = listVersion.value + 1 }

    fun openAudioMenu() { menuKind = "audio"; navIndex.value = 0; openAudioSignal.value++ }
    fun openSpuMenu() { menuKind = "spu"; navIndex.value = 0; openSpuSignal.value++ }
    /** Opens the profile menu; the item list is supplied by the activity (the server may replace it later). */
    fun openProfileMenu() { menuKind = "profile"; navIndex.value = 0; openProfileSignal.value++ }
    fun closeMenu() { closeMenuSignal.value++ }

    /** Ids of the items of the currently open menu in display order (for D-pad selection). */
    fun menuIds(htspStream: Boolean): List<Int> {
        val mp = player() ?: return emptyList()
        if (menuKind == "profile") return profileItems.value.indices.toList()
        return if (menuKind == "audio") {
            mp.audioTrackItems().map { it.id }
        } else {
            val spu = if (htspStream) htspSpuItems() else mp.spuTrackItems()
            listOf(-1) + spu.map { it.id }  // -1 = Off
        }
    }

    /** HTSP: the complete list of subtitle languages from the metadata (the same on every device,
     *  regardless of whether the language has already "spoken"). id = HTSP stream index. */
    fun htspSpuItems(): List<TrackItem> {
        val subs = htspFeeder()?.subtitleStreams ?: return emptyList()
        // M491: track name when the language cannot be determined — it was hard-coded in Slovak
        return subs.map { TrackItem(it.esIndex, langDisplay(it.language) ?: ctx.getString(R.string.sub_dvb)) }
    }

    /** Sets the libVLC subtitle track by the wanted (English) language name, if it already exists. */
    fun applyDesiredSpu() {
        val mp = player() ?: return
        val want = desiredSubName ?: return
        val tracks = mp.spuTracks ?: return
        val m = tracks.firstOrNull { it.id >= 0 && (it.name?.contains(want, ignoreCase = true) == true) } ?: return
        if (mp.spuTrack != m.id) mp.spuTrack = m.id
    }

    /** M392-fix: enforce the user's subtitle choice on the HTTP live stream (on every ESAdded). */
    fun applyPendingSpuRestore(htspStream: Boolean, seekable: Boolean) {
        val mp = player() ?: return
        if (htspStream || seekable) return
        val want = httpSpuWantName
        if (want != null) {
            val tr = mp.spuTracks?.firstOrNull { it.id >= 0 && (it.name?.contains(want, ignoreCase = true) == true) } ?: return
            if (mp.spuTrack != tr.id) mp.spuTrack = tr.id
            return
        }
        if (httpSpuWantOff && mp.spuTrack != -1) mp.spuTrack = -1
    }

    /** M392-fix: manual subtitle selection on HTTP live (both D-pad and touch menu). */
    fun httpSpuUserPick(id: Int) {
        httpSpuWantOff = id < 0
        httpSpuWantName = if (id >= 0) player()?.spuTrackItems()?.firstOrNull { it.id == id }?.name else null
    }

    /** M392: reconcile the wanted state with the actual state (before the stream restart on a profile change). */
    fun captureHttpSpuFromPlayer() {
        val mp = player() ?: return
        val cur = runCatching { mp.spuTrack }.getOrDefault(-1)
        httpSpuWantOff = cur < 0
        httpSpuWantName = if (cur >= 0) mp.spuTrackItems().firstOrNull { it.id == cur }?.name else null
    }

    /** New channel: HTSP subtitles off, HTTP choice back to the default OFF, allow a re-parse. */
    fun resetForNewChannel() {
        selectedSubEs.value = -1
        desiredSubName = null
        httpSpuWantOff = true
        httpSpuWantName = null
        reparseDone = false
        reparseHandler.removeCallbacksAndMessages(null)
    }

    // ---- track list refresh after start ----
    // Tracks and DVB subtitle tracks only appear a few seconds after start. On some
    // streams ESAdded does not arrive reliably, so after Event.Playing we poll briefly and refresh
    // any open track menu (by incrementing listVersion) until the tracks fill in.
    private val refreshHandler = Handler(Looper.getMainLooper())
    fun scheduleRefresh() {
        refreshHandler.removeCallbacksAndMessages(null)
        // several waves over ~8 s — enough for the languages and the DVB subtitles to finish parsing
        for (delay in longArrayOf(800L, 1600L, 2600L, 4000L, 6000L, 8000L)) {
            refreshHandler.postDelayed({ bumpListVersion() }, delay)
        }
    }
    fun cancelRefresh() = refreshHandler.removeCallbacksAndMessages(null)

    // ---- one-off re-parse because of the tracks ----
    // If no audio track has a language after start, libVLC created the ES before it had parsed the PMT
    // with the language descriptors (common on multi-audio TS). Only a fresh attach to the stream helps.
    // We do it ONCE per channel and ONLY when the languages are genuinely missing (otherwise no needless flicker).
    private var reparseDone = false
    private val reparseHandler = Handler(Looper.getMainLooper())
    fun maybeReparse(htspStream: () -> Boolean, seekable: () -> Boolean, reconnect: () -> Unit) {
        if (reparseDone || seekable() || htspStream()) return  // HTSP takes the languages from the PMT
        reparseHandler.removeCallbacksAndMessages(null)
        reparseHandler.postDelayed({
            val mp = player()
            if (reparseDone || seekable() || htspStream() || mp == null) return@postDelayed
            val langs = runCatching { mp.trackLanguages() }.getOrDefault(emptyMap())
            val anyLang = langs.values.any { !it.isNullOrBlank() && !it.equals("und", true) }
            reparseDone = true  // either way, try only once
            // re-attach via the PROVEN reconnect path (it recovers by itself and fills the tracks)
            if (!anyLang) reconnect()
        }, 1800)
    }

    /** New channel -> allow a one-off track re-parse. */
    fun resetReparse() { reparseDone = false; reparseHandler.removeCallbacksAndMessages(null) }

    fun destroy() {
        refreshHandler.removeCallbacksAndMessages(null)
        reparseHandler.removeCallbacksAndMessages(null)
    }
}
