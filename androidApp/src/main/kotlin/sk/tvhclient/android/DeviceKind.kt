package sk.tvhclient.android

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * M679: a single place for the question "are we running on a TV (leanback)?". The same
 * UiModeManager query used to be spelled out in 16 places (activities, composables, prefs) — on every
 * change (e.g. M537, when it turned out that TV MUST NOT be inferred from missing PiP) it had to be
 * hunted down everywhere. Returns true on Android TV / Google TV, false otherwise.
 */
internal fun isTvUiMode(ctx: Context): Boolean {
    val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return um?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * Android Automotive OS (a car's infotainment system). The app runs there as a parked video app:
 * the system hides it while driving, so playback must stop when the player is paused and must not
 * continue in the background (no PiP, no radio handoff to the service, no autostart).
 * See https://developer.android.com/training/cars/parked
 */
internal fun isAutomotive(ctx: Context): Boolean =
    ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
