package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * M388: the density of the EPG grid on a phone (<600dp).
 *  - true (default) = compact: 1 min = 3 dp, lower rows, a narrower channel column
 *  - false = comfortable: the original dimensions from M387
 * On wide screens (TV/tablet) the value is ignored.
 * We also hold a live state (MutableState), so that a switch takes effect immediately.
 */
object EpgDensityPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "epg_compact"

    private var state: MutableState<Boolean>? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun compactStateOf(c: Context): MutableState<Boolean> =
        state ?: mutableStateOf(prefs(c).getBoolean(KEY, true)).also { state = it }

    fun set(c: Context, v: Boolean) {
        compactStateOf(c).value = v
        prefs(c).edit().putBoolean(KEY, v).apply()
    }
}
