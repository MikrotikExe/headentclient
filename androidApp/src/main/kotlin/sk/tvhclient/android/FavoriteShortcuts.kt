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
 * M573 (issue #7) — favourite channels as dynamic app shortcuts (ShortcutManager).
 *
 * Launchers that show app shortcuts (long-press on the icon on a phone,
 * on Google TV e.g. Projectivity) thus get tiles of favourite channels with
 * a picon; a click opens the player directly on the channel via the deep link
 * `headentclient://channel/<uuid>` ([DeepLink]).
 *
 * Published on every change of the favourites ([Favorites.save] -> [onFavoritesChanged])
 * and after every load of the channel list ([rowsLoaded], so that names and picons
 * are current). It keeps the channel list in [rows]; without it (e.g. a favourites change
 * before loading) it waits for the next load.
 *
 * The standard Google TV launcher does not show app shortcuts — nothing changes there.
 */
object FavoriteShortcuts {

    private const val MAX = 8                 // launchers typically show 4–5, the system allows more
    private const val ICON_PX = 192

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var rows: Map<String, ChannelRow> = emptyMap()
    /** M580: now/next EPG for the row on the TV home screen. */
    @Volatile private var epg: Map<String, List<EpgEvent>> = emptyMap()
    @Volatile private var lastTvSignature: String? = null
    /** Signature of the most recently published content (server + uuid:name in order). */
    @Volatile private var lastSignature: String? = null
    /** Published with all picons? If not, it is retried on the next load. */
    @Volatile private var lastComplete = false

    /** The TV channel list has loaded — remember it and publish the shortcuts. */
    fun rowsLoaded(ctx: Context, serverId: String?, all: List<ChannelRow>, epgMap: Map<String, List<EpgEvent>> = emptyMap()) {
        rows = all.associateBy { it.channel.uuid }
        if (epgMap.isNotEmpty()) epg = epgMap
        schedule(ctx.applicationContext, serverId)
    }

    /** The favourites have changed (add, remove, move). */
    fun onFavoritesChanged(ctx: Context, serverId: String) {
        schedule(ctx.applicationContext, serverId)
    }

    private fun schedule(app: Context, serverId: String?) {
        if (serverId == null) return
        if (android.os.Build.VERSION.SDK_INT < 25) return   // shortcuts are available from Android 7.1
        job?.cancel()
        job = scope.launch {
            delay(400)   // coalescing of rapid changes (dragging in Favourites)
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
        publishTvHome(app, favRows.take(MAX))   // M580 (independent of the shortcut limit)
        val picked = favRows.take(minOf(MAX, ShortcutManagerCompat.getMaxShortcutCountPerActivity(app)))
        if (picked.isEmpty()) {
            if (lastSignature != "") { ShortcutManagerCompat.removeAllDynamicShortcuts(app); lastSignature = ""; lastComplete = true }
            return
        }
        // Cheap signature first — the channel list is refreshed on every EPG tick and
        // picons need not be loaded when nothing has changed (and last time they were all there)
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
        currentCoroutineContext().ensureActive()   // a cancelled job (a new change) publishes nothing
        ShortcutManagerCompat.setDynamicShortcuts(app, list)
        lastSignature = sig
        lastComplete = complete
    }

    /** Picon via Coil (cache + network); null when it fails to load. */
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
     * M580: row on the Android TV home screen. The signature also includes the current
     * programmes so that the "Now / Next" text is rewritten on every new now/next.
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
     * Picons are wide logos on a transparent background; a shortcut icon is a square. The logo is
     * placed into a square with a margin on a dark backing (white logos would otherwise blend into a light
     * launcher). The adaptive version (Android 8+) is "full bleed" — the launcher
     * crops it into its own shape itself, which is why the logo sits in the safe zone (the middle half)
     * and the backing has no rounding; the legacy version has rounded corners of its own.
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
 * M573 — deep link to a channel: `headentclient://channel/<uuid>`.
 *
 * It is received by [MainActivity] (intent-filter in the manifest), which defers the request into
 * [pending]; it is carried out by the UI, which can wait for the channels to load and populate
 * LivePlaylist (the same reason as with restoring the last channel, M496) — opening the player
 * directly would play a single channel without CH+/-.
 *
 * It can also be used from other apps / launchers / adb:
 *   am start -a android.intent.action.VIEW -d "headentclient://channel/<uuid>"
 */
object DeepLink {
    const val SCHEME = "headentclient"
    const val HOST_CHANNEL = "channel"

    val pending = mutableStateOf<String?>(null)

    fun channelUri(uuid: String): Uri = Uri.parse("$SCHEME://$HOST_CHANNEL/$uuid")

    /** Returns true if the intent was a deep link to a channel (and was taken over). */
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
