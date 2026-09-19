package sk.tvhclient.android

import android.content.Context

/**
 * M623: "Rádio hrá na pozadí" (telefón aj TV, predvolene vypnuté).
 *
 * Zapnuté: keď prehrávač rádia ide na pozadie (zhasnutie, zámok, domovská
 * obrazovka, iná appka/prehliadač), rádio hrá ďalej — telefón (moderný aj
 * klasický režim, M624) cez RadioPlayerService s notifikáciou a mini lištou,
 * TV ostane hrať samotný prehrávač (drží wake/wifi lock z M452).
 * V samotnom prehrávači obrazovka svieti ďalej (KEEP_SCREEN_ON ako pri TV) —
 * rádio na pozadí je len pre čas mimo prehrávača.
 *
 * Vypnuté (predvolené): pôvodné správanie — odchod na pozadie rádio pozastaví.
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
