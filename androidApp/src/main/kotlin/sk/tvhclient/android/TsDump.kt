package sk.tvhclient.android

import android.content.Context
import java.io.File

/**
 * M462-diag: jednorazovy odber vzorky nasho TS vystupu.
 *
 * Sluzi na porovnanie s tym, co posiela Tvheadend cez HTTP (profil pass) —
 * ten isty kanal, ta ista chvila. Z rozdielu v strukture (parameter sety pri
 * klucovych snimkoch, deskriptory v PMT, stream type) sa da zistit, preco
 * libVLC nas stream pri HEVC nezvlada, hoci serverovy ano.
 *
 * Docasna diagnostika — po vyrieseni ide von.
 */
object TsDump {
    const val MAX_BYTES = 12L * 1024 * 1024   // ~8 s HD streamu

    @Volatile
    private var pending: File? = null

    /** Naplanuje odber pri najblizsom spusteni kanala. */
    fun arm(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        pending = File(dir, "sample.ts")
    }

    /** Feeder si vyzdvihne ciel (a zaroven odber deaktivuje). */
    fun take(): File? {
        val f = pending
        pending = null
        return f
    }
}
