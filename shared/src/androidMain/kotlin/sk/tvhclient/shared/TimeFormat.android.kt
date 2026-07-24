package sk.tvhclient.shared

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vzor pre hodiny a minuty (M423).
 *
 * Zdielany modul nema pristup ku Context, preto sa hodnota nastavuje zvonku:
 * ClockPref v androidApp ju zapise pri starte (TvhApplication) a pri kazdej
 * zmene predvolby Hodiny (Automaticky / 24 hodin / 12 hodin).
 * Predvolena hodnota je 24-hodinova, aby sa nic nerozbilo, keby sa apply()
 * z akehokolvek dovodu nestihol zavolat.
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
