package sk.tvhclient.android

import android.content.Context

/**
 * M447: forced software video decoding.
 *
 * OFF by default — the app uses the device's hardware decoder (the correct
 * behaviour on the vast majority of boxes). It is switched on where the HW decoder
 * is broken: e.g. the Xiaomi Mi Box S (Android 9, old Amlogic OMX) stutters on
 * 10-bit HEVC at one-second intervals, while H.264 at a higher bitrate plays
 * smoothly. Software decoding takes more CPU (1080p H.264/HEVC 8-bit is
 * manageable for ordinary boxes), which is why it does not switch itself on.
 */
object SwDecodePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "sw_decode"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
