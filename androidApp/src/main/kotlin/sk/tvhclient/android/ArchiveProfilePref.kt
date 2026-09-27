package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * M707 (issue #18): "Sort the archive by profile" (Settings -> General -> Recordings).
 *
 * Adds a "By profile" section to the archive (phone, modern and classic TV): the recordings grouped
 * by the DVR profile they were recorded with. Most servers have a single profile, so it is off by
 * default; whoever wants it turns it on here.
 */
object ArchiveProfilePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "archive_by_profile"

    private var state: MutableState<Boolean>? = null

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Compose state shared by every screen, so a change in the Settings shows up at once. */
    fun stateOf(context: Context): MutableState<Boolean> =
        state ?: mutableStateOf(get(context)).also { state = it }

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
        stateOf(context).value = enabled
    }
}
