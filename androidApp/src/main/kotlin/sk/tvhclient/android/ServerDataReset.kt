package sk.tvhclient.android

import android.content.Context

/**
 * M391: reset of per-server data after the connection method changes (HTSP <-> HTTP).
 * Each mode has its own namespace of channel/EPG/archive identifiers, so
 * after switching all stored ids are invalid — the old cache would lead to
 * HTTP 400 on the stream and mixed-up EPG (M390).
 *
 * What gets cleared: EPG cache (grid + live), last channel / station,
 * in-memory playlist. HtspData and dataReload are handled by ServersViewModel.save().
 * Favourites / hidden channels / parental locks are tied to ids too — we do not
 * clear those (the user would lose them), after switching they simply stop
 * matching and have to be set up again.
 */
object ServerDataReset {
    fun onConnectionModeChanged(ctx: Context, serverId: String) {
        EpgCache.clearAll(ctx, serverId)
        LastChannel.clear(ctx, serverId)
        LastRadio.clear(ctx, serverId)
        LivePlaylist.reset()
    }
}
