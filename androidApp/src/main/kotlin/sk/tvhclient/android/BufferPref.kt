package sk.tvhclient.android

import android.content.Context

/** M406: velkost sietoveho bufferu prehravaca. Vacsi buffer = viac dat dopredu,
 *  takze kratke vypadky wifi / mobilnej siete sa preklenu bez seku ("buffering").
 *  Cena je vyssia latencia od ziveho a mierne pomalsie prepnutie kanala.
 *  STREDNY (default) je bezpecny kompromis pre wifi aj mobil; VELKY pre slabsie
 *  alebo kolisave siete; MALY pre rychle LAN, kde ide o najnizsiu latenciu. */
object BufferPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "net_buffer"

    const val SMALL = "small"
    const val MEDIUM = "medium"
    const val LARGE = "large"
    val options = listOf(SMALL, MEDIUM, LARGE)

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, MEDIUM) ?: MEDIUM

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }

    /** Hlbka bufferu v ms pre libVLC (file-caching / network-caching). */
    fun ms(context: Context): Int = when (get(context)) {
        SMALL -> 1500     // povodne spravanie — rychla LAN, najnizsia latencia
        LARGE -> 6000     // kolisava wifi / mobil — max odolnost
        else -> 3500      // STREDNY default — vyvazene pre wifi aj mobilne data
    }
}
