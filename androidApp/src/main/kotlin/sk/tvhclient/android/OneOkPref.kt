package sk.tvhclient.android

import android.content.Context

/**
 * M596: "Switch channel with a single OK".
 *
 * In the player's channel list a channel is normally switched only on RELEASE of the OK button
 * (holding OK is the context menu) and for a channel currently being recorded it also asks
 * "live / from the start". Some remotes (IR/CEC) send OK in such a way
 * that the user perceives it as having to press OK twice.
 *
 * With the option on, OK in the list switches the channel right on the press, with no archive
 * question and with a shorter guard window after the list opens. Applies equally to
 * modern and classic mode. Off by default (the original behaviour).
 */
object OneOkPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "one_ok_switch"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
