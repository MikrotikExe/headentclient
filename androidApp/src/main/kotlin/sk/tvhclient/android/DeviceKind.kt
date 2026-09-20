package sk.tvhclient.android

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/**
 * M679: jedno miesto pre otazku „bezime na TV (leanback)?". Ten isty dotaz na UiModeManager
 * bol predtym rozpisany na 16 miestach (aktivity, composables, prefs) — pri kazdej zmene
 * (napr. M537, ked sa ukazalo, ze TV sa NESMIE odvodzovat z chybajuceho PiP) sa musel
 * hladat vsade. Vracia true na Android TV / Google TV, inak false.
 */
internal fun isTvUiMode(ctx: Context): Boolean {
    val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return um?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
