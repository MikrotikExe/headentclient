package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sk.tvhclient.shared.htsp.HtspSignal
import sk.tvhclient.shared.htsp.HtspSourceInfo

/**
 * M724: one snapshot of the playing stream for the info window's "Stream" section (Settings →
 * Advanced → "Stream details in the info window"), like Kodi's PlayerProcessInfo. Built by
 * PlayerActivity.streamDetails(): the libVLC tracks and bitrate, the decoder in use, and over
 * HTSP the source (subscriptionStart) and the signal (signalStatus).
 */
internal data class StreamDetails(
    val videoCodec: String?, val width: Int, val height: Int, val fps: Double,
    val audioCodec: String?, val audioChannels: Int, val audioRate: Int,
    val decoder: String,
    val source: HtspSourceInfo?, val signal: HtspSignal?,
    val htsp: Boolean, val live: Boolean, val profile: String, val bitrateKbps: Int
) {
    companion object {
        const val DEC_HARDWARE = "hw"
        const val DEC_DECODE = "decode"
        const val DEC_SOFTWARE = "sw"

        /** HTSP / libVLC codec names -> what a viewer expects to read. */
        fun codecName(c: String?): String? = when (c) {
            null, "" -> null
            "MPEG2VIDEO" -> "MPEG-2"
            "H264" -> "H.264"
            "HEVC" -> "HEVC (H.265)"
            "MPEG4VIDEO" -> "MPEG-4"
            "MPEG2AUDIO" -> "MPEG audio"
            "AC3" -> "Dolby Digital (AC-3)"
            "EAC3" -> "Dolby Digital Plus (E-AC-3)"
            "AAC", "AAC-LATM" -> "AAC"
            else -> c
        }
    }
}

/** The "Stream" section inside the info window (only with the setting on). */
@Composable
internal fun StreamInfoSection(d: StreamDetails, compact: Boolean = false) {
    val label = playerFgFaint()
    val value = playerFgDim()
    // M735: on a landscape screen (TV) tighter rows, and the decoder / provider join the line above
    val rowH = if (compact) 19.dp else 22.dp
    val labelSp = if (compact) 12.sp else 13.sp
    val valueSp = if (compact) 13.sp else 14.sp
    val labelW = if (compact) 96.dp else 118.dp
    @Composable
    fun row(name: String, text: String?) {
        if (text.isNullOrBlank()) return
        Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = label, fontSize = labelSp, modifier = Modifier.width(labelW))
            Text(text, color = value, fontSize = valueSp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
    /**
     * M730 (audit R7): HTSP signalStatus carries no scale. Relative drivers send 0..65535; DVBv5
     * drivers in decibels (linuxdvb, SAT>IP) send feSNR = dB × 1000 and feSignal = dBm × 1000
     * (negative). [pct] = the bar, [text] = what is written next to it.
     */
    @Composable
    fun bar(name: String, pct: Int, text: String) {
        Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = label, fontSize = labelSp, modifier = Modifier.width(labelW))
            androidx.compose.foundation.layout.Box(
                Modifier.width(160.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(label.copy(alpha = 0.3f))
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth(pct / 100f).height(8.dp)
                        .background(if (pct >= 50) Color(0xFF4CAF50) else if (pct >= 25) Color(0xFFFFC107) else Color(0xFFE5484D))
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(text, color = value, fontSize = valueSp)
        }
    }

    Column {
        Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
        Text(stringResource(R.string.si_section), color = playerFg(), fontSize = labelSp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        // video / audio / decoder
        val video = listOfNotNull(
            StreamDetails.codecName(d.videoCodec),
            if (d.width > 0 && d.height > 0) "${d.width}×${d.height}" else null,
            if (d.fps > 0) (if (kotlin.math.abs(d.fps - Math.round(d.fps)) < 0.01) "${Math.round(d.fps)} fps"
                else String.format(java.util.Locale.US, "%.2f fps", d.fps)) else null
        ).joinToString(" · ")
        val decoderText = when (d.decoder) {
            StreamDetails.DEC_SOFTWARE -> stringResource(R.string.hw_decode_off)
            StreamDetails.DEC_DECODE -> stringResource(R.string.hw_decode_decode)
            else -> stringResource(R.string.si_hardware)
        }
        row(stringResource(R.string.si_video), if (compact) listOf(video, decoderText).filter { it.isNotBlank() }.joinToString(" · ") else video)
        val audio = listOfNotNull(
            StreamDetails.codecName(d.audioCodec),
            if (d.audioChannels > 0) stringResource(R.string.si_channels, d.audioChannels) else null,
            if (d.audioRate > 0) "${d.audioRate / 1000} kHz" else null
        ).joinToString(" · ")
        row(stringResource(R.string.si_audio), audio)
        if (!compact) row(stringResource(R.string.si_decoder), decoderText)
        // source (HTSP only)
        val src = d.source
        if (src != null) {
            Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
            if (compact) {
                row(stringResource(R.string.si_service), listOfNotNull(src.service, src.provider).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null })
            } else {
                row(stringResource(R.string.si_service), src.service)
                row(stringResource(R.string.si_provider), src.provider)
            }
            row(stringResource(R.string.si_mux), listOfNotNull(src.network, src.mux, src.satpos).joinToString(" · ").ifBlank { null })
            row(stringResource(R.string.si_adapter), src.adapter)
        }
        val sig = d.signal
        if (sig != null) {
            Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
            val decibel = sig.signal < 0 && sig.signal != -1 || (sig.signal == -1 && sig.snr in 1..25000)
            if (sig.signal != -1) {
                if (decibel) {   // dBm × 1000: -90 dBm = empty, -30 dBm = full
                    val dbm = sig.signal / 1000.0
                    bar(stringResource(R.string.si_signal), (((dbm + 90.0) / 60.0) * 100).toInt().coerceIn(0, 100),
                        String.format(java.util.Locale.US, "%.1f dBm", dbm))
                } else {
                    val pct = (sig.signal.coerceIn(0, 65535) * 100) / 65535
                    bar(stringResource(R.string.si_signal), pct, "$pct %")
                }
            }
            if (sig.snr >= 0) {
                if (decibel) {   // dB × 1000: 30 dB = full
                    val db = sig.snr / 1000.0
                    bar(stringResource(R.string.si_snr), ((db / 30.0) * 100).toInt().coerceIn(0, 100),
                        String.format(java.util.Locale.US, "%.1f dB", db))
                } else {
                    val pct = (sig.snr.coerceIn(0, 65535) * 100) / 65535
                    bar(stringResource(R.string.si_snr), pct, "$pct %")
                }
            }
            if (sig.ber >= 0 || sig.unc >= 0) {
                row(stringResource(R.string.si_errors), listOfNotNull(
                    if (sig.ber >= 0) "BER ${sig.ber}" else null,
                    if (sig.unc >= 0) "UNC ${sig.unc}" else null
                ).joinToString(" · "))
            }
        }
        // connection
        Spacer(Modifier.height(if (compact) 3.dp else 6.dp))
        row(stringResource(R.string.si_connection), listOfNotNull(
            if (d.htsp) "HTSP" else "HTTP",
            if (d.live && d.profile.isNotBlank()) d.profile else null,
            if (d.bitrateKbps > 0) (if (d.bitrateKbps >= 1000) String.format(java.util.Locale.US, "%.1f Mbit/s", d.bitrateKbps / 1000.0) else "${d.bitrateKbps} kbit/s") else null
        ).joinToString(" · "))
    }
}
