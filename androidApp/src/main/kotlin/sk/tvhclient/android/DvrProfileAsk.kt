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
 * M606: „Pýtať sa na profil nahrávania" (Nastavenia → Prehrávanie, predvolene vypnuté).
 *
 * Tvheadend má DVR profily (cesta na disk, doba uchovania, stream profil…) a
 * niektorí používatelia si nimi triedia nahrávky do priečinkov. Appka doteraz
 * nahrávala vždy do profilu zvoleného pri serveri (M486). So zapnutou voľbou sa
 * po stlačení „Nahrať" ukáže zoznam profilov zo servera (len ak sú aspoň dva);
 * kurzor stojí na naposledy použitom. Zrušenie nahrávky sa nepýta.
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
     * Zoznam profilov na výber, alebo prázdny, keď sa netreba pýtať (voľba vypnutá,
     * server má len jeden profil, alebo sa zoznam nepodarilo načítať — vtedy sa
     * nahrá ako doteraz, bez otázky). Poradie: naposledy použitý (alebo profil zo
     * servera) prvý, potom serverové poradie.
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
 * Dialóg „Nahrať do profilu". `selected` = index zvýraznenej položky (D-pad na TV),
 * na telefóne sa klikne priamo. Prvá položka je naposledy použitá.
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
    // dpad = obrazovky mimo prehravaca (mriezka, detail): vlastne okno, ktore rieši
    // BACK aj fokus D-padu samo; prehravac (dpad = false) kresli prekrytie sam a
    // klavesy spracuva vo svojom dispatchKeyEvent
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
                .pointerInput(Unit) { detectTapGestures { } }   // klik v dialogu nezatvara
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
