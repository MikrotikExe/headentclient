package sk.tvhclient.android

import android.content.Context

/**
 * M505: posledne zvoleny tag (skupina kanalov) — zvlast pre TV a zvlast pre Radio.
 *
 * Kto ma stovky kanalov roztriedenych do skupin, obvykle pouziva len jednu alebo
 * dve; po otvoreni appky nema zmysel zacinat zoznamom vsetkych. Uklada sa PER
 * SERVER, lebo tag uuid je platne len na tom serveri, z ktoreho prislo.
 *
 * Prazdna hodnota / chybajuci zaznam = „vsetky kanaly".
 */
object LastTag {
    private const val PREFS = "app_prefs"
    /** M541: „Oblubene" ako zapamatana skupina. V preferenciach bez NUL znaku
     *  (LivePlaylist.GROUP_FAV je "\u0000fav" — NUL do XML SharedPreferences nepatri). */
    const val FAV = "fav"

    /** Ulozena hodnota -> kluc skupiny pre LivePlaylist (FAV -> GROUP_FAV). */
    fun toGroupKey(saved: String?): String? =
        if (saved == FAV) LivePlaylist.GROUP_FAV else saved

    /** Kluc skupiny z prehravaca -> hodnota na ulozenie (GROUP_FAV -> FAV). */
    fun fromGroupKey(key: String): String =
        if (key == LivePlaylist.GROUP_FAV) FAV else key

    private fun key(serverId: String, radio: Boolean) =
        "lasttag_" + (if (radio) "radio_" else "tv_") + serverId

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tag uuid, alebo null pre „vsetky". */
    fun get(c: Context, serverId: String?, radio: Boolean): String? {
        if (serverId.isNullOrBlank()) return null
        return prefs(c).getString(key(serverId, radio), null)?.takeIf { it.isNotBlank() }
    }

    fun set(c: Context, serverId: String?, radio: Boolean, tagUuid: String?) {
        if (serverId.isNullOrBlank()) return
        val e = prefs(c).edit()
        if (tagUuid.isNullOrBlank()) e.remove(key(serverId, radio))
        else e.putString(key(serverId, radio), tagUuid)
        e.apply()
    }
}
