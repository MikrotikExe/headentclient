package sk.tvhclient.android

import android.content.Context
import sk.tvhclient.shared.api.ChannelRow

/**
 * M602: „Číslovať rádiá od 1".
 *
 * Rozhlasové stanice dostavaju od servera cisla, ktore nikto nepouziva (634, 635…),
 * kym pri TV kanaloch su zauzivane. Predvolene sa preto radia cisluju 1…n podla
 * poradia v prave zobrazenej skupine — v zalozke Radia, v mriezke TV programu aj
 * v zozname stanic v prehravaci (a podla toho ide aj volba cislicami). Serverove
 * cisla ostavaju nedotknute; kto ich chce vidiet, volbu vypne.
 */
object RadioNumberingPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "radio_number_from_one"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    /** Precisluje zoznam 1…n (bez zmeny poradia), ak je volba zapnuta. */
    fun apply(context: Context, rows: List<ChannelRow>): List<ChannelRow> =
        if (!get(context)) rows
        else rows.mapIndexed { i, r -> r.copy(channel = r.channel.copy(number = i + 1)) }
}
