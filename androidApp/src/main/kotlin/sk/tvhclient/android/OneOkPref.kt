package sk.tvhclient.android

import android.content.Context

/**
 * M596: „Prepnúť kanál jedným OK".
 *
 * V zozname kanálov v prehrávači sa kanál bežne prepne až po UVOĽNENÍ tlačidla OK
 * (podržanie OK je kontextové menu) a pri práve nahrávanom kanáli sa ešte pýta
 * „naživo / od začiatku". Niektoré diaľkové ovládače (IR/CEC) posielajú OK tak,
 * že to používateľ vníma ako potrebu stlačiť OK dvakrát.
 *
 * Ked je voľba zapnutá, OK v zozname prepne kanál hned pri stlačení, bez otázky
 * na archív a s kratším ochranným oknom po otvorení zoznamu. Platí rovnako pre
 * moderný aj klasický režim. Predvolene vypnuté (pôvodné správanie).
 */
object OneOkPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "one_ok_switch"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
