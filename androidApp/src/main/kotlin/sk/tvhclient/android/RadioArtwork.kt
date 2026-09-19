package sk.tvhclient.android

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import coil.request.ImageRequest
import sk.tvhclient.shared.model.TvhServer

/**
 * M625: obrazok (album art) pre medialnu notifikaciu radia.
 *
 * System (Android 13+) odvodzuje farby medialnej notifikacie z tohto obrazka,
 * takze podklad kreslime vo farbach appky — moderny rezim teal/navy, klasik
 * Material 3 (fialova), tmavy alebo svetly podla ThemePref/systemu. Picon
 * stanice je v strede na plate (primaryContainer); bez piconu je tam biela
 * silueta radia (ic_stat_radio) vo farbe primary.
 */
object RadioArtwork {

    private const val SIZE = 512

    private data class Palette(val bg: Int, val plate: Int, val accent: Int)

    private fun palette(ctx: Context): Palette {
        val modern = UiModePref.get(ctx) == UiModePref.MODERN
        val dark = when (ThemePref.get(ctx)) {
            ThemePref.DARK -> true
            ThemePref.LIGHT -> false
            else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
        return when {
            // ModernTheme.kt: surface / primaryContainer / primary
            modern && dark -> Palette(0xFF0C1730.toInt(), 0xFF0F2E22.toInt(), 0xFF1D9E75.toInt())
            modern -> Palette(0xFFF4F8F7.toInt(), 0xFFBFEBD9.toInt(), 0xFF0F8A63.toInt())
            // klasik = predvolene Material 3 schemy (darkColorScheme / lightColorScheme)
            dark -> Palette(0xFF1C1B1F.toInt(), 0xFF4F378B.toInt(), 0xFFD0BCFF.toInt())
            else -> Palette(0xFFFFFBFE.toInt(), 0xFFEADDFF.toInt(), 0xFF6750A4.toInt())
        }
    }

    /** Podklad + plat + (picon alebo silueta radia). [picon] moze byt null. */
    fun render(ctx: Context, picon: Bitmap?): Bitmap {
        val pal = palette(ctx)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(pal.bg)
        // jemny diagonalny nadych akcentu, nech podklad nie je plocha
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
                withAlpha(pal.accent, 0x33), withAlpha(pal.accent, 0x00),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), glow)
        // plat na picon
        val plate = RectF(96f, 96f, SIZE - 96f, SIZE - 96f)
        c.drawRoundRect(plate, 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.plate })
        if (picon != null) {
            // picon vpisany do platu s okrajom, zachovany pomer stran
            val inner = RectF(plate.left + 40f, plate.top + 40f, plate.right - 40f, plate.bottom - 40f)
            val scale = minOf(inner.width() / picon.width, inner.height() / picon.height)
            val w = picon.width * scale
            val h = picon.height * scale
            val dst = RectF(
                inner.centerX() - w / 2, inner.centerY() - h / 2,
                inner.centerX() + w / 2, inner.centerY() + h / 2
            )
            c.drawBitmap(picon, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            // silueta radia (rovnaka cesta ako ic_stat_radio, 24dp mriezka)
            val p = Path().apply {
                moveTo(3.24f, 6.15f); cubicTo(2.51f, 6.43f, 2f, 7.17f, 2f, 8f); lineTo(2f, 20f)
                cubicTo(2f, 21.1f, 2.89f, 22f, 4f, 22f); lineTo(20f, 22f)
                cubicTo(21.11f, 22f, 22f, 21.1f, 22f, 20f); lineTo(22f, 8f)
                cubicTo(22f, 6.89f, 21.11f, 6f, 20f, 6f); lineTo(8.3f, 6f); lineTo(16.56f, 2.66f)
                lineTo(15.88f, 1f); close()
                // reproduktor
                addCircle(7f, 17f, 3f, Path.Direction.CCW)
                // displej
                moveTo(20f, 12f); lineTo(18f, 12f); lineTo(18f, 10f); lineTo(16f, 10f); lineTo(16f, 12f)
                lineTo(4f, 12f); lineTo(4f, 8f); lineTo(20f, 8f); close()
            }
            val m = android.graphics.Matrix()
            val s = 200f / 24f
            m.setScale(s, s)
            m.postTranslate(SIZE / 2f - 12f * s, SIZE / 2f - 11.5f * s)
            p.transform(m)
            p.fillType = Path.FillType.EVEN_ODD
            c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.accent })
        }
        return bmp
    }

    /** Akcentova farba appky pre notifikaciu na starsich Androidoch (setColor). */
    fun accent(ctx: Context): Int = palette(ctx).accent

    /** Stiahne picon cez rovnaky Coil loader ako appka (auth, cache); null pri chybe. */
    suspend fun loadPicon(ctx: Context, server: TvhServer?, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val req = ImageRequest.Builder(ctx)
                .data(url)
                .allowHardware(false)   // Canvas.drawBitmap nevie hardware bitmapy
                .size(320)
                .build()
            val res = PiconImageLoader.get(ctx, server).execute(req)
            (res.drawable as? BitmapDrawable)?.bitmap
        }.getOrNull()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
