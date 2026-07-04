package sk.tvhclient.android

import android.content.Context

/** Automaticka obnovovacia frekvencia (AFR, M346): prehravac prepne rezim
 *  displeja podla snimkovej frekvencie streamu (25/50 fps vysielanie -> 50 Hz),
 *  cim zmizne trhanie obrazu (judder) na 60 Hz paneloch. Predvolene ZAPNUTE
 *  (M347-fix) — kto nechce kratke stmavnutie pri prepnuti, vypne si to. */
object AfrPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "afr_enabled"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
