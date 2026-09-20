package sk.tvhclient.android

import android.content.Context

/**
 * M623: "Radio plays in the background" (phone and TV, off by default).
 *
 * On: when the radio player goes into the background (screen off, lock, home
 * screen, another app/browser), the radio keeps playing — the phone (both modern and
 * classic mode, M624) via RadioPlayerService with a notification and a mini bar,
 * on TV the player itself keeps playing (it holds the wake/wifi lock from M452).
 * In the player itself the screen stays on (KEEP_SCREEN_ON as on TV) —
 * radio in the background is only for the time outside the player.
 *
 * Off (the default): the original behaviour — going into the background pauses the radio.
 */
object RadioBackgroundPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "radio_background"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
