package sk.tvhclient.android

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import sk.tvhclient.shared.api.ChannelRow
import sk.tvhclient.shared.model.EpgEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M580 — the "Favourites" row on the Android TV home screen (a preview channel via
 * the TV provider, the same API through which Netflix / YouTube have their rows).
 *
 * Each favourite channel is a 16:9 tile (picon on a dark background), below it the channel
 * name, "Now: programme · time" and "Next: programme · time" from the now/next EPG. OK on
 * a tile starts the player via a deep link (M573). The row is rewritten together with
 * the shortcuts ([FavoriteShortcuts]) — on a change of favourites and after the channels load;
 * between app launches the EPG in the row does not refresh by itself.
 *
 * The launcher downloads the tile images itself and would not load picons behind the server password —
 * so they are drawn locally into the cache and handed to the launcher via [TvHomeImageProvider].
 *
 * TV only (UI_MODE_TYPE_TELEVISION) and Android 8+ (preview channels). Google TV home
 * shows app rows differently/less; on Android TV home (boxes) it is a full row.
 */
object TvHomeChannel {

    private const val CHANNEL_KEY = "headent-favorites"
    private const val TILE_W = 640
    private const val TILE_H = 360

    fun supported(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 26) return false
        return isTvUiMode(ctx)   // M679
    }

    /**
     * Rewrites the row according to [favs] (in order). [epg] = now/next map (uuid -> programmes),
     * may be empty — then the now from [ChannelRow] is used. Call from an IO thread.
     */
    suspend fun publish(app: Context, favs: List<ChannelRow>, epg: Map<String, List<EpgEvent>>) {
        if (!supported(app)) return
        val server = sk.tvhclient.shared.Tvh.store.active() ?: return
        val resolver = app.contentResolver
        if (favs.isEmpty()) {
            // without favourites we do not create the row (and the system would ask about an empty row);
            // an existing one we merely empty
            findChannelId(app)?.let { id ->
                runCatching { resolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(id), null, null) }
            }
            return
        }
        val channelId = ensureChannel(app) ?: return
        // Existing programmes matched by uuid -> updated in place (a new _ID on every
        // rewrite would make the launcher change the row on every programme change); the rest are deleted.
        val existing = HashMap<String, Long>()
        runCatching {
            resolver.query(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                arrayOf(TvContractCompat.PreviewPrograms._ID, TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(1) ?: continue
                    existing[key] = c.getLong(0)
                }
            }
        }
        val keep = HashSet<String>()
        val nowSec = System.currentTimeMillis() / 1000
        val fmt = SimpleDateFormat(ClockPref.hm(app), Locale.getDefault())
        val nowLabel = app.getString(R.string.tv_home_now)
        val nextLabel = app.getString(R.string.mh_next)
        favs.forEachIndexed { i, row ->
            val events = epg[row.channel.uuid].orEmpty().sortedBy { it.start }
            val cur = events.firstOrNull { it.start <= nowSec && it.stop > nowSec }
            val curTitle = cur?.title ?: row.nowTitle.orEmpty()
            val curStart = cur?.start ?: row.nowStart
            val curStop = cur?.stop ?: row.nowStop
            val next = events.firstOrNull { it.start >= (if (curStop > 0) curStop else nowSec) }
            fun span(a: Long, b: Long) = if (a > 0 && b > 0) " · " + fmt.format(Date(a * 1000)) + " – " + fmt.format(Date(b * 1000)) else ""
            val line1 = if (curTitle.isNotBlank()) "$nowLabel $curTitle" + span(curStart, curStop) else ""
            val line2 = if (next != null && next.title.isNotBlank()) "$nextLabel ${next.title}" + span(next.start, next.stop) else ""

            val poster = posterUri(app, server, row)
            // intent bound to this app (not a bare deep link that another app could take over)
            val intent = Intent(Intent.ACTION_VIEW, DeepLink.channelUri(row.channel.uuid), app, MainActivity::class.java)
            val b = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setTitle(row.channel.name.ifBlank { "#" + (row.channel.number ?: 0) })
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntent(intent)
                .setInternalProviderId(row.channel.uuid)
                .setLive(true)
                .setWeight(1000 - i)
            // M580-fix: for the "channel" type the launcher shows only the description under the name (not episodeTitle)
            // -> both Now and Next go into a single description
            val desc = listOf(line1, line2).filter { it.isNotEmpty() }.joinToString("   ")
            if (desc.isNotEmpty()) b.setDescription(desc)
            if (curStart > 0 && curStop > 0) { b.setStartTimeUtcMillis(curStart * 1000); b.setEndTimeUtcMillis(curStop * 1000) }
            if (poster != null) b.setPosterArtUri(poster)
            val values = b.build().toContentValues()
            keep += row.channel.uuid
            val id = existing[row.channel.uuid]
            val r = runCatching {
                if (id != null) resolver.update(TvContractCompat.buildPreviewProgramUri(id), values, null, null)
                else resolver.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, values)
            }
            r.exceptionOrNull()?.let { CrashLogger.report(app, "TvHomeChannel", it) }
        }
        for ((uuid, id) in existing) if (uuid !in keep) runCatching { resolver.delete(TvContractCompat.buildPreviewProgramUri(id), null, null) }
    }

    /** Removes the whole row (e.g. when a server is removed). */
    fun remove(app: Context) {
        if (!supported(app)) return
        runCatching { File(app.cacheDir, "tvhome").listFiles()?.forEach { it.delete() } }
        val id = findChannelId(app) ?: return
        runCatching { app.contentResolver.delete(TvContractCompat.buildChannelUri(id), null, null) }
    }

    // ---- channel (row) ----

    private fun findChannelId(app: Context): Long? {
        val c = runCatching {
            app.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI, PreviewChannel.Columns.PROJECTION, null, null, null
            )
        }.getOrNull() ?: return null
        c.use {
            while (it.moveToNext()) {
                val ch = PreviewChannel.fromCursor(it)
                if (ch.internalProviderId == CHANNEL_KEY) return ch.id
            }
        }
        return null
    }

    private fun ensureChannel(app: Context): Long? {
        val name = app.getString(R.string.favorites)
        val appIntent = Intent(app, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        val existing = findChannelId(app)
        val ch = PreviewChannel.Builder()
            .setDisplayName(name)
            .setDescription(app.getString(R.string.app_name))
            .setAppLinkIntent(appIntent)
            .setInternalProviderId(CHANNEL_KEY)
            .build()
        val id: Long = if (existing != null) {
            runCatching { app.contentResolver.update(TvContractCompat.buildChannelUri(existing), ch.toContentValues(), null, null) }
            existing
        } else {
            val r = runCatching { app.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, ch.toContentValues()) }
            r.exceptionOrNull()?.let { CrashLogger.report(app, "TvHomeChannel", it) }
            val uri = r.getOrNull() ?: return null
            val newId = ContentUris.parseId(uri)
            // row logo = app icon
            runCatching {
                val logo = app.packageManager.getApplicationIcon(app.packageName).toBitmap(160, 160)
                ChannelLogoUtils.storeChannelLogo(app, newId, logo)
            }
            // A new row is not "browsable" — the system asks once whether to add it to the home
            // screen. An existing row has either already been allowed, or the user declined it.
            runCatching { TvContractCompat.requestChannelBrowsable(app, newId) }
            newId
        }
        return id
    }

    // ---- tiles ----

    /**
     * 16:9 tile: the picon (already downloaded in the Coil cache, [PiconImageLoader]) centred on
     * a dark background; without a picon the app icon. It is saved to cache/tvhome/<uuid>.png and
     * a content:// URI from [TvHomeImageProvider] is returned.
     */
    private suspend fun posterUri(app: Context, server: sk.tvhclient.shared.model.TvhServer, row: ChannelRow): Uri? {
        val logo: Bitmap? = row.piconUrl?.let { FavoriteShortcuts.loadPicon(app, server, it) }
            ?: runCatching { app.packageManager.getApplicationIcon(app.packageName).toBitmap(192, 192) }.getOrNull()
        return runCatching { drawPoster(app, row, logo) }.getOrNull()
    }

    private fun drawPoster(app: Context, row: ChannelRow, logo: Bitmap?): Uri {
        val dir = File(app.cacheDir, "tvhome").apply { mkdirs() }
        val file = File(dir, row.channel.uuid.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png")
        val out = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(0x1e, 0x2a, 0x3a))
        if (logo != null) {
            val maxW = TILE_W * 0.5f
            val maxH = TILE_H * 0.5f
            val scale = minOf(maxW / logo.width, maxH / logo.height)
            val w = logo.width * scale
            val h = logo.height * scale
            val dst = RectF((TILE_W - w) / 2f, (TILE_H - h) / 2f, (TILE_W + w) / 2f, (TILE_H + h) / 2f)
            c.drawBitmap(logo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
        // M580-fix: our own exported provider — no grants, survives a restart
        return TvHomeImageProvider.uriFor(app, file)
    }
}
