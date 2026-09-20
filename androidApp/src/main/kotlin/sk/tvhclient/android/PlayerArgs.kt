package sk.tvhclient.android

import android.content.Intent

/**
 * M658: all player intent extras in one place (extracted from PlayerActivity.onCreate).
 * A pure data holder — read once via [from], the activity assigns its locals and fields
 * from it in the original order, so behaviour does not change. The key constants stay
 * in `PlayerActivity.companion` (callers use them too).
 */
internal class PlayerArgs private constructor(
    /** Return to the original live channel after closing (for "Play from start" from the player). */
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
    /** M605-fix: list first (no condition on the channel count — the activity handles that). */
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
