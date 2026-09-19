package sk.tvhclient.android

import android.content.Intent

/**
 * M658: všetky intent extra prehrávača na jednom mieste (vyclenené z PlayerActivity.onCreate).
 * Čistý držiak dát — číta sa raz cez [from], aktivita si z neho priradí lokály a polia
 * v pôvodnom poradí, takže správanie sa nemení. Konštanty kľúčov ostávajú
 * v `PlayerActivity.companion` (používajú ich aj volajúci).
 */
internal class PlayerArgs private constructor(
    /** Navrat na povodny zivy kanal po zatvoreni (pri "Prehrat od zaciatku" z prehravaca). */
    val returnLiveUuid: String?,
    val returnLiveTitle: String?,
    val channelUuid: String?,
    val channelTitle: String,
    val directUrl: String?,
    val playKind: String,
    val durationMs: Long,
    val progStart: Long,
    val progStop: Long,
    val progTitle: String,
    val dvrUuid: String?,
    val progStartFrac: Float,
    val progStopFrac: Float,
    val dvrRecording: Boolean,
    val dvrProgStartSec: Long,
    val dvrProgStopSec: Long,
    val dvrRealStartSec: Long,
    /** M605-fix: zoznam najprv (bez podmienky na počet kanálov — tú rieši aktivita). */
    val listFirst: Boolean
) {
    companion object {
        fun from(intent: Intent): PlayerArgs = PlayerArgs(
            returnLiveUuid = intent.getStringExtra(PlayerActivity.EXTRA_RETURN_UUID),
            returnLiveTitle = intent.getStringExtra(PlayerActivity.EXTRA_RETURN_TITLE),
            channelUuid = intent.getStringExtra(PlayerActivity.EXTRA_UUID),
            channelTitle = intent.getStringExtra(PlayerActivity.EXTRA_TITLE) ?: "",
            directUrl = intent.getStringExtra(PlayerActivity.EXTRA_URL),
            playKind = intent.getStringExtra(PlayerActivity.EXTRA_KIND) ?: "tv",
            durationMs = intent.getLongExtra(PlayerActivity.EXTRA_DURATION_MS, 0L),
            progStart = intent.getLongExtra(PlayerActivity.EXTRA_PROG_START, 0L),
            progStop = intent.getLongExtra(PlayerActivity.EXTRA_PROG_STOP, 0L),
            progTitle = intent.getStringExtra(PlayerActivity.EXTRA_PROG_TITLE) ?: "",
            dvrUuid = intent.getStringExtra(PlayerActivity.EXTRA_DVR_UUID),
            progStartFrac = intent.getFloatExtra(PlayerActivity.EXTRA_PROG_START_FRAC, 0f),
            progStopFrac = intent.getFloatExtra(PlayerActivity.EXTRA_PROG_STOP_FRAC, 1f),
            dvrRecording = intent.getBooleanExtra(PlayerActivity.EXTRA_DVR_RECORDING, false),
            dvrProgStartSec = intent.getLongExtra(PlayerActivity.EXTRA_DVR_PROG_START_SEC, 0L),
            dvrProgStopSec = intent.getLongExtra(PlayerActivity.EXTRA_DVR_PROG_STOP_SEC, 0L),
            dvrRealStartSec = intent.getLongExtra(PlayerActivity.EXTRA_DVR_REAL_START_SEC, 0L),
            listFirst = intent.getBooleanExtra(PlayerActivity.EXTRA_LIST_FIRST, false)
        )
    }
}
