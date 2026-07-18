package sk.tvhclient.android

import android.content.Context

/**
 * M391: reset per-server dat po zmene sposobu pripojenia (HTSP <-> HTTP).
 * Kazdy rezim ma vlastny priestor identifikatorov kanalov/EPG/archivu, takze
 * po prepnuti su vsetky ulozene id neplatne — stara cache by viedla na
 * HTTP 400 pri streame a pomiesane EPG (M390).
 *
 * Co sa maze: EPG cache (mriezka + live), posledny kanal / stanica,
 * playlist v pamati. HtspData a dataReload riesi ServersViewModel.save().
 * Oblubene / skryte kanaly / rodicovske zamky su tiez viazane na id — tie
 * nemazeme (pouzivatel by o ne prisiel), po prepnuti jednoducho prestanu
 * sediet a treba ich nastavit znova.
 */
object ServerDataReset {
    fun onConnectionModeChanged(ctx: Context, serverId: String) {
        EpgCache.clearAll(ctx, serverId)
        LastChannel.clear(ctx, serverId)
        LastRadio.clear(ctx, serverId)
        LivePlaylist.reset()
    }
}
