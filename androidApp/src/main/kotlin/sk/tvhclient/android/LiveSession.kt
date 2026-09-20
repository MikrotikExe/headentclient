package sk.tvhclient.android

import androidx.compose.runtime.mutableStateOf
import sk.tvhclient.shared.model.TvhServer

/**
 * M639: the state of live playback (zapping) in one place — the node most of the
 * clusters of PlayerActivity reach for (liveUuids/liveIndex/playKind/server + compose states
 * for PlayerUi). For now just a data holder with no logic: the activity accesses it through
 * delegating properties with the original names, so the behaviour does not change. Further
 * splitting steps (context menu, groups, modern overlay, switchToIndex) will get
 * this object instead of a dozen lambdas.
 */
internal class LiveSession {
    // Live zapping (switching channels in the player)
    var uuids: List<String> = emptyList()
    var names: List<String> = emptyList()
    var index: Int = -1
    var playKind: String = "tv"
    var server: TvhServer? = null

    // compose states for PlayerUi
    val titleState = mutableStateOf("")
    val uuidState = mutableStateOf<String?>(null)
    val progStartState = mutableStateOf(0L)
    val progStopState = mutableStateOf(0L)
    val progTitleState = mutableStateOf("")
    val nextTitleState = mutableStateOf("")
    val nextStartState = mutableStateOf(0L)
    val nextStopState = mutableStateOf(0L)
    val indexState = mutableStateOf(-1)
    val channelsState = mutableStateOf<List<LivePlaylist.LiveChannel>>(emptyList())
    /** M407: an increment = a new channel/preview — PlayerUi restarts the zapping bar timer. */
    val zapPokeState = mutableStateOf(0)

    /** M652: now/next programme of channel [ch] into the compose states (null = unknown programme, hide the progress). */
    fun showProgramme(ch: LivePlaylist.LiveChannel?) {
        progStartState.value = ch?.nowStart ?: 0L
        progStopState.value = ch?.nowStop ?: 0L
        progTitleState.value = ch?.nowTitle ?: ""
        nextTitleState.value = ch?.nextTitle ?: ""
        nextStartState.value = ch?.nextStart ?: 0L
        nextStopState.value = ch?.nextStop ?: 0L
        zapPokeState.value = zapPokeState.value + 1
    }

    /** The channel at the current index according to the compose state (indexState), null if out of range. */
    fun currentChannel(): LivePlaylist.LiveChannel? = channelsState.value.getOrNull(indexState.value)
}
