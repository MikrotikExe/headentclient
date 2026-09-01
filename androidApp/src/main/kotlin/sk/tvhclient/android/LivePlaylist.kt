package sk.tvhclient.android

/**
 * Zdielany zoznam live kanalov pre prepinanie (zapping) a zoznam kanalov
 * priamo v prehravaci. Naplna sa pri zobrazeni zoznamu kanalov / mriezky.
 */
object LivePlaylist {
    data class LiveChannel(
        val uuid: String,
        val name: String,
        val number: Int,
        val piconUrl: String?,
        val nowTitle: String,
        val nowStart: Long,
        val nowStop: Long,
        val nextTitle: String = "",
        val nextStart: Long = 0,
        val nextStop: Long = 0,
        val recording: Boolean = false
    )

    @Volatile
    var channels: List<LiveChannel> = emptyList()

    // M369: plny (needlefiltrovany podla skupiny) zoznam + skupiny/tagy pre filter.
    // 'channels' je aktualne zobrazena podmnozina; 'allChannels' je vzdy cely zoznam,
    // z ktoreho sa pri zmene skupiny prestavuje. Skupiny su len tagy; Vsetky/Oblubene
    // su implicitne (Oblubene sa citaju dynamicky z Favorites v prehravaci).
    data class Group(val key: String, val label: String, val uuids: Set<String>)

    const val GROUP_ALL = ""
    const val GROUP_FAV = "\u0000fav"
    /** M541: pseudo-skupina skrytych kanalov (len v prehravaci, aby sa dali odkryt). */
    const val GROUP_HIDDEN = "\u0000hidden"

    /** M541: poradie oblubenych (uuid) — v skupine Oblubene sa kanaly cisluju 1..n podla neho. */
    @Volatile
    var favOrder: List<String> = emptyList()
    /** M541: skryte kanaly (nie su v allChannels ani v skupinach). */
    @Volatile
    var hiddenChannels: List<LiveChannel> = emptyList()

    /** M541: oblubene v ulozenom poradi, precislovane 1..n. */
    fun favChannels(): List<LiveChannel> {
        val byUuid = allChannels.associateBy { it.uuid }
        return favOrder.mapNotNull { byUuid[it] }.mapIndexed { i, ch -> ch.copy(number = i + 1) }
    }

    @Volatile
    var allChannels: List<LiveChannel> = emptyList()
    @Volatile
    var groups: List<Group> = emptyList()
    @Volatile
    var activeGroupKey: String = GROUP_ALL

    /** M391: uplny reset (zmena sposobu pripojenia servera — stare id neplatia). */
    fun reset() {
        allChannels = emptyList()
        channels = emptyList()
        groups = emptyList()
        favOrder = emptyList()
        hiddenChannels = emptyList()
        activeGroupKey = GROUP_ALL
    }

    /**
     * Naplni zoznam aj skupiny.
     *
     * M506: [restoreKey] = naposledy zvolena skupina (z LastTag). Ak sa medzi
     * skupinami najde, filter sa na nu nastavi — inak sa zacina od „Vsetky".
     * Vdaka tomu prehravac po restarte appky nabehne v tej istej skupine, v akej
     * pouzivatel skoncil, rovnako ako zoznam Kanaly.
     */
    fun setChannels(
        full: List<LiveChannel>, grps: List<Group>, restoreKey: String? = null,
        favs: List<String> = emptyList(), hidden: List<LiveChannel> = emptyList()
    ) {
        allChannels = full
        groups = grps
        favOrder = favs
        hiddenChannels = hidden
        // M541: aj Oblubene sa pamataju ako skupina
        if (restoreKey == GROUP_FAV) {
            val f = favChannels()
            if (f.isNotEmpty()) { activeGroupKey = GROUP_FAV; channels = f; return }
        }
        val g = restoreKey?.let { k -> grps.firstOrNull { it.key == k } }
        activeGroupKey = g?.key ?: GROUP_ALL
        channels = if (g != null) full.filter { it.uuid in g.uuids } else full
    }

    // M271: procesova cache EPG (uuid -> relacie) + cas poslednej uspesnej obnovy.
    // Prezije zatvorenie/otvorenie prehravaca, takze sa nesťahuje znova pri kazdom otvoreni.
    @Volatile
    var epgUpcoming: Map<String, List<sk.tvhclient.shared.model.EpgEvent>> = emptyMap()
    @Volatile
    var epgLastOkMs: Long = 0L

    fun clearEpg() {
        epgUpcoming = emptyMap()
        epgLastOkMs = 0L
    }

    @Volatile
    var index: Int = -1

    fun setIndexForUuid(uuid: String?) {
        index = if (uuid == null) -1 else channels.indexOfFirst { it.uuid == uuid }
    }
}
