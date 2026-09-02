package sk.tvhclient.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sk.tvhclient.shared.teletext.TeletextCell
import sk.tvhclient.shared.teletext.TeletextRenderer

/**
 * M553 — zobrazenie teletextovej stránky (mockup schválený): 40×25 mriežka
 * vykreslená na Canvas (text + mozaika obdĺžnikmi), predvolene nepriehľadná,
 * OK prepína priehľadný „mix“ režim, dole riadok s nápovedou. Ovládanie rieši
 * PlayerActivity (dispatchKeyEvent), tu je len vykreslenie stavu.
 *
 * Samostatný composable — PlayerUi je na hranici veľkosti metódy.
 */
@Composable
fun TeletextOverlay(
    session: TeletextSession,
    pageNumber: Int,          // požadovaná strana (hex, 0x100..0x8FF)
    subpage: Int,             // -1 = posledná prijatá podstránka
    entry: String,            // rozpísané číslice pri zadávaní ("", "1", "12")
    transparent: Boolean,
    reveal: Boolean,
    isHttp: Boolean,
    onClose: () -> Unit,
    onStep: (Int) -> Unit,            // dotyk: horná polovica +1, dolná −1
    onToggleTransparent: () -> Unit   // dotyk: dlhé podržanie
) {
    // prekreslenie pri každej prijatej stránke
    @Suppress("UNUSED_VARIABLE") val ver = session.pageVersion.value
    val page = session.decoder.page(pageNumber, subpage)
    val cells = remember(page, reveal) { page?.let { TeletextRenderer.render(it, reveal) } }
    val header = session.decoder.lastHeader
    val noTeletext = isHttp && session.httpNoTeletext.value
    val label = if (entry.isNotEmpty()) "P" + entry.padEnd(3, '_')
        else "P" + pageNumber.toString(16).uppercase().padStart(3, '0')
    val subLabel = if (page != null && page.subpage != 0) " " + page.subpage.toString(16).padStart(4, '0').takeLast(2) else ""

    val hint = stringResource(R.string.ttx_hint)
    val status: String? = when {
        noTeletext -> stringResource(R.string.ttx_none)
        page == null && entry.isNotEmpty() -> null
        page == null && session.decoder.pagesSeen == 0L -> stringResource(R.string.ttx_loading)
        page == null -> stringResource(R.string.ttx_searching, pageNumber.toString(16).uppercase())
        else -> null
    }

    val measurer = rememberTextMeasurer()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val availH = with(density) { maxHeight.toPx() } * 0.96f
                val availW = with(density) { maxWidth.toPx() } * 0.96f
                var ch = availH / 25f
                var cw = ch / 1.2f
                if (cw * 40f > availW) { cw = availW / 40f; ch = cw * 1.2f }
                val pageW = cw * 40f
                val pageH = ch * 25f
                val fontPx = ch / 1.15f
                val style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = with(density) { fontPx.toSp() },
                    color = Color.White
                )
                // skutočná šírka glyfu -> vodorovné natiahnutie na šírku bunky
                val glyph = remember(fontPx) { measurer.measure("M", style) }
                val glyphW = glyph.size.width.toFloat().coerceAtLeast(1f)
                val glyphH = glyph.size.height.toFloat().coerceAtLeast(1f)
                val sx = cw / glyphW
                val textTop = (ch - glyphH) / 2f

                Canvas(
                    Modifier
                        .size(with(density) { pageW.toDp() }, with(density) { pageH.toDp() })
                        .clip(RoundedCornerShape(6.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { pos -> onStep(if (pos.y < size.height / 2f) +1 else -1) },
                                onLongPress = { onToggleTransparent() }
                            )
                        }
                ) {
                    if (!transparent) drawRect(Color.Black, Offset.Zero, Size(pageW, pageH))
                    // --- hlavička (riadok 0): stĺpce 0..7 = číslo strany, 8..39 = text z vysielania
                    val hdrRow: Array<TeletextCell>? = cells?.get(0)
                    drawTextRun(label + subLabel, 0, 0, cw, ch, sx, textTop, Color.White, null, 1f, style, measurer)
                    // živá („rolujúca“) hlavička s hodinami má prednosť pred uloženou
                    if (header.isNotEmpty()) {
                        drawTextRun(header, 8, 0, cw, ch, sx, textTop, Color.White, null, 1f, style, measurer)
                    } else if (hdrRow != null) {
                        drawRow(hdrRow, 0, 8, cw, ch, sx, textTop, transparent, style, measurer)
                    }
                    // --- riadky 1..24
                    if (cells != null) {
                        for (r in 1 until 25) {
                            val row = cells[r] ?: continue
                            drawRow(row, r, 0, cw, ch, sx, textTop, transparent, style, measurer)
                        }
                    }
                }
                if (status != null) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xE6111827))
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        Text(status, color = Color(0xFFFBBF24), fontSize = 16.sp)
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().height(34.dp).background(Color(0xD90B1220))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Text(hint, color = Color(0xFFCBD5E1), fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

private val TXT_COLORS = arrayOf(
    Color(0xFF000000), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFFFFFF00),
    Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFF00FFFF), Color(0xFFFFFFFF)
)

/** Jeden riadok buniek od stĺpca [fromCol]; spája susedné bunky s rovnakými atribútmi. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRow(
    row: Array<TeletextCell>, r: Int, fromCol: Int,
    cw: Float, ch: Float, sx: Float, textTop: Float, transparent: Boolean, style: TextStyle, measurer: TextMeasurer
) {
    var c = fromCol
    while (c < 40 && c < row.size) {
        val first = row[c]
        var e = c + 1
        while (e < row.size && e < 40) {
            val n = row[e]
            if (n.fg != first.fg || n.bg != first.bg || n.doubleHeight != first.doubleHeight ||
                (n.mosaic >= 0) != (first.mosaic >= 0) || n.separated != first.separated) break
            e++
        }
        val sy = if (first.doubleHeight) 2f else 1f
        val x = c * cw; val y = r * ch
        // pozadie (v priehľadnom režime čierne pozadie = priehľadné)
        if (first.bg != 0 || !transparent) {
            drawRect(TXT_COLORS[first.bg], Offset(x, y), Size((e - c) * cw, ch * sy))
        }
        if (first.mosaic >= 0) {
            for (i in c until e) drawMosaic(row[i], i * cw, y, cw, ch * sy)
        } else {
            val sb = StringBuilder(e - c)
            for (i in c until e) sb.append(row[i].ch)
            val text = sb.toString()
            if (text.isNotBlank()) {
                drawTextRun(text, c, r, cw, ch, sx, textTop, TXT_COLORS[first.fg], null, sy, style, measurer)
            }
        }
        c = e
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTextRun(
    text: String, col: Int, row: Int, cw: Float, ch: Float, sx: Float, textTop: Float,
    color: Color, bg: Color?, sy: Float, style: TextStyle, measurer: TextMeasurer
) {
    val x = col * cw; val y = row * ch
    if (bg != null) drawRect(bg, Offset(x, y), Size(text.length * cw, ch * sy))
    scale(sx, sy, pivot = Offset(x, y)) {
        drawText(measurer, text, Offset(x, y + textTop), style.copy(color = color))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMosaic(
    cell: TeletextCell, x: Float, y: Float, w: Float, h: Float
) {
    val p = cell.mosaic
    if (p <= 0) return
    val col = TXT_COLORS[cell.fg]
    val bw = w / 2f; val bh = h / 3f
    val inset = if (cell.separated) minOf(bw, bh) / 6f else 0f
    for (i in 0 until 6) {
        if ((p shr i) and 1 == 0) continue
        val bx = x + (i % 2) * bw; val by = y + (i / 2) * bh
        drawRect(col, Offset(bx + inset, by + inset), Size(bw - 2 * inset, bh - 2 * inset))
    }
}

