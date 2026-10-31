package sk.tvhclient.android

import android.content.Context

/** M406: size of the player's network buffer. A bigger buffer = more data ahead,
 *  so short wifi / mobile network dropouts are bridged without a stutter ("buffering").
 *  The price is higher latency behind live and slightly slower channel switching.
 *  MEDIUM (default) is a safe compromise for wifi and mobile alike; LARGE for weaker
 *  or fluctuating networks; SMALL for fast LANs, where the lowest latency matters. */
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

    /** Buffer depth in ms for the HTTP path (the server assembles a finished stream — it can be full). */
    fun ms(context: Context): Int = when (get(context)) {
        SMALL -> 1500     // the original behaviour — fast LAN, lowest latency
        LARGE -> 6000     // fluctuating wifi / mobile — maximum resilience
        // MEDIUM default — balanced for wifi and mobile data. M752 (performance audit P1): on a wired
        // (Ethernet) connection 1500 ms: Tvheadend sends live without an initial burst, so the first
        // picture waited for the whole 3.5 s on every channel switch; a LAN does not need the reserve.
        else -> if (onEthernet(context)) 1500 else 3500
    }

    /** M752: the active network is Ethernet (a wired TV box). Any doubt = false (the wifi value). */
    private fun onEthernet(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    /** M406-fix: buffer depth for the HTSP path (WE assemble the stream via TsMuxer).
     *  A big buffer here throws our PCR off and A/V drifts apart during ramp-up, so we keep
     *  more conservative values — still noticeably larger than the original 1500 ms
     *  (better resilience on wifi/mobile), but not so much that the remux floats away. */
    /** Buffer depth for the HTSP path (WE assemble the stream via TsMuxer). */
    fun htspMs(context: Context): Int = when (get(context)) {
        SMALL -> 1500
        LARGE -> 3000
        else -> 2200
    }
}
