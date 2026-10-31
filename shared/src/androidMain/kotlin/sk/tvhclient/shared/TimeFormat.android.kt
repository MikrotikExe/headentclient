package sk.tvhclient.shared

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The pattern for hours and minutes (M423).
 *
 * The shared module has no access to a Context, so the value is set from outside:
 * ClockPref in androidApp writes it at start-up (TvhApplication) and on every
 * change of the Clock preference (Automatic / 24 hours / 12 hours).
 * The default value is the 24-hour one, so that nothing breaks if apply()
 * for any reason does not get called in time.
 */
object TimeFormatConfig {
    @Volatile
    var hm: String = "HH:mm"
}

/**
 * M743 (Play vitals 1.0.6: ANR in formatTimeHm, many ANRs waiting for GC): a new SimpleDateFormat
 * per call — the EPG grid, lists and tickers format thousands of times, each building a formatter
 * (calendar, symbols, compiled pattern) and that garbage kept the GC busy on the main thread.
 * One formatter per thread, pattern and locale is reused. SimpleDateFormat is not thread-safe,
 * hence the ThreadLocal; the time zone is applied on every call, because a formatter keeps the
 * zone it was created with and the user (or DST settings) may change it while the app runs.
 */
object DateFmtCache {
    private val cache = object : ThreadLocal<HashMap<String, SimpleDateFormat>>() {
        override fun initialValue() = HashMap<String, SimpleDateFormat>()
    }

    fun format(pattern: String, locale: Locale, epochMs: Long): String {
        val map = cache.get()!!
        val key = pattern + "|" + locale.toString()
        val f = map[key] ?: SimpleDateFormat(pattern, locale).also {
            if (map.size > 32) map.clear()
            map[key] = it
        }
        f.timeZone = java.util.TimeZone.getDefault()
        return f.format(Date(epochMs))
    }
}

actual fun formatTimeHm(epochSec: Long): String =
    DateFmtCache.format(TimeFormatConfig.hm, Locale.getDefault(), epochSec * 1000)

actual fun formatDayLabel(epochSec: Long): String =
    DateFmtCache.format("EEEE d.M.", Locale.getDefault(), epochSec * 1000)
        .replaceFirstChar { it.uppercase() }

actual fun dateKey(epochSec: Long): String =
    DateFmtCache.format("yyyy-MM-dd", Locale.US, epochSec * 1000)

actual fun formatDateFull(epochSec: Long): String =
    DateFmtCache.format("d.M.yyyy", Locale.getDefault(), epochSec * 1000)
