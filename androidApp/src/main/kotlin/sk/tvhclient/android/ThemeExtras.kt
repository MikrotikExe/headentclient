package sk.tvhclient.android

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * An optional "solid backdrop" for the information bar in modern mode. When it is on, the
 * overlay has a fully opaque backdrop under the info area (a subtle fade at the top), so the
 * bar is readable even over bright video — in both the light and the dark theme. Off by default.
 */
object ModernOverlayPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "modern_overlay_solid_bg"
    private var state: androidx.compose.runtime.MutableState<Boolean>? = null
    private fun load(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    /** Live state — reading .value in a @Composable redraws the overlay immediately after a change. */
    fun stateOf(c: Context): androidx.compose.runtime.MutableState<Boolean> =
        state ?: androidx.compose.runtime.mutableStateOf(load(c)).also { state = it }
    fun isSolidBg(c: Context): Boolean = stateOf(c).value
    fun setSolidBg(c: Context, on: Boolean) {
        stateOf(c).value = on
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/** True if the light scheme is active (by surface brightness) — works with a manual switch too. */
@Composable
fun isLightTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() > 0.5f

/** True if the modern interface mode is on (UiModePref) — reacts live to a change. */
@Composable
fun isModernUi(): Boolean =
    UiModePref.stateOf(LocalContext.current).value == UiModePref.MODERN

/**
 * The background under a picon (channel/radio logo). Picons are designed for a dark background,
 * so in light mode we put a neutral grey field underneath, so that white logos do not disappear.
 * In dark mode a subtle light overlay as before.
 */
@Composable
fun piconBackground(): Color {
    val ctx = LocalContext.current
    return when (val v = PiconBgPref.stateOf(ctx).value) {
        PiconBgPref.DEFAULT ->
            if (isLightTheme()) Color(0xFFA2A8B4) else Color(0xFF353B47)
        PiconBgPref.TRANSPARENT -> Color.Transparent
        else -> runCatching { Color(android.graphics.Color.parseColor(v)) }
            .getOrElse { if (isLightTheme()) Color(0xFFA2A8B4) else Color(0xFF353B47) }
    }
}

// --- Player overlay colours ---
// In dark mode they return exactly the original values (visually unchanged),
// in light mode dark text / light panels (readable over video).
@Composable fun playerFg(): Color =
    if (isLightTheme()) Color(0xDE000000) else Color.White
@Composable fun playerFgDim(): Color =
    if (isLightTheme()) Color(0x99000000) else Color(0xCCFFFFFF)
@Composable fun playerFgFaint(): Color =
    if (isLightTheme()) Color(0x66000000) else Color(0x99FFFFFF)
@Composable fun playerTrack(): Color =
    if (isLightTheme()) Color(0x33000000) else Color(0x55FFFFFF)
@Composable fun playerScrim(): Color = when {
    isModernUi() && isLightTheme() -> Color(0xF2F0F4FB)
    isModernUi() -> Color(0xF20A1124)
    isLightTheme() -> Color(0xF2F2F2F6)
    else -> Color(0xE6000000)
}
@Composable fun playerScrimSoft(): Color = when {
    isModernUi() && isLightTheme() -> Color(0xC0F0F4FB)
    isModernUi() -> Color(0x990A1124)
    isLightTheme() -> Color(0xC0F2F2F6)
    else -> Color(0x99000000)
}

// --- Cards and frames (EPG browser) ---
@Composable fun playerBorder(): Color =
    if (isLightTheme()) Color(0x1F000000) else Color(0x33FFFFFF)
@Composable fun playerCard(): Color =
    if (isLightTheme()) Color(0x0D000000) else Color(0x14FFFFFF)
/** Player accent: classic blue; modern mode teal (dark/light variant). */
@Composable fun playerAccent(): Color = when {
    isModernUi() && isLightTheme() -> Color(0xFF0F8A63)
    isModernUi() -> Color(0xFF1D9E75)
    else -> Color(0xFF1E88E5)
}
// --- Surf overlay (modern TV live panel, M351): light/dark variants ---
/** Vertical gradient scrim under the cards. */
@Composable fun overlayScrim(): List<Color> = if (isLightTheme())
    listOf(Color(0x00F0F4FB), Color(0xD9F0F4FB), Color(0xF7F0F4FB))
    else listOf(Color(0x000A1124), Color(0xD90A1124), Color(0xF70A1124))

/** "Solid backdrop": a FULLY opaque flat panel under the whole bar (hint + cards +
 *  controls), so that everything is readable over any video. For both themes. */
@Composable fun overlaySolidPanel(): Color =
    if (isLightTheme()) Color(0xFFEEF2FA) else Color(0xFF0A1124)
/** Card/pill backdrop (unfocused). */
@Composable fun overlaySurface(): Color =
    if (isLightTheme()) Color(0xFFEAF0F9) else Color(0xFF13234A)
/** Card backdrop when focused. */
@Composable fun overlaySurfaceFocus(): Color =
    if (isLightTheme()) Color(0xFFE4ECF7) else Color(0xFF12294E)
/** Backdrop of an unfocused card with slight transparency (large channel cards). */
@Composable fun overlayCard(): Color =
    if (isLightTheme()) Color(0xEAEAF0F9) else Color(0xE60F1E3D)
/** Outline of unfocused elements. */
@Composable fun overlayOutline(): Color =
    if (isLightTheme()) Color(0xFFC4D2E8) else Color(0xFF27407A)
/** Outline of an unfocused large card. */
@Composable fun overlayCardOutline(): Color =
    if (isLightTheme()) Color(0xFFC4D2E8) else Color(0xFF1E3A6E)
/** Text of hints and secondary labels in the overlay. */
@Composable fun overlayHint(): Color =
    if (isLightTheme()) Color(0xFF5A6B85) else Color(0xFF8FA6C8)
/** Track of progress bars in the overlay. */
@Composable fun overlayTrack(): Color =
    if (isLightTheme()) Color(0xFFC4D2E8) else Color(0xFF1B2C52)
/** "Live" highlight during timeshift. */
@Composable fun overlayLive(): Color =
    if (isLightTheme()) Color(0xFF0F8A63) else Color(0xFF7FE3BF)
/** Colour of the icon inside the play button (contrast against teal). */
@Composable fun overlayOnAccent(): Color =
    if (isLightTheme()) Color.White else Color(0xFF04120C)
/** Outline of the play button when focused. */
@Composable fun overlayPlayFocusRing(): Color =
    if (isLightTheme()) Color(0xFF0F8A63) else Color(0xFF7FE3BF)

@Composable fun playerSelTint(): Color = when {
    isModernUi() && isLightTheme() -> Color(0x1F0F8A63)
    isModernUi() -> Color(0x331D9E75)
    isLightTheme() -> Color(0x1F1E88E5)
    else -> Color(0x331E88E5)
}
