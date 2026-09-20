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

actual fun formatTimeHm(epochSec: Long): String =
    SimpleDateFormat(TimeFormatConfig.hm, Locale.getDefault()).format(Date(epochSec * 1000))

actual fun formatDayLabel(epochSec: Long): String =
    SimpleDateFormat("EEEE d.M.", Locale.getDefault()).format(Date(epochSec * 1000))
        .replaceFirstChar { it.uppercase() }

actual fun dateKey(epochSec: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(epochSec * 1000))

actual fun formatDateFull(epochSec: Long): String =
    java.text.SimpleDateFormat("d.M.yyyy", java.util.Locale.getDefault()).format(java.util.Date(epochSec * 1000))
