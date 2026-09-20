package sk.tvhclient.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/*
 * M662: EPG effects (prefetch + periodic refresh while the list/bar is open) extracted
 * from PlayerUi (PlayerActivity.kt) — the 64 kB method limit. Bodies 1:1; the caller holds the state.
 */

/** Prefetching the EPG after start and periodic refresh while the list / bar / controls are open. */
@Composable
internal fun PlayerEpgEffects(
    showChannelList: Boolean,
    controlsVisible: Boolean,
    modernOvVisible: Boolean,
    onPrefetchEpg: () -> Unit,
    onRefreshEpgInitial: () -> Unit,
    onRefreshEpg: () -> Unit
) {
    // M266: prefetch the EPG (now/next) in the background shortly after the player starts,
    // so that the first opened channel list already has data from the cache (epgUpcomingState) without a network
    // wait. Runs on IO (refreshOverlayEpg), the stream comes up first and the UI is not blocked.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1200)
        onPrefetchEpg()   // M274: refresh only if the cache is empty/stale
    }

    // While the channel list is open, refresh the EPG (now/next) so that programmes
    // gradually roll over to the next ones
    // M522: refresh the EPG and the recording flag (red dot) while the full
    // channel list OR the horizontal bar is open. So far this only held for the full list,
    // so in the bar the dots appeared late or not at all — and the state of the recording
    // button in "More" was just as unreliable.
    // One effect instead of two: PlayerUi is just under the 64 KB method limit.
    // M525: the MODERN BAR too (modernOvVisible) — it is not governed by `controlsVisible`,
    // so the condition from M522 did not apply to it at all and the red dots in it
    // only came up after the big channel list had fetched them.
    LaunchedEffect(showChannelList || controlsVisible || modernOvVisible) {
        if (showChannelList || controlsVisible || modernOvVisible) {
            onRefreshEpgInitial()   // M270: first load with a spinner (only if the cache is empty/stale)
            while (true) {
                kotlinx.coroutines.delay(60_000)
                onRefreshEpg()      // periodic refresh without a spinner
            }
        }
    }
}
