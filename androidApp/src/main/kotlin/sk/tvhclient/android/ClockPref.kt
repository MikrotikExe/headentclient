package sk.tvhclient.android

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import sk.tvhclient.shared.TimeFormatConfig

/**
 * Format hodin v celej appke (M423) — vyziadane v GitHub issue #2.
 *  - AUTO: podla systemoveho nastavenia zariadenia — predvolene
 *  - H24:  vzdy 24-hodinovy (13:45)
 *  - H12:  vzdy 12-hodinovy (1:45 PM)
 *
 * Rovnaky vzor ako ThemePref: zivy stav (MutableState), aby sa cas prepisal
 * hned po zmene v nastaveniach, bez restartu. Popisky AM/PM si SimpleDateFormat
 * lokalizuje sam podla Locale, nepiseme ich rucne.
 */
object ClockPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "clock_format"

    const val AUTO = "auto"
    const val H24 = "h24"
    const val H12 = "h12"

    val options = listOf(AUTO, H24, H12)

    private var state: MutableState<String>? = null

    /**
     * Revizia — zvysi sa, ked systemove nastavenie 12/24 zmeni pouzivatel
     * pocas behu appky. Compose ju cita v hm(), takze sa cas prekresli
     * bez restartu. Samotna volba v SharedPreferences sa nemeni.
     */
    private val revision: MutableIntState = mutableIntStateOf(0)

    private fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO

    /** Zivy stav volby — citanim .value v @Composable sa cas prekresli pri zmene. */
    fun stateOf(context: Context): MutableState<String> =
        state ?: mutableStateOf(load(context)).also { state = it }

    fun get(context: Context): String = stateOf(context).value

    fun set(context: Context, value: String) {
        stateOf(context).value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
        apply(context)
    }

    /** true = 24-hodinovy format. Pri AUTO sa pytame systemu. */
    fun is24(context: Context): Boolean = when (get(context)) {
        H24 -> true
        H12 -> false
        else -> DateFormat.is24HourFormat(context)
    }

    /** Vzor pre SimpleDateFormat. */
    fun hm(context: Context): String {
        revision.intValue          // odber: prekresli sa aj pri zmene v systeme
        return if (is24(context)) "HH:mm" else "h:mm a"
    }

    /** Volane pri ACTION_TIME_CHANGED — system zmenil 12/24. */
    fun onSystemFormatChanged(context: Context) {
        apply(context)
        revision.intValue++
    }

    /** Prenesie aktualnu volbu do zdielaneho modulu, ktory nema Context. */
    fun apply(context: Context) {
        TimeFormatConfig.hm = hm(context)
    }
}
