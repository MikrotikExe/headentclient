package sk.tvhclient.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    onToggleTransparent: () -> Unit,  // dotyk: dlhé podržanie
    touchUi: Boolean = false,         // M559: telefón — spodná lišta s tlačidlami a číselník
    onSubStep: (Int) -> Unit = {},
    onDigit: (Int) -> Unit = {}
) {
    var keypad by remember { mutableStateOf(false) }   // M559
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
                // M553-fix2: mriežka 40×25 sa roztiahne na celú obrazovku (ako VLC / TV v 16:9),
                // nie 4:3 v strede — bunky nie sú viazané pomerom, text sa natiahne na šírku bunky
                val ch = availH / 25f
                val cw = availW / 40f
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
                        .pointerInput(Unit) {
                            // M559-fix2: švihnutie doľava/doprava = podstránka ±1
                            var acc = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { acc = 0f },
                                onHorizontalDrag = { _, dx -> acc += dx },
                                onDragEnd = { if (acc > 80f) onSubStep(-1) else if (acc < -80f) onSubStep(+1) }
                            )
                        }
                ) {
                    if (!transparent) drawRect(Color.Black, Offset.Zero, Size(pageW, pageH))
                    // --- hlavička (riadok 0): stĺpce 0..7 = číslo strany, 8..39 = text z vysielania
                    val hdrRow: Array<TeletextCell>? = cells?.get(0)
                    drawTextRun(label + subLabel, 0, 0, cw, ch, sx, textTop, Color.White, null, 1f, style, measurer)
                    // M553-fix: nájdená strana zobrazí VLASTNÚ hlavičku (stĺpce 8..31), len hodiny
                    // (posledných 8 stĺpcov) sa berú zo živej hlavičky; kým sa strana hľadá,
                    // beží celá „rolujúca“ hlavička práve vysielaných strán (ako na TV).
                    if (hdrRow != null) {
                        drawRow(hdrRow, 0, 8, cw, ch, sx, textTop, transparent, style, measurer, toCol = 32)
                        if (header.length >= 32) {
                            drawTextRun(header.substring(24, 32), 32, 0, cw, ch, sx, textTop, Color.White, null, 1f, style, measurer)
                        }
                    } else if (header.isNotEmpty()) {
                        drawTextRun(header, 8, 0, cw, ch, sx, textTop, Color.White, null, 1f, style, measurer)
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
            if (touchUi) {
                // M559: dotyková lišta — číselník, podstránky, priehľadnosť, zavrieť
                Row(
                    Modifier.fillMaxWidth().height(48.dp).background(Color(0xD90B1220)),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // M559-fix2: ◀ ▶ = strana ±1 (podstránky má málo strán; tie idú švihnutím
                    // do strany po stránke), inak tlačidlá "nič nerobili"
                    TouchKey(if (keypad) "▾ 123" else "123", accent = keypad) { keypad = !keypad }
                    TouchKey("◀") { onStep(-1) }
                    TouchKey("▶") { onStep(+1) }
                    TouchKey("◐", accent = transparent) { onToggleTransparent() }
                    TouchKey("✕") { onClose() }
                }
            } else {
                Box(
                    Modifier.fillMaxWidth().height(34.dp).background(Color(0xD90B1220))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(hint, color = Color(0xFFCBD5E1), fontSize = 13.sp, maxLines = 1)
                }
            }
        }
        // M559: číselník (telefón) — vpravo dole nad lištou
        if (touchUi && keypad) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xF2111827))
                    .padding(6.dp)
            ) {
                for (row in listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(-1, 0, -2))) {
                    Row {
                        for (d in row) {
                            when (d) {
                                -1 -> Spacer(Modifier.size(56.dp))
                                -2 -> Box(
                                    Modifier.size(56.dp).clickable { keypad = false },
                                    contentAlignment = Alignment.Center
                                ) { Text("▾", color = Color(0xFFCBD5E1), fontSize = 20.sp) }
                                else -> Box(
                                    Modifier.size(56.dp).padding(3.dp).clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF1E293B)).clickable { onDigit(d) },
                                    contentAlignment = Alignment.Center
                                ) { Text(d.toString(), color = Color.White, fontSize = 20.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchKey(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).widthIn(min = 56.dp).clip(RoundedCornerShape(18.dp))
            .background(if (accent) Color(0xFF1D9E75) else Color(0xFF1E293B))
            .clickable { onClick() }.padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 14.sp) }
}

private val TXT_COLORS = arrayOf(
    Color(0xFF000000), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFFFFFF00),
    Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFF00FFFF), Color(0xFFFFFFFF)
)

/** Jeden riadok buniek od stĺpca [fromCol]; spája susedné bunky s rovnakými atribútmi. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRow(
    row: Array<TeletextCell>, r: Int, fromCol: Int,
    cw: Float, ch: Float, sx: Float, textTop: Float, transparent: Boolean, style: TextStyle, measurer: TextMeasurer,
    toCol: Int = 40
) {
    var c = fromCol
    while (c < toCol && c < row.size) {
        val first = row[c]
        var e = c + 1
        while (e < row.size && e < toCol) {
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

