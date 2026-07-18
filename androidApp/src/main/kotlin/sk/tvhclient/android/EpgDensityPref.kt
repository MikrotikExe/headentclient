package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * M388: hustota EPG mriezky na telefone (<600dp).
 *  - true (predvolene) = kompaktna: 1 min = 3 dp, nizsie riadky, uzsi stlpec kanala
 *  - false = komfortna: povodne rozmery z M387
 * Na sirokych obrazovkach (TV/tablet) sa hodnota ignoruje.
 * Drzime aj zivy stav (MutableState), aby sa prepnutie prejavilo hned.
 */
object EpgDensityPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "epg_compact"

    private var state: MutableState<Boolean>? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun compactStateOf(c: Context): MutableState<Boolean> =
        state ?: mutableStateOf(prefs(c).getBoolean(KEY, true)).also { state = it }

    fun set(c: Context, v: Boolean) {
        compactStateOf(c).value = v
        prefs(c).edit().putBoolean(KEY, v).apply()
    }
}
