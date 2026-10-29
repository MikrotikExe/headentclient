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

    /**
     * M738 (audit B21): a server was deleted — everything kept under its id goes too. Until now the
     * EPG files (tens of MB on a large server), favourites, hidden channels, parental locks, the last
     * channel / station / group, per-channel audio choices, watch positions and the codec memo stayed
     * for ever (a new server always gets a new id, so nothing of it could ever match again).
     * Deliberately NOT called by a backup import, which deletes and re-adds the same ids.
     */
    fun onServerRemoved(ctx: Context, serverId: String) {
        val app = ctx.applicationContext
        EpgCache.clearAll(app, serverId)
        LastChannel.clear(app, serverId)
        LastRadio.clear(app, serverId)
        DvrProfileAsk.clear(serverId)
        PiconImageLoader.invalidate()
        removeKeys(app, "app_prefs") { k ->
            k == "hidden_channels_$serverId" || k == "plock_channels_$serverId" ||
                k == "lasttag_tv_$serverId" || k == "lasttag_radio_$serverId"
        }
        removeKeys(app, "favorites") { it == "fav:$serverId" }
        removeKeys(app, "channel_prefs") { it.startsWith("aud:$serverId:") }
        removeKeys(app, "watch_progress") { it.startsWith("wp:$serverId:") }
        removeKeys(app, "codec_memo") { it.startsWith("$serverId:") }
        if (app.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getString("lastpb_server", null) == serverId) {
            LastPlayback.clear(app)
        }
    }

    private fun removeKeys(ctx: Context, file: String, match: (String) -> Boolean) {
        runCatching {
            val sp = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
            val keys = sp.all.keys.filter(match)
            if (keys.isNotEmpty()) sp.edit().apply { keys.forEach { remove(it) } }.apply()
        }
    }
}
