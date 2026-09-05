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
import androidx.core.content.FileProvider
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
 * M580 — riadok „Obľúbené" na domovskej obrazovke Android TV (preview channel cez
 * TV provider, to iste API, cez ktore maju svoje riadky Netflix / YouTube).
 *
 * Kazdy oblubeny kanal je dlazdica 16:9 (picon na tmavom podklade), pod nou nazov
 * kanala, „Teraz: relacia · cas" a „Potom: relacia · cas" z now/next EPG. OK na
 * dlazdici spusti prehravac cez deep link (M573). Riadok sa prepisuje spolu so
 * skratkami ([FavoriteShortcuts]) — pri zmene oblubenych a po nacitani kanalov;
 * medzi spusteniami appky sa EPG v riadku samo neobnovuje.
 *
 * Obrazky dlazdic si launcher stahuje sam, picony za heslom servera by nenacital —
 * preto sa kreslia lokalne do cache a launcheru sa pustia cez FileProvider.
 *
 * Len TV (UI_MODE_TYPE_TELEVISION) a Android 8+ (preview channels). Google TV home
 * riadky appiek zobrazuje inak/menej; na Android TV home (boxy) je to plny riadok.
 */
object TvHomeChannel {

    private const val CHANNEL_KEY = "headent-favorites"
    private const val TILE_W = 640
    private const val TILE_H = 360
    private val LAUNCHERS = listOf(
        "com.google.android.tvlauncher",          // Android TV home
        "com.google.android.apps.tv.launcherx",   // Google TV home
        "com.google.android.tvrecommendations",
        "com.android.tv"
    )

    fun supported(ctx: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 26) return false
        val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        return um?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * Prepise riadok podla [favs] (v poradi). [epg] = now/next mapa (uuid -> relacie),
     * moze byt prazdna — potom sa pouzije now z [ChannelRow]. Volat z IO vlakna.
     */
    suspend fun publish(app: Context, favs: List<ChannelRow>, epg: Map<String, List<EpgEvent>>) {
        if (!supported(app)) return
        val server = sk.tvhclient.shared.Tvh.store.active() ?: return
        val resolver = app.contentResolver
        if (favs.isEmpty()) {
            // bez oblubenych riadok nevytvarame (a system by sa pytal na prazdny riadok);
            // existujuci len vyprazdnime
            findChannelId(app)?.let { id ->
                runCatching { resolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(id), null, null) }
            }
            return
        }
        val channelId = ensureChannel(app) ?: return
        // Existujuce programy podla uuid -> aktualizuju sa na mieste (nove _ID pri kazdom
        // prepise by launcheru menili riadok pri kazdej zmene relacie); zvysne sa zmazu.
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
            // intent viazany na tuto appku (nie holy deep link, ktory by mohla prevziat ina appka)
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
            if (line1.isNotEmpty()) b.setEpisodeTitle(line1)
            if (line2.isNotEmpty()) b.setDescription(line2)
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

    /**
     * M580: opravnenia na obrazky dlazdic (grantUriPermission) nepreziju restart boxu,
     * programy v TV provideri ano — launcher by po restarte ukazal dlazdice bez obrazkov.
     * BootReceiver ich preto po starte znova udeli (bez zapisu do providera).
     */
    fun regrantPosters(app: Context) {
        if (!supported(app)) return
        val dir = File(app.cacheDir, "tvhome")
        val files = dir.listFiles() ?: return
        for (f in files) {
            val uri = runCatching { FileProvider.getUriForFile(app, app.packageName + ".fileprovider", f) }.getOrNull() ?: continue
            for (pkg in LAUNCHERS) runCatching { app.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** Odstrani cely riadok (napr. pri odstraneni servera). */
    fun remove(app: Context) {
        if (!supported(app)) return
        runCatching { File(app.cacheDir, "tvhome").listFiles()?.forEach { it.delete() } }
        val id = findChannelId(app) ?: return
        runCatching { app.contentResolver.delete(TvContractCompat.buildChannelUri(id), null, null) }
    }

    // ---- kanal (riadok) ----

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
            // logo riadku = ikona appky
            runCatching {
                val logo = app.packageManager.getApplicationIcon(app.packageName).toBitmap(160, 160)
                ChannelLogoUtils.storeChannelLogo(app, newId, logo)
            }
            // Novy riadok nie je „browsable" — system sa raz spyta, ci ho pridat na domovsku
            // obrazovku. Existujuci riadok je bud uz povoleny, alebo ho pouzivatel odmietol.
            runCatching { TvContractCompat.requestChannelBrowsable(app, newId) }
            newId
        }
        return id
    }

    // ---- dlazdice ----

    /**
     * 16:9 dlazdica: picon (uz stiahnuty v Coil cache, [PiconImageLoader]) v strede na
     * tmavom podklade; bez piconu ikona appky. Ulozi sa do cache/tvhome/<uuid>.png a
     * vrati content:// URI cez FileProvider s pravom citania pre launchery.
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
        val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
        for (pkg in LAUNCHERS) {
            runCatching { app.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        return uri
    }
}
