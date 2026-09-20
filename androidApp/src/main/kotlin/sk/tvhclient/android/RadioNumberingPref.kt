package sk.tvhclient.android

import android.content.Context
import sk.tvhclient.shared.api.ChannelRow

/**
 * M602: "Number radios from 1".
 *
 * Radio stations get numbers from the server that nobody uses (634, 635…),
 * while for TV channels they are established. By default the radios are therefore numbered 1…n by
 * their order in the currently displayed group — in the Radios tab, in the TV guide grid and
 * in the station list in the player (and selection by digits follows this too). The server
 * numbers stay untouched; anyone who wants to see them turns the option off.
 */
object RadioNumberingPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "radio_number_from_one"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    /** Renumbers the list 1…n (without changing the order), if the option is on. */
    fun apply(context: Context, rows: List<ChannelRow>): List<ChannelRow> =
        if (!get(context)) rows
        else rows.mapIndexed { i, r -> r.copy(channel = r.channel.copy(number = i + 1)) }
}
