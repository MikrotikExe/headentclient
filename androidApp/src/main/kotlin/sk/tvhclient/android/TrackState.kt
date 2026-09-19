package sk.tvhclient.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import org.videolan.libvlc.MediaPlayer

/**
 * M637: stav stôp (zvuk / titulky / profil) v prehrávači, vyclenený z PlayerActivity.
 *
 * Drží compose stavy pre track menu (signály otvorenia, navigácia, verzia zoznamu),
 * voľbu HTSP titulkov (vlastný DVB dekodér — [selectedSubEs]), trvalú voľbu HTTP
 * titulkov (M392-fix: [httpSpuWantOff]/[httpSpuWantName], vynucuje sa pri každom ESAdded),
 * stav prepínača profilu (M383) a dva časovače: opakovaná obnova zoznamu stôp po štarte
 * (ESAdded nechodí spoľahlivo) a jednorazový re-parse, keď audio stopy nemajú jazyk.
 *
 * Čo mení stream (výber profilu, zápis poslednej audio stopy, prepnutie kanála) ostáva
 * v aktivite — sem chodí len [player] a [htspFeeder] ako lambdy.
 */
class TrackState(
    private val ctx: Context,
    private val player: () -> MediaPlayer?,
    private val htspFeeder: () -> HtspTsFeeder?
) {
    // HTSP titulky: kompletny zoznam jazykov berieme z metadat (feeder.subtitleStreams),
    // nie z libVLC (to ma len jazyky, ktore uz "prehovorili"). Vyber mapujeme na realnu
    // libVLC stopu podla anglickeho nazvu jazyka (libVLC DVB titulky netaguje kodom).
    val selectedSubEs = mutableStateOf(-1)  // -1 = Vypnute
    var desiredSubName: String? = null   // anglicky nazov zvoleneho jazyka (null = vypnute)
    // M392-fix: trvala volba pouzivatela pre HTTP live titulky. Default OFF (zhodne s HTSP).
    var httpSpuWantOff = true
    var httpSpuWantName: String? = null

    // signaly pre PlayerUi (zvysenie = otvor)
    val openAudioSignal = mutableStateOf(0)
    val openSpuSignal = mutableStateOf(0)
    val openProfileSignal = mutableStateOf(0)
    val closeMenuSignal = mutableStateOf(0)
    // M383: prepinac stream profilu v prehravaci (len HTTP live)
    val profileItems = mutableStateOf<List<String>>(emptyList())
    val currentProfile = mutableStateOf("")
    // M383-fix: dostupnost MUSI byt compose state — inak by sa UI o zmene nedozvedelo
    val profileSwitch = mutableStateOf(false)
    // Navigacia track menu (audio/titulky) z Activity
    val navIndex = mutableStateOf(0)
    // Verzia zoznamu stop — zvysi sa ked libVLC prida/ubere stopu (ESAdded/ESDeleted).
    val listVersion = mutableStateOf(0)
    var menuKind = "audio"

    fun bumpListVersion() { listVersion.value = listVersion.value + 1 }

    fun openAudioMenu() { menuKind = "audio"; navIndex.value = 0; openAudioSignal.value++ }
    fun openSpuMenu() { menuKind = "spu"; navIndex.value = 0; openSpuSignal.value++ }
    /** Otvorí menu profilu; zoznam položiek dodá aktivita (server ho môže neskôr nahradiť). */
    fun openProfileMenu() { menuKind = "profile"; navIndex.value = 0; openProfileSignal.value++ }
    fun closeMenu() { closeMenuSignal.value++ }

    /** Id položiek aktuálne otvoreného menu v poradí zobrazenia (pre D-pad výber). */
    fun menuIds(htspStream: Boolean): List<Int> {
        val mp = player() ?: return emptyList()
        if (menuKind == "profile") return profileItems.value.indices.toList()
        return if (menuKind == "audio") {
            mp.audioTrackItems().map { it.id }
        } else {
            val spu = if (htspStream) htspSpuItems() else mp.spuTrackItems()
            listOf(-1) + spu.map { it.id }  // -1 = Vypnute
        }
    }

    /** HTSP: kompletny zoznam titulkovych jazykov z metadat (rovnaky na kazdom zariadeni,
     *  nezavisle od toho ci jazyk uz "prehovoril"). id = HTSP stream index. */
    fun htspSpuItems(): List<TrackItem> {
        val subs = htspFeeder()?.subtitleStreams ?: return emptyList()
        // M491: nazov stopy, ked sa jazyk neda urcit — bol natvrdo po slovensky
        return subs.map { TrackItem(it.esIndex, langDisplay(it.language) ?: ctx.getString(R.string.sub_dvb)) }
    }

    /** Nastavi libVLC titulkovu stopu podla zelaneho (anglickeho) nazvu jazyka, ak uz existuje. */
    fun applyDesiredSpu() {
        val mp = player() ?: return
        val want = desiredSubName ?: return
        val tracks = mp.spuTracks ?: return
        val m = tracks.firstOrNull { it.id >= 0 && (it.name?.contains(want, ignoreCase = true) == true) } ?: return
        if (mp.spuTrack != m.id) mp.spuTrack = m.id
    }

    /** M392-fix: presad zelanie pouzivatela pre titulky na HTTP live streame (pri kazdom ESAdded). */
    fun applyPendingSpuRestore(htspStream: Boolean, seekable: Boolean) {
        val mp = player() ?: return
        if (htspStream || seekable) return
        val want = httpSpuWantName
        if (want != null) {
            val tr = mp.spuTracks?.firstOrNull { it.id >= 0 && (it.name?.contains(want, ignoreCase = true) == true) } ?: return
            if (mp.spuTrack != tr.id) mp.spuTrack = tr.id
            return
        }
        if (httpSpuWantOff && mp.spuTrack != -1) mp.spuTrack = -1
    }

    /** M392-fix: rucna volba titulkov na HTTP live (D-pad aj dotykove menu). */
    fun httpSpuUserPick(id: Int) {
        httpSpuWantOff = id < 0
        httpSpuWantName = if (id >= 0) player()?.spuTrackItems()?.firstOrNull { it.id == id }?.name else null
    }

    /** M392: zosulad zelanie so skutocnym stavom (pred restartom streamu pri zmene profilu). */
    fun captureHttpSpuFromPlayer() {
        val mp = player() ?: return
        val cur = runCatching { mp.spuTrack }.getOrDefault(-1)
        httpSpuWantOff = cur < 0
        httpSpuWantName = if (cur >= 0) mp.spuTrackItems().firstOrNull { it.id == cur }?.name else null
    }

    /** Novy kanal: HTSP titulky vypnute, HTTP volba na default OFF, povol re-parse. */
    fun resetForNewChannel() {
        selectedSubEs.value = -1
        desiredSubName = null
        httpSpuWantOff = true
        httpSpuWantName = null
        reparseDone = false
        reparseHandler.removeCallbacksAndMessages(null)
    }

    // ---- obnova zoznamu stop po starte ----
    // Stopy a DVB titulkove stopy sa objavia az par sekund po starte. ESAdded na niektorych
    // streamoch nechodi spolahlivo, preto po Event.Playing kratko pollujeme a obnovujeme
    // pripadne otvorene track menu (zvysenim listVersion), kym sa stopy doplnia.
    private val refreshHandler = Handler(Looper.getMainLooper())
    fun scheduleRefresh() {
        refreshHandler.removeCallbacksAndMessages(null)
        // niekolko vln v priebehu ~8 s — staci aby sa stihli doparsovat jazyky aj DVB titulky
        for (delay in longArrayOf(800L, 1600L, 2600L, 4000L, 6000L, 8000L)) {
            refreshHandler.postDelayed({ bumpListVersion() }, delay)
        }
    }
    fun cancelRefresh() = refreshHandler.removeCallbacksAndMessages(null)

    // ---- jednorazovy re-parse kvoli stopam ----
    // Ak po starte ziadna audio stopa nema jazyk, libVLC vytvoril ES skor nez doparsoval PMT
    // s jazykovymi deskriptormi (caste na multi-audio TS). Pomoze len cerstve napojenie streamu.
    // Spravime ho RAZ na kanal a LEN ked jazyky naozaj chybaju (inak ziadny zbytocny blik).
    private var reparseDone = false
    private val reparseHandler = Handler(Looper.getMainLooper())
    fun maybeReparse(htspStream: () -> Boolean, seekable: () -> Boolean, reconnect: () -> Unit) {
        if (reparseDone || seekable() || htspStream()) return  // HTSP berie jazyky z PMT
        reparseHandler.removeCallbacksAndMessages(null)
        reparseHandler.postDelayed({
            val mp = player()
            if (reparseDone || seekable() || htspStream() || mp == null) return@postDelayed
            val langs = runCatching { mp.trackLanguages() }.getOrDefault(emptyMap())
            val anyLang = langs.values.any { !it.isNullOrBlank() && !it.equals("und", true) }
            reparseDone = true  // tak ci tak skus len raz
            // znovu napojenie cez OVERENU reconnect cestu (sama sa zotavi, naplni stopy)
            if (!anyLang) reconnect()
        }, 1800)
    }

    /** Nový kanál -> povoľ jednorazový re-parse stôp. */
    fun resetReparse() { reparseDone = false; reparseHandler.removeCallbacksAndMessages(null) }

    fun destroy() {
        refreshHandler.removeCallbacksAndMessages(null)
        reparseHandler.removeCallbacksAndMessages(null)
    }
}
