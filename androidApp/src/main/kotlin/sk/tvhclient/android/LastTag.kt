package sk.tvhclient.android

import android.content.Context

/**
 * M505: the last selected tag (channel group) — separately for TV and separately for Radio.
 *
 * Whoever has hundreds of channels sorted into groups usually uses only one or
 * two; after opening the app there is no point starting with a list of all of them. It is saved PER
 * SERVER, because a tag uuid is only valid on the server it came from.
 *
 * Empty value / missing record = "all channels".
 */
object LastTag {
    private const val PREFS = "app_prefs"
    /** M541: "Favourites" as a remembered group. In the preferences without the NUL character
     *  (LivePlaylist.GROUP_FAV is "\u0000fav" — NUL does not belong in XML SharedPreferences). */
    const val FAV = "fav"

    /** Saved value -> group key for LivePlaylist (FAV -> GROUP_FAV). */
    fun toGroupKey(saved: String?): String? =
        if (saved == FAV) LivePlaylist.GROUP_FAV else saved

    /** Group key from the player -> value to save (GROUP_FAV -> FAV). */
    fun fromGroupKey(key: String): String =
        if (key == LivePlaylist.GROUP_FAV) FAV else key

    private fun key(serverId: String, radio: Boolean) =
        "lasttag_" + (if (radio) "radio_" else "tv_") + serverId

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tag uuid, or null for "all". */
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
