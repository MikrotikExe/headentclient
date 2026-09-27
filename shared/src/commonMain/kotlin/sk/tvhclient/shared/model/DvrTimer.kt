package sk.tvhclient.shared.model

/**
 * M696: recording rules on the server — what Kodi calls "timer rules".
 *
 * Tvheadend has two kinds:
 *  - [DvrAutorec]: "record by EPG" — every programme whose title matches [title] (optionally on one
 *    channel, on some weekdays, starting around a time) is scheduled automatically.
 *  - [DvrTimerec]: "record by time" — a fixed start/stop time on given weekdays, EPG or not.
 *
 * Both paths (HTSP and HTTP) are mapped into these two shapes, so the UI is the same for both.
 * Fields the app does not edit (priority, retention, directory, min/max duration, fulltext...) are
 * left to the server's defaults and to the Tvheadend web UI.
 *
 * [id]: HTSP and HTTP both use the entry's uuid string.
 * [channelUuid]: "" = any channel; otherwise the app's channel uuid (HTSP: the numeric channelId
 * as a string, HTTP: the hex uuid) — the same identity the channel list uses.
 * [daysOfWeek]: bitmask, bit 0 = Monday ... bit 6 = Sunday (Tvheadend's `daysOfWeek`);
 * [ALL_DAYS] = every day.
 */
data class DvrAutorec(
    val id: String = "",
    val enabled: Boolean = true,
    /** A short label of the rule; empty on the server means "same as title". */
    val name: String = "",
    /** The title (or a part of it) the programmes must match. */
    val title: String = "",
    val channelUuid: String = "",
    val daysOfWeek: Int = ALL_DAYS,
    /**
     * Minutes after midnight the programme should start around; -1 = any time.
     * M700: Tvheadend stores two times of day, "start after" (`start`) and "start before"
     * (`start_window`) — not a duration. The app shows their midpoint and writes ±15 min around it,
     * which is exactly what HTSP `approxTime` does on add ([autorecAround] / [autorecWindow]).
     */
    val startMin: Int = -1,
    /** Tvheadend's "start before" time of day as read from the server; -1 = none. */
    val startWindowMin: Int = -1,
    /** Duplicate handling — Tvheadend `record` / HTSP `dupDetect`, see [DupDetect]. */
    val dupDetect: Int = DupDetect.ALL,
    /** DVR profile: the app stores the profile NAME (as with recordEvent); "" = server default. */
    val configName: String = "",
    val comment: String = "",
    /** M704: the EPG series link; when set, the server matches episodes by it and ignores the title. */
    val serieslink: String = "",
    /** M705: a sub-folder of the server's recording directory ("" = as the DVR profile says). */
    val directory: String = ""
) {
    companion object {
        const val ALL_DAYS = 0x7F
    }
}

data class DvrTimerec(
    val id: String = "",
    val enabled: Boolean = true,
    val name: String = "",
    /** Title given to the recordings; Tvheadend supports a format string here (default "Time-%F_%R"). */
    val title: String = "",
    val channelUuid: String = "",
    val daysOfWeek: Int = DvrAutorec.ALL_DAYS,
    /** Minutes after midnight. */
    val startMin: Int = 0,
    val stopMin: Int = 0,
    val configName: String = "",
    val comment: String = "",
    /** M705: a sub-folder of the server's recording directory ("" = as the DVR profile says). */
    val directory: String = ""
)

/**
 * Tvheadend's `dvr_autorec_dedup_t` (dvr.h). The app offers only the four everyday choices;
 * other values coming from the server are kept as they are.
 */
object DupDetect {
    const val ALL = 0
    const val DIFFERENT_EPISODE_NUMBER = 1
    const val DIFFERENT_SUBTITLE = 2
    const val DIFFERENT_DESCRIPTION = 3
    const val ONCE_PER_WEEK = 4
    const val ONCE_PER_DAY = 5
    const val UNIQUE = 14   // "unique" = all of the above (new episodes only)
}

/** Bit for a weekday, 1 = Monday ... 7 = Sunday (ISO / Tvheadend numbering). */
fun weekdayBit(isoDay: Int): Int = 1 shl (isoDay - 1)

/** Tvheadend's `weekdays` list (1..7) -> bitmask. */
fun weekdaysToMask(days: Collection<Int>): Int {
    var m = 0
    for (d in days) if (d in 1..7) m = m or weekdayBit(d)
    return m
}

/** Bitmask -> Tvheadend's `weekdays` list (1..7). */
fun maskToWeekdays(mask: Int): List<Int> = (1..7).filter { mask and weekdayBit(it) != 0 }

/** "HH:MM" -> minutes after midnight; anything that is not a time (e.g. "Any") -> -1. */
fun parseHm(s: String?): Int {
    if (s.isNullOrBlank() || !s[0].isDigit()) return -1
    val h = s.substringBefore(':').toIntOrNull() ?: return -1
    val m = s.substringAfter(':', "0").toIntOrNull() ?: 0
    val t = h * 60 + m
    return if (t in 0 until 24 * 60) t else -1
}

/** Minutes after midnight -> "HH:MM"; -1 -> "". */
fun formatHm(min: Int): String {
    if (min < 0) return ""
    val h = min / 60
    val m = min % 60
    return (if (h < 10) "0$h" else "$h") + ":" + (if (m < 10) "0$m" else "$m")
}

/** M700: "start after" + "start before" (times of day, may wrap past midnight) -> the midpoint; -1 = any. */
fun autorecAround(start: Int, window: Int): Int {
    if (start < 0 || window < 0) return -1
    var w = window
    if (w < start) w += 24 * 60
    return ((start + w) / 2) % (24 * 60)
}

/** M700: the midpoint -> ("start after", "start before") = ±15 min, like HTSP approxTime; (-1, -1) = any. */
fun autorecWindow(around: Int): Pair<Int, Int> {
    if (around < 0) return -1 to -1
    val day = 24 * 60
    return ((around - 15 + day) % day) to ((around + 15) % day)
}

/**
 * M704: the series title of an episode — trailing episode markers removed, so that a title-based
 * "Record series" rule matches the other episodes too ("Show VI (17)" -> "Show VI"). Only markers
 * that are clearly episode numbers are removed; a title that would become empty stays as it was.
 */
fun seriesTitle(title: String): String {
    val patterns = listOf(
        Regex("""\s*\(\s*\d{1,3}\s*(/\s*\d{1,3})?\s*\)\s*$"""),              // (17)  (17/40); not a year (2019)
        Regex("""\s*\[\s*\d{1,4}\s*(/\s*\d{1,4})?\s*\]\s*$"""),              // [17]
        Regex("""\s*\(?\s*[Ss]\d{1,2}\s*[Ee]\d{1,3}\s*\)?\s*$"""),            // S01E05  (S1 E5)
        Regex("""\s*[-–:,]?\s*\d{1,4}\s*\.?\s*(časť|čast|diel|díl|část|epizóda|epizoda|ep\.?|folge|teil|odc\.?|rész)\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-–:,]?\s*(časť|diel|díl|část|epizóda|epizoda|ep\.?|folge|teil)\s*\d{1,4}\s*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*[-–:,]\s*\d{1,4}\s*$"""),                                  // "Show - 17"
    )
    var t = title.trim()
    while (true) {
        val before = t
        for (p in patterns) t = t.replace(p, "").trim()
        if (t == before) break
    }
    return t.ifBlank { title.trim() }
}

/** M704: [s] as a literal inside Tvheadend's title regex (it matches case-insensitively, anywhere). */
fun regexLiteral(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        if (c in ".^$*+?()[]{}|\\") sb.append('\\')
        sb.append(c)
    }
    return sb.toString()
}
