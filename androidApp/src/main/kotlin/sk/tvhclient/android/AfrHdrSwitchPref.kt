package sk.tvhclient.android

import android.content.Context

/**
 * Switching to HDR during AFR (TV/box only). A hard display-mode switch
 * (preferredDisplayModeId) triggers an HDMI re-negotiation and the firmware of some
 * boxes switches the output to HDR during it (following its own HDR policy, the app
 * cannot forbid it directly). When this switch is OFF, AFR skips the hard switch
 * and asks for the frame-rate change only the smooth way
 * (Surface.setFrameRate, without a re-sync) — no black screen, no
 * HDR flip; whether the panel really changes the frame rate is up to the system.
 *
 * M500: default OFF. A hard mode switch is unpredictable on various boxes
 * (black screen on every frame-rate change, a flip to HDR even with
 * SDR content) and the app cannot see into the firmware's HDR policy. Whoever wants it
 * turns it on themselves in Settings next to AFR.
 */
object AfrHdrSwitchPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "afr_hdr_switch"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)   // M500: off by default

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
    }
}
