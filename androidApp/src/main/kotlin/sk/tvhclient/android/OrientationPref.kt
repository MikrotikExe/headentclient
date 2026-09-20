package sk.tvhclient.android

import android.content.Context

/**
 * Default screen rotation in the player.
 *  - AUTO: by the sensor / device setting (behaves as before, can be pinned with the lock)
 *  - PORTRAIT: forced portrait
 *  - LANDSCAPE: forced landscape
 * With PORTRAIT/LANDSCAPE the orientation is already fixed, so the lock button in the player makes no sense
 * and is hidden. Stored globally in SharedPreferences.
 */
object OrientationPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "player_orientation"

    const val AUTO = "auto"
    const val PORTRAIT = "portrait"
    const val LANDSCAPE = "landscape"

    val options = listOf(AUTO, LANDSCAPE, PORTRAIT)

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, AUTO) ?: AUTO

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
