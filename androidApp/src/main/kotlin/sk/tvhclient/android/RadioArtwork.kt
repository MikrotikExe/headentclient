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
 * M625: the image (album art) for the radio media notification.
 *
 * The system (Android 13+) derives the colours of the media notification from this image,
 * so we draw the background in the app's colours — modern mode teal/navy, classic
 * Material 3 (purple), dark or light depending on ThemePref/the system. The station's
 * picon is in the middle on a plate (primaryContainer); without a picon there is a white
 * radio silhouette (ic_stat_radio) in the primary colour.
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
            // classic = the default Material 3 schemes (darkColorScheme / lightColorScheme)
            dark -> Palette(0xFF1C1B1F.toInt(), 0xFF4F378B.toInt(), 0xFFD0BCFF.toInt())
            else -> Palette(0xFFFFFBFE.toInt(), 0xFFEADDFF.toInt(), 0xFF6750A4.toInt())
        }
    }

    /** Background + plate + (picon or radio silhouette). [picon] may be null. */
    fun render(ctx: Context, picon: Bitmap?): Bitmap {
        val pal = palette(ctx)
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(pal.bg)
        // a subtle diagonal hint of the accent, so that the background is not flat
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, SIZE.toFloat(), SIZE.toFloat(),
                withAlpha(pal.accent, 0x33), withAlpha(pal.accent, 0x00),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), glow)
        // the plate for the picon
        val plate = RectF(96f, 96f, SIZE - 96f, SIZE - 96f)
        c.drawRoundRect(plate, 56f, 56f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.plate })
        if (picon != null) {
            // the picon inscribed into the plate with a margin, aspect ratio preserved
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
            // radio silhouette (the same path as ic_stat_radio, a 24dp grid)
            val p = Path().apply {
                moveTo(3.24f, 6.15f); cubicTo(2.51f, 6.43f, 2f, 7.17f, 2f, 8f); lineTo(2f, 20f)
                cubicTo(2f, 21.1f, 2.89f, 22f, 4f, 22f); lineTo(20f, 22f)
                cubicTo(21.11f, 22f, 22f, 21.1f, 22f, 20f); lineTo(22f, 8f)
                cubicTo(22f, 6.89f, 21.11f, 6f, 20f, 6f); lineTo(8.3f, 6f); lineTo(16.56f, 2.66f)
                lineTo(15.88f, 1f); close()
                // speaker
                addCircle(7f, 17f, 3f, Path.Direction.CCW)
                // display
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

    /** The app's accent colour for the notification on older Androids (setColor). */
    fun accent(ctx: Context): Int = palette(ctx).accent

    /** Downloads the picon through the same Coil loader as the app (auth, cache); null on error. */
    suspend fun loadPicon(ctx: Context, server: TvhServer?, url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return runCatching {
            val req = ImageRequest.Builder(ctx)
                .data(url)
                .allowHardware(false)   // Canvas.drawBitmap cannot handle hardware bitmaps
                .size(320)
                .build()
            val res = PiconImageLoader.get(ctx, server).execute(req)
            (res.drawable as? BitmapDrawable)?.bitmap
        }.getOrNull()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
