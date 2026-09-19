package sk.tvhclient.android

import androidx.compose.runtime.Immutable

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
