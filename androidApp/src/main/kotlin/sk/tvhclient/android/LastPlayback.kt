package sk.tvhclient.android

import android.content.Context

/**
 * M494/M497: zapamatanie posledneho ZIVEHO vysielania (TV kanal alebo radio),
 * aby po zapnuti setoboxu appka nabehla rovnako, ako ked ju pustis rucne —
 * teda s nacitanym zoznamom kanalov a spustenym tym istym kanalom.
 *
 * Archiv sem zamerne NEPATRI: dopozerana nahravka by sa otvarala znova a na
 * pokracovanie v rozpozeranej sluzi WatchProgress.
 *
 * Zaznam sa zapisuje, kym prehravac bezi, a maze sa vo chvili, ked z neho
 * pouzivatel zamerne odide (BACK na zoznam, zatvorenie). Vdaka tomu sa stav
 * obnovi len vtedy, ked bol prehravac na obrazovke v momente vypnutia — ak
 * pouzivatel skoncil na zozname kanalov, otvori sa zoznam.
 *
 * Funkcia je urcena pre TV/Leanback; na telefone sa neuplatnuje.
 */
object LastPlayback {
    private const val PREFS = "app_prefs"
    private const val KEY_KIND = "lastpb_kind"          // "tv" | "radio"
    private const val KEY_SERVER = "lastpb_server"
    private const val KEY_UUID = "lastpb_uuid"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Zapamataj beziace zive vysielanie. [kind] je "tv" alebo "radio". */
    fun setLive(c: Context, serverId: String?, channelUuid: String?, kind: String) {
        if (serverId == null || channelUuid.isNullOrBlank()) return
        prefs(c).edit()
            .putString(KEY_KIND, if (kind == "radio") "radio" else "tv")
            .putString(KEY_SERVER, serverId)
            .putString(KEY_UUID, channelUuid)
            .apply()
    }

    /** Pouzivatel odisiel z prehravaca — uz niet co obnovovat. */
    fun clear(c: Context) {
        prefs(c).edit()
            .remove(KEY_KIND).remove(KEY_SERVER).remove(KEY_UUID)
            .apply()
    }

    /**
     * M496/M497: poziadavka na obnovenie. Vykona ju UI, nie Activity v onCreate —
     * prehravac dostava zoznam kanalov cez LivePlaylist a ten pri studenom starte
     * este nie je naplneny. Priame otvorenie by hralo jediny kanal a CH+/- by
     * nefungovalo, preto sa pouzije ta ista cesta ako pri autostarte: pocka sa na
     * nacitanie kanalov/radii a az potom sa spusti.
     */
    @Volatile
    var pendingKind: String? = null     // "tv" | "radio" | null

    /** uuid, ktore ma po nacitani zoznamu prednost pred LastChannel/LastRadio. */
    @Volatile
    var pendingUuid: String? = null

    /** Priprav obnovenie podla ulozeneho stavu. */
    fun prepareRestore(c: Context, activeServerId: String?) {
        if (activeServerId == null) return
        val p = prefs(c)
        val kind = p.getString(KEY_KIND, null) ?: return
        if (p.getString(KEY_SERVER, null) != activeServerId) return
        val uuid = p.getString(KEY_UUID, null)?.takeIf { it.isNotBlank() } ?: return
        pendingKind = if (kind == "radio") "radio" else "tv"
        pendingUuid = uuid
    }
}

/**
 * M494/M497: spustit po starte appky posledny zivy kanal? Len TV/Leanback.
 *
 * Predvolene zapnute — na setoboxe je ocakavane, ze sa po zapnuti vratis na
 * kanal, ktory si pozeral. Kto to nechce, vypne si to v nastaveniach.
 */
object ResumeLastPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "resume_last_playback"

    fun get(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
