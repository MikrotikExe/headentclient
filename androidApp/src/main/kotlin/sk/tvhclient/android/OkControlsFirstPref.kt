package sk.tvhclient.android

import android.content.Context

/**
 * M717 (issue #24): "Controls first, then channel list".
 *
 * Classic mode, live broadcast, controls hidden: OK normally opens the channel list straight away.
 * With the option on, the first OK shows the control bar and the next OK opens the channel list;
 * if the user moves with the arrows in between, OK activates the highlighted control as usual.
 * The modern mode (its own overlay) and recordings (OK = play/pause) are not affected.
 * Off by default (the original behaviour).
 */
object OkControlsFirstPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "ok_controls_first"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
