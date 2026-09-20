package sk.tvhclient.android

import android.content.Context

/**
 * M494/M497: remembering the last LIVE broadcast (TV channel or radio),
 * so that after switching the set-top box on the app comes up just as when you start it by hand —
 * that is, with the channel list loaded and that same channel playing.
 *
 * The archive deliberately does NOT belong here: a recording watched to the end would open again and
 * WatchProgress serves for resuming one in progress.
 *
 * The record is written while the player is running and is deleted the moment the
 * user deliberately leaves it (BACK to the list, closing). Thanks to that the state
 * is restored only when the player was on screen at the moment of switching off — if the
 * user ended up on the channel list, the list opens.
 *
 * The feature is intended for TV/Leanback; on a phone it does not apply.
 */
object LastPlayback {
    private const val PREFS = "app_prefs"
    private const val KEY_KIND = "lastpb_kind"          // "tv" | "radio"
    private const val KEY_SERVER = "lastpb_server"
    private const val KEY_UUID = "lastpb_uuid"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Remember the running live broadcast. [kind] is "tv" or "radio". */
    fun setLive(c: Context, serverId: String?, channelUuid: String?, kind: String) {
        if (serverId == null || channelUuid.isNullOrBlank()) return
        prefs(c).edit()
            .putString(KEY_KIND, if (kind == "radio") "radio" else "tv")
            .putString(KEY_SERVER, serverId)
            .putString(KEY_UUID, channelUuid)
            .apply()
    }

    /** The user has left the player — there is nothing left to restore. */
    fun clear(c: Context) {
        prefs(c).edit()
            .remove(KEY_KIND).remove(KEY_SERVER).remove(KEY_UUID)
            .apply()
    }

    /**
     * M496/M497: a request to restore. It is carried out by the UI, not by the Activity in onCreate —
     * the player gets the channel list through LivePlaylist and on a cold start that is
     * not yet populated. Opening directly would play a single channel and CH+/- would
     * not work, which is why the same path as for autostart is used: it waits for the
     * channels/radios to load and only then starts.
     */
    @Volatile
    var pendingKind: String? = null     // "tv" | "radio" | null

    /** uuid that takes precedence over LastChannel/LastRadio once the list has loaded. */
    @Volatile
    var pendingUuid: String? = null

    /** Prepare the restore from the saved state. */
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
 * M494/M497: start the last live channel after the app starts? TV/Leanback only.
 *
 * On by default — on a set-top box it is expected that after switching on you return to the
 * channel you were watching. Whoever does not want that turns it off in the settings.
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
