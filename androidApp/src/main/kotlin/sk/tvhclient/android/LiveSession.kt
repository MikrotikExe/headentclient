package sk.tvhclient.android

import androidx.compose.runtime.mutableStateOf
import sk.tvhclient.shared.model.TvhServer

/**
 * M639: stav živého prehrávania (zapping) na jednom mieste — uzol, na ktorý siaha
 * väčšina zhlukov PlayerActivity (liveUuids/liveIndex/playKind/server + compose stavy
 * pre PlayerUi). Zatiaľ len držiak dát bez logiky: aktivita k nemu pristupuje cez
 * delegujúce vlastnosti s pôvodnými názvami, takže správanie sa nemení. Ďalšie
 * kroky rozdelenia (kontextové menu, skupiny, moderný overlay, switchToIndex) dostanú
 * tento objekt namiesto desiatky lambd.
 */
internal class LiveSession {
    // Live zapping (prepinanie kanalov v prehravaci)
    var uuids: List<String> = emptyList()
    var names: List<String> = emptyList()
    var index: Int = -1
    var playKind: String = "tv"
    var server: TvhServer? = null

    // compose stavy pre PlayerUi
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

    /** Kanál na aktuálnom indexe podľa compose stavu (indexState), null ak mimo. */
    fun currentChannel(): LivePlaylist.LiveChannel? = channelsState.value.getOrNull(indexState.value)
}
