package sk.tvhclient.android

import android.content.Context

/**
 * Audio-track preference: up to 3 languages in priority order. The player picks
 * the first available one (e.g. SK -> CZ -> EN). An empty list = automatic
 * (leaves the choice to the server/VLC). Stored in SharedPreferences as CSV codes.
 */
object AudioPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "audio_langs"

    /** Default: if nothing is stored, derive it from the app/system language
     *  (1st slot = that language, the rest empty). Returns the codes in order (may
     *  contain empty slots — the player ignores them). */
    fun get(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        if (raw != null) return raw.split(",").map { it.trim() }
        return defaultFromLocale(context)
    }

    /** Preset by language: the app language (if one is chosen in Settings),
     *  otherwise the system language, in the 1st slot; the other slots empty. If the language
     *  is not in the menu, leave everything empty (= automatic). */
    fun defaultFromLocale(context: Context): List<String> {
        val appLang = LocaleHelper.getLang(context)
        val raw = if (appLang.isNotBlank()) appLang
                  else (java.util.Locale.getDefault().language ?: "")
        // historical Android codes -> codes used in the audio menu
        val code = when (raw) { "in" -> "id"; "iw" -> "he"; "ji" -> "yi"; else -> raw }
        val supported = code.isNotBlank() && options.any { it.first == code }
        return if (supported) listOf(code, "", "") else listOf("", "", "")
    }

    fun set(context: Context, langs: List<String>) {
        // keep the slot positions (including empty ones), comma-separated
        val csv = langs.joinToString(",")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, csv).apply()
    }

    /** Language menu for selection (code -> displayed name). "" = not selected. */
    val options: List<Pair<String, String>> = listOf(
        "" to "—",
        "id" to "Bahasa Indonesia",
        "cs" to "Čeština",
        "de" to "Deutsch",
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "hr" to "Hrvatski",
        "it" to "Italiano",
        "hu" to "Magyar",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "pt" to "Português",
        "ro" to "Română",
        "sk" to "Slovenčina",
        "sl" to "Slovenščina",
        "vi" to "Tiếng Việt",
        "tr" to "Türkçe",
        "el" to "Ελληνικά",
        "bg" to "Български",
        "ru" to "Русский",
        "sr" to "Српски",
        "uk" to "Українська",
        "ur" to "اردو",
        "ar" to "العربية",
        "fa" to "فارسی",
        "hi" to "हिन्दी",
        "bn" to "বাংলা",
        "th" to "ไทย",
        "ko" to "한국어",
        "zh" to "中文",
        "ja" to "日本語"
    )

    private fun stripAccents(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            sb.append(
                when (c) {
                    'á','ä','à','â' -> 'a'; 'č','ç' -> 'c'; 'ď' -> 'd'
                    'é','ě','è','ê' -> 'e'; 'í','ì','î' -> 'i'; 'ľ','ĺ' -> 'l'
                    'ň' -> 'n'; 'ó','ô','ö' -> 'o'; 'ŕ','ř' -> 'r'
                    'š','ś' -> 's'; 'ť' -> 't'; 'ú','ů','ü' -> 'u'
                    'ý' -> 'y'; 'ž','ź' -> 'z'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun tokens(code: String): List<String> = when (code) {
        "sk" -> listOf("slovak", "sloven", "slk", "slo")
        "cs" -> listOf("czech", "cesk", "cze", "ces")
        "en" -> listOf("english", "anglick", "eng")
        "pl" -> listOf("polish", "polsk", "pol")
        "de" -> listOf("german", "deutsch", "nemeck", "ger", "deu")
        "hu" -> listOf("hungar", "magyar", "madar", "hun")
        "es" -> listOf("spanish", "espan", "spa")
        "fr" -> listOf("french", "francais", "franc", "fra", "fre")
        "it" -> listOf("italian", "italiano", "ita")
        "nl" -> listOf("dutch", "neder", "holland", "nld", "dut")
        "pt" -> listOf("portug", "por")
        "ro" -> listOf("romanian", "roman", "ron", "rum")
        "uk" -> listOf("ukrain", "ukr")
        "ru" -> listOf("russian", "russk", "rus")
        "tr" -> listOf("turkish", "turk", "tur")
        "vi" -> listOf("vietnam", "vie")
        "id" -> listOf("indones", "bahasa", "ind")
        "ar" -> listOf("arabic", "arab", "ara")
        "hi" -> listOf("hindi", "hin")
        "bn" -> listOf("bengali", "bangla", "ben")
        "zh" -> listOf("chinese", "mandarin", "zho", "chi")
        "ja" -> listOf("japanese", "japan", "jpn")
        "ko" -> listOf("korean", "korea", "kor")
        "el" -> listOf("greek", "ell", "gre")
        "fa" -> listOf("persian", "farsi", "fas", "per")
        "sr" -> listOf("serbian", "srpski", "srpsk", "srp", "scc")
        "hr" -> listOf("croatian", "hrvatsk", "hrv", "scr")
        "bg" -> listOf("bulgarian", "bulgar", "bul")
        "sl" -> listOf("slovenian", "slovensc", "slovene", "slv")
        "ur" -> listOf("urdu", "urd")
        "th" -> listOf("thai", "tha")
        else -> emptyList()
    }

    /** Does the track name match the given language? */
    fun matches(trackName: String, code: String): Boolean {
        val n = stripAccents(trackName)
        return tokens(code).any { n.contains(it) }
    }

    // M378: heuristic for tracks with audio description for the blind
    // (audio description / narrated). We never select such tracks automatically —
    // only when the user picks them manually. We recognise the usual markings:
    // "AD" as a standalone word/in brackets, "audio description", "described",
    // "narrat...", "komentar" (CZ/SK audio commentary), "vi"/"vis. impaired".
    private val adWord = Regex("(^|[^a-z])ad([^a-z]|$)")
    fun isDescriptive(trackName: String): Boolean {
        val n = stripAccents(trackName)
        if (n.contains("audio desc") || n.contains("descri")) return true
        if (n.contains("narrat")) return true
        if (n.contains("komentar")) return true
        if (n.contains("visually impaired") || n.contains("vis. imp")) return true
        return adWord.containsMatchIn(n)
    }
}
