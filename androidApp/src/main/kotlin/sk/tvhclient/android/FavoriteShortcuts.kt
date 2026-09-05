package sk.tvhclient.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.api.ChannelRow
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer

/**
 * M573 (issue #7) — oblubene kanaly ako dynamicke skratky aplikacie (ShortcutManager).
 *
 * Launchery, ktore skratky aplikacii zobrazuju (na telefone podrzanie ikony,
 * na Google TV napr. Projectivity), tak dostanu dlazdice oblubenych kanalov s
 * piconom; klik otvori prehravac priamo na kanali cez deep link
 * `headentclient://channel/<uuid>` ([DeepLink]).
 *
 * Zverejnuje sa pri kazdej zmene oblubenych ([Favorites.save] -> [onFavoritesChanged])
 * a po kazdom nacitani zoznamu kanalov ([rowsLoaded], nech su nazvy a picony
 * aktualne). Zoznam kanalov si drzi v [rows]; bez neho (napr. zmena oblubenych
 * pred nacitanim) sa pocka na najblizsie nacitanie.
 *
 * Standardny Google TV launcher skratky aplikacii nezobrazuje — tam sa nic nezmeni.
 */
object FavoriteShortcuts {

    private const val MAX = 8                 // launchery zobrazuju typicky 4–5, system dovoli viac
    private const val ICON_PX = 192

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var rows: Map<String, ChannelRow> = emptyMap()
    /** M580: now/next EPG pre riadok na domovskej obrazovke TV. */
    @Volatile private var epg: Map<String, List<EpgEvent>> = emptyMap()
    @Volatile private var lastTvSignature: String? = null
    /** Podpis naposledy zverejneneho obsahu (server + uuid:nazov v poradi). */
    @Volatile private var lastSignature: String? = null
    /** Zverejnene so vsetkymi piconmi? Ak nie, pri dalsom nacitani sa skusi znova. */
    @Volatile private var lastComplete = false

    /** Zoznam TV kanalov sa nacital — zapamataj a zverejni skratky. */
    fun rowsLoaded(ctx: Context, serverId: String?, all: List<ChannelRow>, epgMap: Map<String, List<EpgEvent>> = emptyMap()) {
        rows = all.associateBy { it.channel.uuid }
        if (epgMap.isNotEmpty()) epg = epgMap
        schedule(ctx.applicationContext, serverId)
    }

    /** Oblubene sa zmenili (pridanie, odobratie, presun). */
    fun onFavoritesChanged(ctx: Context, serverId: String) {
        schedule(ctx.applicationContext, serverId)
    }

    private fun schedule(app: Context, serverId: String?) {
        if (serverId == null) return
        if (android.os.Build.VERSION.SDK_INT < 25) return   // skratky su od Android 7.1
        job?.cancel()
        job = scope.launch {
            delay(400)   // zlucenie rychlych zmien (tahanie v Oblubenych)
            try { publish(app, serverId) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { CrashLogger.report(app, "FavoriteShortcuts", e) }
        }
    }

    private suspend fun publish(app: Context, serverId: String) {
        val server = Tvh.store.active()?.takeIf { it.id == serverId } ?: return
        val favs = Favorites.list(app, serverId)
        val snapshot = rows
        val favRows = favs.mapNotNull { snapshot[it] }
        publishTvHome(app, favRows.take(MAX))   // M580 (nezavisle od limitu skratiek)
        val picked = favRows.take(minOf(MAX, ShortcutManagerCompat.getMaxShortcutCountPerActivity(app)))
        if (picked.isEmpty()) {
            if (lastSignature != "") { ShortcutManagerCompat.removeAllDynamicShortcuts(app); lastSignature = ""; lastComplete = true }
            return
        }
        // Lacny podpis najprv — zoznam kanalov sa obnovuje pri kazdom EPG ticku a
        // picony netreba nacitavat, ked sa nic nezmenilo (a minule boli vsetky)
        val sig = picked.joinToString("|", prefix = serverId) { it.channel.uuid + ":" + it.channel.name + ":" + (it.piconUrl ?: "") }
        if (sig == lastSignature && lastComplete) return
        val list = ArrayList<ShortcutInfoCompat>(picked.size)
        var complete = true
        picked.forEachIndexed { i, row ->
            val picon: Bitmap? = row.piconUrl?.let { loadPicon(app, server, it) }
            if (picon == null && row.piconUrl != null) complete = false
            val icon = when {
                picon == null -> IconCompat.createWithResource(app, R.mipmap.ic_launcher)
                android.os.Build.VERSION.SDK_INT >= 26 -> IconCompat.createWithAdaptiveBitmap(squareIcon(picon, adaptive = true))
                else -> IconCompat.createWithBitmap(squareIcon(picon, adaptive = false))
            }
            val intent = Intent(Intent.ACTION_VIEW, DeepLink.channelUri(row.channel.uuid), app, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            list += ShortcutInfoCompat.Builder(app, "ch:" + row.channel.uuid)
                .setShortLabel(row.channel.name.ifBlank { "#" + (row.channel.number ?: 0) })
                .setLongLabel(row.channel.name.ifBlank { "#" + (row.channel.number ?: 0) })
                .setIcon(icon)
                .setIntent(intent)
                .setRank(i)
                .build()
        }
        currentCoroutineContext().ensureActive()   // zruseny job (nova zmena) nic nezverejni
        ShortcutManagerCompat.setDynamicShortcuts(app, list)
        lastSignature = sig
        lastComplete = complete
    }

    /** Picon cez Coil (cache + siet); null ked sa nenacita. */
    suspend fun loadPicon(app: Context, server: TvhServer, url: String): Bitmap? {
        val loader = PiconImageLoader.get(app, server)
        return try {
            val res = loader.execute(
                ImageRequest.Builder(app).data(url).allowHardware(false).size(ICON_PX).build()
            )
            (res.drawable as? BitmapDrawable)?.bitmap ?: res.drawable?.toBitmap(ICON_PX, ICON_PX)
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
    }

    /**
     * M580: riadok na domovskej obrazovke Android TV. Podpis zahrna aj aktualne
     * relacie, aby sa text „Teraz / Potom" prepisal pri kazdom novom now/next.
     */
    private suspend fun publishTvHome(app: Context, picked: List<ChannelRow>) {
        if (!TvHomeChannel.supported(app)) return
        val e = epg
        val nowSec = System.currentTimeMillis() / 1000
        val sig = picked.joinToString("|") { r ->
            val ev = e[r.channel.uuid].orEmpty()
            val cur = ev.firstOrNull { it.start <= nowSec && it.stop > nowSec }
            val curStop = cur?.stop ?: r.nowStop
            val next = ev.firstOrNull { it.start >= (if (curStop > 0) curStop else nowSec) }
            r.channel.uuid + ":" + r.channel.name + ":" + (cur?.title ?: r.nowTitle.orEmpty()) + ":" + curStop + ":" + (next?.title ?: "")
        }
        if (sig == lastTvSignature) return
        currentCoroutineContext().ensureActive()
        val ok = try { TvHomeChannel.publish(app, picked, e); true }
            catch (ce: CancellationException) { throw ce }
            catch (t: Throwable) { CrashLogger.report(app, "TvHomeChannel", t); false }
        if (ok) lastTvSignature = sig
    }

    /**
     * Picony su siroke loga na priehladnom pozadi; ikona skratky je stvorec. Logo sa
     * vlozi do stvorca s okrajom na tmavy podklad (biele loga inak splynu so svetlym
     * launcherom). Adaptivna verzia (Android 8+) je „na celu plochu" — launcher ju
     * sam oreze do svojho tvaru, preto je logo v bezpecnej zone (stredna polovica)
     * a podklad bez zaoblenia; stara verzia ma zaoblene rohy sama.
     */
    private fun squareIcon(src: Bitmap, adaptive: Boolean): Bitmap {
        val out = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1e, 0x2a, 0x3a) }
        val r = if (adaptive) 0f else ICON_PX * 0.18f
        c.drawRoundRect(RectF(0f, 0f, ICON_PX.toFloat(), ICON_PX.toFloat()), r, r, bg)
        val pad = ICON_PX * (if (adaptive) 0.25f else 0.14f)
        val avail = ICON_PX - 2 * pad
        val scale = minOf(avail / src.width, avail / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val dst = RectF((ICON_PX - w) / 2f, (ICON_PX - h) / 2f, (ICON_PX + w) / 2f, (ICON_PX + h) / 2f)
        c.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}

/**
 * M573 — deep link na kanal: `headentclient://channel/<uuid>`.
 *
 * Prijima ho [MainActivity] (intent-filter v manifeste) a poziadavku odlozi do
 * [pending]; vykona ju UI, ktore vie pockat na nacitanie kanalov a naplnit
 * LivePlaylist (rovnaky dovod ako pri obnove posledneho kanala, M496) — priame
 * otvorenie prehravaca by hralo jediny kanal bez CH+/-.
 *
 * Da sa pouzit aj z inych appiek / launcherov / adb:
 *   am start -a android.intent.action.VIEW -d "headentclient://channel/<uuid>"
 */
object DeepLink {
    const val SCHEME = "headentclient"
    const val HOST_CHANNEL = "channel"

    val pending = mutableStateOf<String?>(null)

    fun channelUri(uuid: String): Uri = Uri.parse("$SCHEME://$HOST_CHANNEL/$uuid")

    /** Vrati true, ak intent bol deep link na kanal (a bol prevzaty). */
    fun handle(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val d = intent.data ?: return false
        if (d.scheme != SCHEME || d.host != HOST_CHANNEL) return false
        val uuid = d.lastPathSegment?.trim().orEmpty()
        if (uuid.isEmpty()) return false
        pending.value = uuid
        return true
    }
}
