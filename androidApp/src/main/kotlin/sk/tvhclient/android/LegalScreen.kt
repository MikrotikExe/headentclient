package sk.tvhclient.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Jednoduchy zobrazitelny pravny dokument (nadpis + uvod + sekcie). */
data class LegalDoc(
    val title: String,
    val meta: String,
    val intro: String,
    val sections: List<Pair<String, String>>
)

/**
 * Celoobrazovkova scrollovatelna obrazovka s pravnym textom (privacy / terms).
 * Skrolovanie D-padom: jeden fokusovatelny kontajner chyta UP/DOWN a posuva
 * ScrollState (na TV boxoch focus-scroll cez prazdny text nefunguje).
 */
@Composable
internal fun LegalScreen(doc: LegalDoc, modifier: Modifier = Modifier, onBack: () -> Unit) {
    BackHandler { onBack() }
    val scroll = rememberScrollState()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxHeight()
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .focusRequester(fr)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { scroll.dispatchRawDelta(220f); true }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> { scroll.dispatchRawDelta(-220f); true }
                        android.view.KeyEvent.KEYCODE_PAGE_DOWN -> { scroll.dispatchRawDelta(700f); true }
                        android.view.KeyEvent.KEYCODE_PAGE_UP -> { scroll.dispatchRawDelta(-700f); true }
                        else -> false
                    }
                }
                .verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text(
                doc.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                doc.meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
            )
            Spacer(Modifier.height(18.dp))
            Text(
                doc.intro,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 22.sp
            )
            doc.sections.forEach { (heading, body) ->
                Spacer(Modifier.height(22.dp))
                Text(
                    heading,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

object LegalText {
    fun privacy(lang: String): LegalDoc = if (lang == "en") PRIVACY_EN else PRIVACY_SK
    fun terms(lang: String): LegalDoc = if (lang == "en") TERMS_EN else TERMS_SK

    private val PRIVACY_SK = LegalDoc(
        title = "Zásady ochrany osobných údajov",
        meta = "Účinné od: 17.6.2026 • Prevádzkovateľ: Juraj Chudý • Kontakt: support@headentclient.com",
        intro = "Headent Client je klientská aplikácia pre Tvheadend — open-source server na živé TV " +
            "vysielanie a nahrávanie. Aplikácia sa pripája k Tvheadend serveru, ku ktorému má " +
            "používateľ prístup a ktorý si sám nakonfiguruje. Aplikácia neposkytuje žiadny " +
            "televízny obsah ani kanály.",
        sections = listOf(
            "Aké údaje aplikácia spracúva" to
                "Aplikácia ukladá lokálne na zariadení nastavenia potrebné na pripojenie k " +
                "serveru: adresu servera (host, port, HTTP/HTTPS), prihlasovacie meno a heslo k " +
                "Tvheadend serveru a predvoľby aplikácie (jazyk, audio stopy, zobrazenie, " +
                "voliteľný PIN rodičovského zámku). Tieto údaje sa ukladajú len v lokálnom " +
                "úložisku zariadenia a neodosielajú sa prevádzkovateľovi aplikácie ani žiadnej " +
                "tretej strane. Prihlasovacie údaje sa posielajú výlučne na server, ktorý " +
                "používateľ sám zadal, za účelom autentifikácie a získania živého vysielania, " +
                "EPG a nahrávok (DVR).",
            "Čo aplikácia nerobí" to
                "• Nezbiera analytiku ani telemetriu.\n• Neobsahuje reklamy.\n• Neobsahuje SDK " +
                "tretích strán na sledovanie.\n• Neodosiela žiadne údaje vývojárovi ani tretím " +
                "stranám.\n• Nepristupuje ku kontaktom, polohe ani fotografiám.",
            "Sieťová komunikácia" to
                "Aplikácia komunikuje iba so serverom (servermi), ktoré používateľ nakonfiguruje. " +
                "Keďže Tvheadend servery v lokálnej sieti často nemajú TLS, aplikácia umožňuje aj " +
                "nešifrované (HTTP) spojenie — voľbu protokolu určuje používateľ.",
            "Povolenia" to
                "INTERNET — pripojenie k serveru. RECEIVE_BOOT_COMPLETED, SYSTEM_ALERT_WINDOW a " +
                "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS sú voliteľné a použijú sa len ak si " +
                "používateľ sám zapne automatické spustenie po štarte (typické pre TV boxy).",
            "Uchovávanie a vymazanie údajov" to
                "Všetky údaje sú uložené len lokálne. Odinštalovaním aplikácie sa všetky uložené " +
                "údaje (vrátane prihlasovacích údajov a PIN-u) odstránia.",
            "Deti" to "Aplikácia nie je určená deťom a nezbiera údaje o deťoch.",
            "Kontakt" to "support@headentclient.com"
        )
    )

    private val PRIVACY_EN = LegalDoc(
        title = "Privacy Policy",
        meta = "Effective date: 17 June 2026 • Operator: Juraj Chudý • Contact: support@headentclient.com",
        intro = "Headent Client is a client application for Tvheadend — an open-source server for live " +
            "TV and recording. The app connects to a Tvheadend server that the user has access " +
            "to and configures themselves. The app does not provide any TV content or channels.",
        sections = listOf(
            "Data the app processes" to
                "The app stores locally on the device the settings needed to connect to the " +
                "server: server address (host, port, HTTP/HTTPS), username and password for the " +
                "Tvheadend server, and app preferences (language, audio tracks, view mode, " +
                "optional parental PIN). This data is stored only in local storage and is never " +
                "sent to the app's operator or any third party. Credentials are sent solely to " +
                "the server the user configured, to authenticate and retrieve live streams, EPG " +
                "and recordings (DVR).",
            "What the app does not do" to
                "• No analytics or telemetry.\n• No advertising.\n• No third-party tracking " +
                "SDKs.\n• No data sent to the developer or third parties.\n• No access to " +
                "contacts, location or photos.",
            "Network" to
                "The app communicates only with the server(s) the user configures. Because " +
                "Tvheadend servers on a local network often lack TLS, the app also allows " +
                "unencrypted (HTTP) connections — the protocol is chosen by the user.",
            "Permissions" to
                "INTERNET — connect to the server. RECEIVE_BOOT_COMPLETED, SYSTEM_ALERT_WINDOW " +
                "and REQUEST_IGNORE_BATTERY_OPTIMIZATIONS are optional and used only if the user " +
                "enables auto-start on boot (common on TV boxes).",
            "Data retention and deletion" to
                "All data is stored locally only. Uninstalling the app removes all stored data " +
                "(including credentials and PIN).",
            "Children" to "The app is not directed to children and does not collect data about children.",
            "Contact" to "support@headentclient.com"
        )
    )

    private val TERMS_SK = LegalDoc(
        title = "Podmienky používania",
        meta = "Účinné od: 17.6.2026 • Poskytovateľ: Juraj Chudý • Kontakt: support@headentclient.com",
        intro = "Headent Client je klient pre Tvheadend — open-source server na živé TV vysielanie a " +
            "nahrávanie. Používaním aplikácie súhlasíte s týmito podmienkami.",
        sections = listOf(
            "Účel aplikácie" to
                "Aplikácia slúži výhradne na pripojenie k Tvheadend serveru, ku ktorému máte " +
                "oprávnený prístup a ktorý si sami nakonfigurujete. Aplikácia sama neposkytuje, " +
                "nehosťuje ani nedistribuuje žiadny televízny obsah ani kanály.",
            "Zodpovednosť používateľa" to
                "Za server, ku ktorému sa pripájate, a za obsah na ňom dostupný zodpovedá " +
                "výhradne jeho prevádzkovateľ, resp. používateľ. Aplikáciu ste povinní používať " +
                "v súlade s platnými zákonmi a s právami tretích strán. Nesmie sa používať na " +
                "neoprávnený prístup k obsahu ani na porušovanie autorských práv.",
            "Bez záruky" to
                "Aplikácia je poskytovaná „tak ako je\" (as-is), bez akýchkoľvek záruk. " +
                "Poskytovateľ nezodpovedá za škody vzniknuté používaním aplikácie, za dostupnosť " +
                "alebo funkčnosť servera používateľa, ani za obsah získaný z tohto servera.",
            "Kontakt" to "support@headentclient.com"
        )
    )

    private val TERMS_EN = LegalDoc(
        title = "Terms of Use",
        meta = "Effective date: 17 June 2026 • Provider: Juraj Chudý • Contact: support@headentclient.com",
        intro = "Headent Client is a client for Tvheadend — an open-source server for live TV and " +
            "recording. By using the app you agree to these terms.",
        sections = listOf(
            "Purpose" to
                "The app is used solely to connect to a Tvheadend server that you are authorized " +
                "to access and configure yourself. The app does not provide, host or distribute " +
                "any TV content or channels.",
            "User responsibility" to
                "The server you connect to and the content available on it are the sole " +
                "responsibility of its operator/user. You must use the app in compliance with " +
                "applicable laws and third-party rights. It must not be used for unauthorized " +
                "access to content or copyright infringement.",
            "No warranty" to
                "The app is provided \"as is\", without warranties of any kind. The provider is " +
                "not liable for any damages arising from use of the app, for the availability or " +
                "functioning of the user's server, or for content obtained from that server.",
            "Contact" to "support@headentclient.com"
        )
    )
}
