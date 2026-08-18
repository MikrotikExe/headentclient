package sk.tvhclient.android

import android.content.Context
import android.content.Intent

/**
 * M494: zapamatanie toho, co sa prave prehravalo, aby sa po opatovnom spusteni
 * appky (typicky po vypnuti a zapnuti setoboxu) pokracovalo tam, kde pouzivatel
 * skoncil — zivy kanal alebo nahravka z archivu.
 *
 * Zamerne NEuklada poziciu v nahravke: tu uz vedie WatchProgress a prehravac sa
 * na obnovenie sam opyta. Tu ide len o to, CO otvorit.
 *
 * Zaznam sa zapisuje, kym prehravac bezi, a maze sa vo chvili, ked z neho
 * pouzivatel zamerne odide (BACK na zoznam, zatvorenie). Vdaka tomu sa stav
 * obnovi len vtedy, ked bol prehravac na obrazovke v momente vypnutia — ak
 * pouzivatel skoncil na zozname kanalov, otvori sa zoznam.
 *
 * Funkcia je urcena pre TV/Leanback; na telefone sa neuplatnuje.
 */
object LastPlayback {
    private const val PREFS = "app_prefs"
    private const val KEY_KIND = "lastpb_kind"          // "live" | "dvr"
    private const val KEY_SERVER = "lastpb_server"
    private const val KEY_UUID = "lastpb_uuid"          // kanal alebo DVR zaznam
    private const val KEY_TITLE = "lastpb_title"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Zapamataj beziaci ZIVY kanal. */
    fun setLive(c: Context, serverId: String?, channelUuid: String?, title: String?) {
        if (serverId == null || channelUuid.isNullOrBlank()) return
        prefs(c).edit()
            .putString(KEY_KIND, "live")
            .putString(KEY_SERVER, serverId)
            .putString(KEY_UUID, channelUuid)
            .putString(KEY_TITLE, title ?: "")
            .apply()
    }

    /** Zapamataj prehravanu NAHRAVKU z archivu. */
    fun setDvr(c: Context, serverId: String?, dvrUuid: String?, title: String?) {
        if (serverId == null || dvrUuid.isNullOrBlank()) return
        prefs(c).edit()
            .putString(KEY_KIND, "dvr")
            .putString(KEY_SERVER, serverId)
            .putString(KEY_UUID, dvrUuid)
            .putString(KEY_TITLE, title ?: "")
            .apply()
    }

    /** Pouzivatel odisiel z prehravaca — uz niet co obnovovat. */
    fun clear(c: Context) {
        prefs(c).edit()
            .remove(KEY_KIND).remove(KEY_SERVER).remove(KEY_UUID).remove(KEY_TITLE)
            .apply()
    }

    /** M496: poziadavka na obnovenie, ktoru vykona UI (nie Activity v onCreate). */
    class Restore(val kind: String, val intent: android.content.Intent?)

    @Volatile
    var pendingRestore: Restore? = null

    /**
     * M496: uuid kanala, ktory sa ma pustit po nacitani zoznamu kanalov.
     * Zivy kanal sa nesmie otvarat priamo — prehravac by nemal LivePlaylist a
     * nedalo by sa prepinat.
     */
    @Volatile
    var restoreLiveUuid: String? = null

    /** Priprav obnovenie; vykona ho UI, ktore vie pockat na nacitanie kanalov. */
    fun prepareRestore(c: Context, activeServerId: String?) {
        if (activeServerId == null) return
        val p = prefs(c)
        val kind = p.getString(KEY_KIND, null) ?: return
        if (p.getString(KEY_SERVER, null) != activeServerId) return
        val uuid = p.getString(KEY_UUID, null)?.takeIf { it.isNotBlank() } ?: return
        when (kind) {
            "live" -> {
                restoreLiveUuid = uuid
                pendingRestore = Restore("live", null)
            }
            "dvr" -> {
                val i = restoreIntent(c, activeServerId) ?: return
                pendingRestore = Restore("dvr", i)
            }
        }
    }

    /**
     * Intent na obnovenie prehravania, alebo null ak nie je co obnovit.
     *
     * Vrati null aj vtedy, ked medzitym pribudol/ubudol server alebo sa prepol
     * aktivny — obnovovat kanal z ineho servera nema zmysel a uuid by aj tak
     * neplatilo. Kanal chraneny rodicovskym zamkom si vypyta PIN rovnako ako
     * pri beznom otvoreni.
     */
    fun restoreIntent(c: Context, activeServerId: String?): Intent? {
        if (activeServerId == null) return null
        val p = prefs(c)
        val kind = p.getString(KEY_KIND, null) ?: return null
        if (p.getString(KEY_SERVER, null) != activeServerId) return null
        val uuid = p.getString(KEY_UUID, null)?.takeIf { it.isNotBlank() } ?: return null
        val title = p.getString(KEY_TITLE, "") ?: ""

        return when (kind) {
            "live" -> Intent(c, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_UUID, uuid)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(
                    PlayerActivity.EXTRA_REQUIRE_PIN,
                    ParentalLock.channelNeedsPin(c, activeServerId, uuid)
                )
            }
            "dvr" -> {
                val srv = sk.tvhclient.shared.Tvh.store.active() ?: return null
                Intent(c, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_URL, sk.tvhclient.shared.Tvh.dvrUrl(srv, uuid))
                    putExtra(PlayerActivity.EXTRA_TITLE, title)
                    putExtra(PlayerActivity.EXTRA_DVR_UUID, uuid)
                }
            }
            else -> null
        }
    }
}

/**
 * M494: obnovit po spusteni appky posledne prehravanie? Len TV/Leanback.
 *
 * Predvolene zapnute — na setoboxe je ocakavane, ze sa po zapnuti vratis tam,
 * kde si skoncil. Kto to nechce, vypne si to v nastaveniach.
 */
object ResumeLastPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "resume_last_playback"

    fun get(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
