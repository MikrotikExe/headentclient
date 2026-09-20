package sk.tvhclient.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sk.tvhclient.shared.TimeFormatConfig

/**
 * M679: formatovanie casu pre UI prehravaca na jednom mieste (predtym styri takmer
 * zhodne kopie: fmtMs, fmtPos, fmtClock/fmtRange, hhmm).
 */

/** Dlzka / pozicia v ms ako h:mm:ss alebo m:ss; zaporne a nula = „0:00". */
internal fun fmtMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "$h:" + m.toString().padStart(2, '0') + ":" + s.toString().padStart(2, '0')
    } else {
        "$m:" + s.toString().padStart(2, '0')
    }
}

/** Hodiny:minuty z unixoveho casu v sekundach (podla nastavenia 12/24 h); 0 = prazdny retazec. */
internal fun fmtClock(sec: Long): String =
    if (sec <= 0) "" else SimpleDateFormat(TimeFormatConfig.hm, Locale.getDefault()).format(Date(sec * 1000))

/** Rozsah „od - do" z dvoch unixovych casov; ak koniec chyba, vrati len zaciatok. */
internal fun fmtRange(a: Long, b: Long): String {
    val sa = fmtClock(a); val sb = fmtClock(b)
    return if (sa.isNotBlank() && sb.isNotBlank()) "$sa - $sb" else sa
}
