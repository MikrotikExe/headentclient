package sk.tvhclient.android

import androidx.compose.runtime.Immutable
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * M666: zoskupené parametre [PlayerUi] — štyri malé nemenné držiaky, aby bola
 * signatúra composable čitateľná. Predvolené hodnoty sú tie isté, aké mali
 * pôvodné parametre PlayerUi; správanie sa nemení.
 */
/** Moderný TV overlay (riadok/karta/pás, exec, panel "Viac"). */
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

/** Vyhľadávanie kanálov v zozname (M-search). */
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

/** Rodičovský PIN prompt a jeho D-pad mriežka. */
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

/** M383: prepínač stream profilu. */
@Immutable
internal data class ProfileArgs(
    val openSignal: Int = 0,
    val items: List<String> = emptyList(),
    val current: String = "",
    val switchAvailable: Boolean = false,
    val onPick: (String) -> Unit = {}
)

/** M667: DVR / seek — dĺžka, resume, scrub, seek callbacky. */
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

/** M667: jednorazové signály (poke/open/close) a navigačné indexy z aktivity. */
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

/** M675: aktuálna/nasledujúca relácia na overlayi a logo kanála v strede. */
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

/** M675: zoznam kanálov (live zapping) — dáta, navigácia a EPG callbacky. */
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

/** M675: callbacky z PlayerUi do aktivity (onAttach/onStart/onClose sú povinné). */
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

/** M675: príznaky stavu prehrávania (seekable je povinný). */
@Immutable
internal data class PlaybackFlags(
    val seekable: Boolean,
    val timeshiftEngaged: Boolean = false,
    val timeshiftOffsetMs: Long = 0L,
    val tsMaxMs: Long = 0L,
    val inPip: Boolean = false,
    val pipSupported: Boolean = false,
    /**
     * PiP tlacidlo v paneli (M349-fix3): oddelene od pipSupported, ktory
     * gate-uje auto-PiP BackHandler a exit-confirm (tie potrebuju schopnost,
     * nie viditelnost tlacidla)
     */
    val pipButton: Boolean = false,
    val hasVideo: Boolean = true,
    val reconnecting: Boolean = false,
    val seeking: Boolean = false,
    val playing: Boolean = true,
    val returnLiveOnBack: Boolean = false,
    val sleepDeadline: Long = 0
)
