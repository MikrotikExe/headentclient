package sk.tvhclient.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import sk.tvhclient.shared.TimeFormatConfig

/**
 * M679: time formatting for the player UI in one place (previously four almost
 * identical copies: fmtMs, fmtPos, fmtClock/fmtRange, hhmm).
 */

/** Duration / position in ms as h:mm:ss or m:ss; negative and zero = "0:00". */
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

/** Hours:minutes from unix time in seconds (per the 12/24 h setting); 0 = empty string. */
internal fun fmtClock(sec: Long): String =
    if (sec <= 0) "" else SimpleDateFormat(TimeFormatConfig.hm, Locale.getDefault()).format(Date(sec * 1000))

/** A "from - to" range from two unix times; if the end is missing, only the start is returned. */
internal fun fmtRange(a: Long, b: Long): String {
    val sa = fmtClock(a); val sb = fmtClock(b)
    return if (sa.isNotBlank() && sb.isNotBlank()) "$sa - $sb" else sa
}
