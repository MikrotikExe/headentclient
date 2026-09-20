package sk.tvhclient.android

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sk.tvhclient.shared.model.TvhServer

/**
 * M606: "Ask for the recording profile" (Settings → Playback, off by default).
 *
 * Tvheadend has DVR profiles (path on disk, retention period, stream profile…) and
 * some users use them to sort recordings into folders. Until now the app always
 * recorded into the profile selected for the server (M486). With the option on,
 * pressing "Record" shows the list of profiles from the server (only if there are at least two);
 * the cursor stands on the last used one. Cancelling a recording does not ask.
 */
object DvrAskPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "dvr_ask_profile"
    private const val KEY_LAST = "dvr_ask_last_"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    fun lastUsed(context: Context, serverId: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST + serverId, null)

    fun setLastUsed(context: Context, serverId: String, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST + serverId, name).apply()
    }
}

object DvrProfileAsk {
    private val cache = HashMap<String, List<String>>()

    /**
     * The list of profiles to choose from, or empty when there is no need to ask (the option is off,
     * the server has only one profile, or the list could not be loaded — then it records
     * as before, without asking). Order: the last used one (or the profile from the
     * server) first, then the server order.
     */
    suspend fun options(context: Context, server: TvhServer): List<String> {
        if (!DvrAskPref.get(context)) return emptyList()
        val names = cache[server.id] ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { sk.tvhclient.shared.Tvh.dvrConfigs(server).map { it.name }.filter { it.isNotBlank() } }
                .getOrDefault(emptyList())
        }.also { if (it.isNotEmpty()) cache[server.id] = it }
        if (names.size < 2) return emptyList()
        val pref = DvrAskPref.lastUsed(context, server.id)?.takeIf { it in names }
            ?: server.dvrConfig.takeIf { it in names }
        return if (pref != null) listOf(pref) + names.filter { it != pref } else names
    }

    fun clear(serverId: String) { cache.remove(serverId) }
}

/**
 * The "Record into profile" dialog. `selected` = the index of the highlighted item (D-pad on TV),
 * on a phone it is clicked directly. The first item is the last used one.
 */
@Composable
fun DvrProfilePickDialog(
    options: List<String>,
    subtitle: String,
    lastUsed: String?,
    selected: Int,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    dpad: Boolean = false
) {
    val firstFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    if (dpad) androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    // dpad = screens outside the player (grid, detail): its own window, which handles
    // both BACK and D-pad focus itself; the player (dpad = false) draws the overlay itself and
    // processes keys in its own dispatchKeyEvent
    if (dpad) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) { PickBody(options, subtitle, lastUsed, selected, onPick, onDismiss, dpad, firstFocus) }
    } else PickBody(options, subtitle, lastUsed, selected, onPick, onDismiss, dpad, firstFocus)
}

@Composable
private fun PickBody(
    options: List<String>,
    subtitle: String,
    lastUsed: String?,
    selected: Int,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    dpad: Boolean,
    firstFocus: androidx.compose.ui.focus.FocusRequester
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC0B1220))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth(0.8f).widthIn(max = 460.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1B2433))
                .pointerInput(Unit) { detectTapGestures { } }   // a click in the dialog does not close it
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Text(
                stringResource(R.string.dvr_pick_title), color = Color.White,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle, color = Color(0xFFB9C2D0), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { i, name ->
                    val sel = i == selected
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (sel) Color(0x553B82F6) else Color.Transparent)
                            .border(1.dp, if (sel) Color(0xFF3B82F6) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                            .then(if (dpad && i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .then(if (dpad) Modifier.dpadFocusable(RoundedCornerShape(12.dp)) else Modifier)
                            .clickable { onPick(name) }
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        Text(
                            if (name == lastUsed) name + "  ·  " + stringResource(R.string.dvr_pick_last) else name,
                            color = if (sel) Color.White else Color(0xFFB9C2D0),
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
