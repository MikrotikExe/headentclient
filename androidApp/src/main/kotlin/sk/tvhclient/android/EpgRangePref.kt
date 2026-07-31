package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Rozsah EPG v mriezke:
 *  - daysBack: kolko dni dozadu si appka pamata EPG (lokalny cache, lebo Tvheadend
 *    stare relacie z EPG zahadzuje) — 1..7, predvolene 7
 *  - daysForward: kolko dni dopredu sa zobrazuje/nacitava EPG — 1..7, predvolene 6
 * Drzime aj zivy stav (MutableState), aby sa zmena prejavila v mriezke hned.
 */
object EpgRangePref {
    private const val PREFS = "app_prefs"
    private const val KEY_BACK = "epg_days_back"
    private const val KEY_FWD = "epg_days_forward"

    const val DEFAULT_BACK = 7
    const val DEFAULT_FWD = 6

    /** M445: predvolby pre zariadenia s malou haldou (Xiaomi Mi Box a spol.).
     *  7+6 dni EPG pri velkej ponuke kanalov tam vyhodi OutOfMemoryError uz pri
     *  nacitavani. Plati LEN ako predvolena hodnota — kto si rozsah nastavi
     *  rucne, ten ho ma; a kto ma silny box, dostane povodnych 7+6. */
    const val LOW_RAM_BACK = 2
    const val LOW_RAM_FWD = 2

    private fun lowRam(c: Context): Boolean = runCatching {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.isLowRamDevice || am.memoryClass <= 128
    }.getOrDefault(false)

    private fun defBack(c: Context) = if (lowRam(c)) LOW_RAM_BACK else DEFAULT_BACK
    private fun defFwd(c: Context) = if (lowRam(c)) LOW_RAM_FWD else DEFAULT_FWD

    /** Volby pre rozbalovacie pole (1..7) ako stringy. */
    val dayOptions: List<String> = (1..7).map { it.toString() }

    private var backState: MutableState<Int>? = null
    private var fwdState: MutableState<Int>? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun backStateOf(c: Context): MutableState<Int> =
        backState ?: mutableStateOf(prefs(c).getInt(KEY_BACK, defBack(c)).coerceIn(1, 7))
            .also { backState = it }

    fun fwdStateOf(c: Context): MutableState<Int> =
        fwdState ?: mutableStateOf(prefs(c).getInt(KEY_FWD, defFwd(c)).coerceIn(1, 7))
            .also { fwdState = it }

    fun daysBack(c: Context): Int = backStateOf(c).value
    fun daysForward(c: Context): Int = fwdStateOf(c).value

    fun setBack(c: Context, v: Int) {
        val x = v.coerceIn(1, 7)
        backStateOf(c).value = x
        prefs(c).edit().putInt(KEY_BACK, x).apply()
    }

    fun setForward(c: Context, v: Int) {
        val x = v.coerceIn(1, 7)
        fwdStateOf(c).value = x
        prefs(c).edit().putInt(KEY_FWD, x).apply()
    }
}
