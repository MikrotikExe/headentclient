package sk.tvhclient.android

import androidx.compose.runtime.Immutable
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * M666: the grouped parameters of [PlayerUi] — four small immutable holders, so that the
 * composable's signature is readable. The default values are the same as those the
 * original PlayerUi parameters had; behaviour does not change.
 */
/** The modern TV overlay (row/card/bar, exec, the "More" panel). */
@Immutable
internal data class ModernOverlayArgs(
    val visible: Boolean = false,
    val row: Int = 0,
    val card: Int = 0,
    val strip: Int = 0,
    val poke: Int = 0,
    val exec: Int = 0,
    val execId: String = "",
    val recNames: Set<String> = emptySet(),
    val stripIds: List<String> = emptyList(),
    val moreVisible: Boolean = false,
    val moreIndex: Int = 0,
    val onMorePick: (Int) -> Unit = {},
    val onMoreDismiss: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val moreIdList: List<String> = listOf("list", "sleep", "info")
)

/** Channel search in the list (M-search). */
@Immutable
internal data class ChannelSearchArgs(
    val active: Boolean = false,
    val query: String = "",
    val onQueryChange: (String) -> Unit = {},
    val fieldFocused: Boolean = true,
    val hits: List<LivePlaylist.LiveChannel> = emptyList(),
    val navIndex: Int = 0,
    val focusSignal: Int = 0
)

/** The parental PIN prompt and its D-pad grid. */
@Immutable
internal data class PinArgs(
    val prompt: Boolean = false,
    val len: Int = 0,
    val error: Boolean = false,
    val onDigit: (Int) -> Unit = {},
    val onBack: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onOpenList: () -> Unit = {},
    val gridRow: Int = 0,
    val gridCol: Int = 0
)

/** M383: the stream profile switch. */
@Immutable
internal data class ProfileArgs(
    val openSignal: Int = 0,
    val items: List<String> = emptyList(),
    val current: String = "",
    val switchAvailable: Boolean = false,
    val onPick: (String) -> Unit = {}
)

/** M667: DVR / seek — length, resume, scrub, seek callbacks. */
@Immutable
internal data class DvrSeekArgs(
    val knownDurationMs: Long,
    val resumeMs: Long = 0,
    val uuid: String? = null,
    val scrubFrac: Float = 0f,
    val recordingLive: Boolean = false,
    val recordingStopSec: Long = 0,
    val recordingOffsetMs: Long = 0,
    val onPlayheadMs: (Long) -> Unit = {},
    val seekSeedMs: Long = -1L,
    val onSeekSeedHandled: () -> Unit = {},
    val onSeekToMs: (Long) -> Unit = {},
    val resumeSel: Int = 1,
    val resumeAnswer: Int = 0,
    val onAskResumeChange: (Boolean) -> Unit = {},
    val onResumeAnswerHandled: () -> Unit = {},
    val onDoubleTapSeek: (Boolean) -> Unit = {},
    val onScrubSeek: (Int) -> Unit = {},
    val seekHint: Int = 0,
    val onSkipBack: () -> Unit = {},
    val onSkipFwd: () -> Unit = {}
)

/** M667: one-shot signals (poke/open/close) and navigation indices from the activity. */
@Immutable
internal data class UiSignals(
    val controlsPoke: Int = 0,
    val infoPoke: Int = 0,
    val zapPoke: Int = 0,
    val openList: Int = 0,
    val closeList: Int = 0,
    val openOptions: Int = 0,
    val closeOptions: Int = 0,
    val optionsNavIndex: Int = 0,
    val controlNavIndex: Int = 0,
    val trackNavIndex: Int = 0,
    val trackListVersion: Int = 0,
    val closeMenu: Int = 0,
    val openAudio: Int = 0,
    val openSpu: Int = 0,
    val lockTick: Int = 0,
    val numberEntry: String = ""
)

/** M675: the current/next programme on the overlay and the channel logo in the middle. */
@Immutable
internal data class ProgrammeArgs(
    val startFrac: Float = 0f,
    val stopFrac: Float = 1f,
    val startSec: Long = 0,
    val stopSec: Long = 0,
    val title: String = "",
    val nextTitle: String = "",
    val nextStart: Long = 0,
    val nextStop: Long = 0,
    val centerLogoUrl: String? = null
)

/** M675: the channel list (live zapping) — data, navigation and EPG callbacks. */
@Immutable
internal data class ChannelListArgs(
    val channels: List<LivePlaylist.LiveChannel> = emptyList(),
    val currentIndex: Int = -1,
    val onSelect: (Int) -> Unit = {},
    val onLongPress: (Int) -> Unit = {},
    val onLoadEpg: (String, (List<sk.tvhclient.shared.model.EpgEvent>) -> Unit) -> Unit = { _, _ -> },
    val navIndex: Int = -1,
    val groupLabel: String = "",
    val groupPicker: Boolean = false,
    val epgLoading: Boolean = false,
    val onOpenChange: (Boolean) -> Unit = {},
    val onRefreshEpg: () -> Unit = {},
    val onRefreshEpgInitial: () -> Unit = {},
    val onPrefetchEpg: () -> Unit = {}
)

/** M675: callbacks from PlayerUi into the activity (onAttach/onStart/onClose are mandatory). */
@Immutable
internal data class PlayerCallbacks(
    val onAttach: (VLCVideoLayout) -> Unit,
    val onStart: () -> Unit,
    val onPrevChannel: (() -> Unit)? = null,
    val onNextChannel: (() -> Unit)? = null,
    val onTogglePlay: () -> Unit = {},
    val onOpenEpg: () -> Unit = {},
    val onEnterPip: () -> Unit = {},
    val onOpenSleep: () -> Unit = {},
    val onTrackMenuChange: (String?) -> Unit = {},
    val onOptionsSelect: (Int) -> Unit = {},
    val onOptionsChange: (Boolean) -> Unit = {},
    val onControlsVisibleChange: (Boolean) -> Unit = {},
    val onOrientationLockChange: (Boolean) -> Unit = {},
    val onRequestExit: () -> Unit = {},
    val onClose: () -> Unit
)

/** M675: playback state flags (seekable is mandatory). */
@Immutable
internal data class PlaybackFlags(
    val seekable: Boolean,
    val timeshiftEngaged: Boolean = false,
    val timeshiftOffsetMs: Long = 0L,
    val tsMaxMs: Long = 0L,
    val inPip: Boolean = false,
    val pipSupported: Boolean = false,
    /**
     * The PiP button in the panel (M349-fix3): separate from pipSupported, which
     * gates the auto-PiP BackHandler and the exit-confirm (those need the capability,
     * not the visibility of the button)
     */
    val pipButton: Boolean = false,
    val hasVideo: Boolean = true,
    val reconnecting: Boolean = false,
    val seeking: Boolean = false,
    val playing: Boolean = true,
    val returnLiveOnBack: Boolean = false,
    val sleepDeadline: Long = 0
)
