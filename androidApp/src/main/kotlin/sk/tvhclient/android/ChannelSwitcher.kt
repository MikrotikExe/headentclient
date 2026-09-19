package sk.tvhclient.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.htsp.HtspData

/**
 * M657: jadro prepínania kanálov (vyclenené z PlayerActivity): výber kanála zo zoznamu
 * s archívnou voľbou (naživo / od začiatku), spustenie prebiehajúcej nahrávky od začiatku,
 * zapamätanie posledného kanála (M494) a samotné switchToIndex vrátane M262 inicializácie
 * HTSP režimu. Poradie krokov je zhodné s pôvodným kódom. Stav drží [LiveSession],
 * [StreamState] a [TrackState]; čo ostáva v aktivite (PIN, DVR stav, ovládanie, zoznam
 * kanálov, otváranie streamu, archívne compose stavy, intent) chodí cez [Actions].
 */
internal class ChannelSwitcher(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val live: LiveSession,
    private val stream: StreamState,
    private val tracks: TrackState,
    private val actions: Actions
) {
    interface Actions {
        /** TV/box (Android TV) — na detekciu kde sa ma archivny vyber zobrazovat. */
        fun isTvDevice(): Boolean
        fun pokeControls()
        fun closeChannelList()
        fun refreshDvrState()
        fun cancelReconnect()
        fun requestPin(onOk: () -> Unit, onCancel: () -> Unit, channelIndex: Int?)
        fun playHtspLive(server: sk.tvhclient.shared.model.TvhServer, channelId: Long, timeshift: Boolean): Boolean
        fun playLiveAuto(server: sk.tvhclient.shared.model.TvhServer, url: String)
        /** M658: DVR nahravka cez HttpTsFeeder (digest-only server) / priama HTTP cesta (playInitial). */
        fun playDvrViaFeeder(server: sk.tvhclient.shared.model.TvhServer, url: String)
        fun playHttp(url: String)
        /** predpokladaj video; kontrola po Playing to opravi */
        fun setHasVideo(v: Boolean)
        /** M262: ci uz prebehlo urcenie HTSP rezimu pre toto sedenie (zdielane s doPlay). */
        fun htspInitDone(): Boolean
        fun setHtspInitDone(v: Boolean)
        /** index kanala cakajuci na archivny vyber, -1 = ziadny */
        fun archiveChoiceIdx(): Int
        fun setArchiveChoiceIdx(v: Int)
        /** 0=nazivo, 1=od zaciatku (D-pad) */
        fun setArchiveChoiceSel(v: Int)
        fun recInProgressByChan(): Map<String, sk.tvhclient.shared.model.DvrEntry>
        /** M497: archiv sa neobnovuje (dvrUuid != null). */
        fun dvrUuid(): String?
        /** EXTRA_UUID z intentu aktivity (nahradne uuid pre rememberPlayback). */
        fun intentUuid(): String?
        /** Novy PlayerActivity v DVR rezime (playRecordingFromStart). */
        fun startActivity(intent: android.content.Intent)
    }

    /**
     * M658: prve spustenie po starte aktivity (povodne doPlay v onStart lambde PlayerUi).
     * HTSP kanal -> subscription (s timeshiftom podla pref + podpory servera), inak HTTP;
     * DVR nahravka s prihlasenim -> auto-detekcia auth (feeder / priama cesta).
     */
    fun playInitial(server: sk.tvhclient.shared.model.TvhServer, channelUuid: String?, directUrl: String?, streamUrl: String) {
        val cid = channelUuid?.toLongOrNull()
        val htspMode = server.connectionMode == "htsp"
        if (cid != null && directUrl == null && htspMode) {
            // stream cez HTSP (9982). Timeshift funkcie len ak je pref zapnuty a server podporuje.
            stream.currentStreamUrl = streamUrl  // HTTP fallback pre reconnect/reparse stop
            scope.launch {
                val ts = TimeshiftPref.get(ctx) && withContext(Dispatchers.IO) {
                    runCatching {
                        HtspData.timeshiftAvailable(server, System.currentTimeMillis() / 1000)
                    }.getOrDefault(false)
                }
                if (actions.playHtspLive(server, cid, ts)) {
                    stream.htspStream = true
                    stream.htspLive = ts
                    stream.htspLiveState.value = ts
                } else {
                    stream.htspStream = false
                    stream.htspLive = false
                    stream.htspLiveState.value = false
                    actions.playLiveAuto(server, streamUrl)
                }
                actions.setHtspInitDone(true)
                actions.pokeControls()
            }
        } else {
            if (directUrl != null && server.username.isNotEmpty()) {
                // M254: auto-detekcia auth. Digest-only server -> feeder
                // (libVLC digest cez URL nevie); basic/ziadna -> priama
                // seekovatelna cesta.
                scope.launch {
                    val useFeeder = withContext(Dispatchers.IO) {
                        DvrAuthProbe.needsFeeder(server, MediaFactory.stripCreds(streamUrl))
                    }
                    stream.dvrViaFeeder = useFeeder
                    if (useFeeder) actions.playDvrViaFeeder(server, streamUrl)
                    else actions.playHttp(streamUrl)
                    actions.pokeControls()
                }
            } else {
                stream.dvrViaFeeder = false
                actions.playLiveAuto(server, streamUrl)
                actions.pokeControls()
            }
        }
    }

    /** Vyber kanala zo zoznamu: ak sa archivuje, ponukni nazivo/od zaciatku, inak prepni. */
    fun selectChannelOrArchive(idx: Int, poke: Boolean = true) {
        val ch = live.channelsState.value.getOrNull(idx)
        val rec = ch?.let { c -> actions.recInProgressByChan().let { it[c.uuid] ?: it[c.name] } }
        if (rec != null && actions.isTvDevice() && ArchiveChoicePref.get(ctx)) {
            actions.setArchiveChoiceSel(0)
            actions.setArchiveChoiceIdx(idx)
            actions.closeChannelList()
        } else if (idx != live.index) switchToIndex(idx, poke) else actions.pokeControls()
    }

    /** Vyriesi vyber pri archivovanom kanali: nazivo (prepne) alebo od zaciatku (spusti nahravku). */
    fun resolveArchiveChoice(fromStart: Boolean) {
        val idx = actions.archiveChoiceIdx()
        actions.setArchiveChoiceIdx(-1)
        if (idx < 0) return
        val ch = live.channelsState.value.getOrNull(idx) ?: LivePlaylist.channels.getOrNull(idx) ?: return
        if (!fromStart) {
            if (idx != live.index) switchToIndex(idx) else actions.pokeControls()
            return
        }
        val rec = actions.recInProgressByChan().let { it[ch.uuid] ?: it[ch.name] }
        if (rec == null) {
            if (idx != live.index) switchToIndex(idx)
            return
        }
        playRecordingFromStart(rec, ch.nowStart, ch.nowStop)
    }

    /**
     * M494: zapamataj, co sa prave prehrava (TV). Zapisuje sa pri starte a pri
     * kazdom prepnuti kanala, aby po vypnuti a zapnuti boxu appka pokracovala
     * tam, kde pouzivatel skoncil.
     */
    fun rememberPlayback() {
        if (!actions.isTvDevice()) return
        if (actions.dvrUuid() != null) return              // M497: archiv sa neobnovuje
        val srvId = (live.server ?: Tvh.store.active())?.id ?: return
        val uuid = live.uuids.getOrNull(live.index) ?: actions.intentUuid()
        LastPlayback.setLive(ctx, srvId, uuid, live.playKind)
    }

    /** Spusti prebiehajucu nahravku od zaciatku (novy PlayerActivity v DVR rezime). */
    fun playRecordingFromStart(rec: sk.tvhclient.shared.model.DvrEntry, progStart: Long, progStop: Long) {
        val srv = live.server ?: return
        val url = Tvh.dvrUrl(srv, rec.uuid)
        val pStart = if (progStart > 0) progStart else rec.start
        val pStop = if (progStop > progStart && progStop > 0) progStop else rec.stop
        val nowSec = System.currentTimeMillis() / 1000
        val inProgress = pStart > 0 && nowSec < pStop
        val i = android.content.Intent(ctx, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, url)
            putExtra(PlayerActivity.EXTRA_TITLE, rec.title)
            putExtra(PlayerActivity.EXTRA_DURATION_MS, rec.durationSec * 1000)
            putExtra(PlayerActivity.EXTRA_DVR_UUID, rec.uuid)
            putExtra(PlayerActivity.EXTRA_DVR_RECORDING, inProgress)
            putExtra(PlayerActivity.EXTRA_DVR_PROG_START_SEC, pStart)
            putExtra(PlayerActivity.EXTRA_DVR_PROG_STOP_SEC, pStop)
            putExtra(PlayerActivity.EXTRA_DVR_REAL_START_SEC, rec.realStartSec)
            // odkial sme prisli (zivy kanal) -> navrat sem po Spat
            live.uuids.getOrNull(live.index)?.let { putExtra(PlayerActivity.EXTRA_RETURN_UUID, it) }
            putExtra(PlayerActivity.EXTRA_RETURN_TITLE, live.names.getOrElse(live.index) { "" })
        }
        runCatching { actions.startActivity(i) }
    }

    /** Prepne na konkretny kanal podla indexu, prebuduje URL a nacita. */
    fun saveLastLive(serverId: String?, uuid: String?) {
        if (serverId == null || uuid == null) return
        if (live.playKind == "radio") LastRadio.set(ctx, serverId, uuid) else LastChannel.set(ctx, serverId, uuid)
    }

    fun switchToIndex(i: Int, poke: Boolean = true) {
        if (i < 0 || i >= live.uuids.size) return
        if (i == live.index) { if (poke) actions.pokeControls(); return }  // ten isty kanal -> nenacitavaj znova
        rememberPlayback()  // M494: obnovenie po restarte appky
        val srv = live.server ?: return
        val uuid = live.uuids[i]
        // rodicovsky zamok: zamknuty kanal mimo 5-min okna -> vypytaj PIN
        if (ParentalLock.channelNeedsPin(ctx, srv.id, uuid)) {
            actions.requestPin(onOk = { switchToIndex(i, poke) }, onCancel = { }, channelIndex = i)
            return
        }
        // M392-fix3: volba titulkov plati len pre aktualny kanal — pri prepnuti na INY
        // kanal ju vynuluj na vypnute (ako HTSP: desiredSubName = null). Restart toho
        // isteho kanala (zmena profilu, applyProfileChange) sem pride s rovnakym uuid
        // cez liveIndex=-1, preto porovnavame uuid, nie index.
        if (uuid != live.uuidState.value) {
            tracks.httpSpuWantOff = true
            tracks.httpSpuWantName = null
        }
        live.index = i
        live.indexState.value = i
        // M523: AZ TU, ked uz index ukazuje na NOVY kanal. Volanie na zaciatku
        // switchToIndex citalo este stary index, takze tlacidlo nahravania
        // zobrazovalo stav kanala, z ktoreho pouzivatel prave odisiel — na
        // nahravanom kanali „Nahrat" a na nenahravanom „Zrusit nahravanie".
        actions.refreshDvrState()
        val name = live.names.getOrElse(i) { "" }
        live.titleState.value = name
        live.uuidState.value = uuid
        saveLastLive(srv.id, uuid)
        // novy kanal = neznama relacia; skry progress bar starej relacie
        live.showProgramme(LivePlaylist.channels.getOrNull(i))   // M652
        // M383: profil je jednotny pre cely server (per-kanal override zruseny)
        val prof = srv.profile.ifBlank { "pass" }
        val url = Tvh.liveUrl(srv, uuid, name, prof)
        stream.currentStreamUrl = url
        actions.cancelReconnect()  // nove pripojenie -> zrus stare pokusy
        tracks.resetReparse()  // novy kanal -> povol jednorazovy re-parse stop
        actions.setHasVideo(true)  // predpokladaj video; kontrola po Playing to opravi
        val cid = uuid.toLongOrNull()
        // M262: ak HTSP rezim este nebol urceny (prepnutie pred doPlay, napr. odchod
        // z PIN vyzvy zamknuteho startovacieho kanala), urci ho tu rovnako ako doPlay,
        // aby aj prvy prepnuty kanal mal HTSP/timeshift a nie len HTTP.
        if (srv.connectionMode == "htsp" && cid != null && !actions.htspInitDone()) {
            actions.setHtspInitDone(true)
            scope.launch {
                val ts = TimeshiftPref.get(ctx) && withContext(Dispatchers.IO) {
                    runCatching {
                        HtspData.timeshiftAvailable(srv, System.currentTimeMillis() / 1000)
                    }.getOrDefault(false)
                }
                if (actions.playHtspLive(srv, cid, ts)) {
                    stream.htspStream = true; stream.htspLive = ts; stream.htspLiveState.value = ts
                } else {
                    stream.htspStream = false; stream.htspLive = false; stream.htspLiveState.value = false
                    actions.playLiveAuto(srv, url)
                }
                if (poke) actions.pokeControls()
            }
            return
        }
        if (stream.htspStream && cid != null && actions.playHtspLive(srv, cid, stream.htspLive)) {
            if (poke) actions.pokeControls()
            return
        }
        actions.playLiveAuto(srv, url)
        if (poke) actions.pokeControls()
    }
}
