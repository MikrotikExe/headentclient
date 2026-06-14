package sk.tvhclient.shared.model

/**
 * Klasifikator DVR nahravok do top kategorii. Prenos jadra z Enigma2 pluginu
 * (classifier.py _determine_top_cat + _guess_top_category_from_keywords).
 *
 * Co je prebrate:
 *  - DVB-SI Level 1 content_type (ct 2-10) -> kategoria (presny broadcaster signal)
 *  - ct=1 -> Film, ct=5 -> Detske
 *  - keyword fallback pre ct=0/11 (undefined) cez title+subtitle+desc+channel
 *
 * Co je zatial vynechane (advanced, do dalsej fazy):
 *  - 1945-titulovy corpus, channel hints, sub-zanre (Akcny/Krimi/...),
 *    series detection, IMDb lookup. Na uzitocne top kategorie netreba.
 *
 * Kategorie (kluce; UI si ich prelozi):
 */
object DvrClassifier {
    const val FILM = "film"
    const val SERIAL = "serial"
    const val SPORT = "sport"
    const val NEWS = "news"
    const val SHOW = "show"
    const val CHILDREN = "children"
    const val MUSIC = "music"
    const val ARTS = "arts"
    const val DOCUMENTARY = "documentary"
    const val HOBBY = "hobby"
    const val OTHER = "other"

    /** Poradie zobrazenia kategorii (ako v pluginnom _CAT_LABELS_ORDER). */
    val order = listOf(
        FILM, SERIAL, SPORT, NEWS, SHOW, CHILDREN,
        MUSIC, ARTS, DOCUMENTARY, HOBBY, OTHER
    )

    // DVB ct (horny nibble) -> kategoria. Z _CT_TO_CAT_BASE.
    private fun ctToCat(ct: Int): String? = when (ct) {
        2 -> NEWS
        3 -> SHOW
        4 -> SPORT
        5 -> CHILDREN
        6 -> MUSIC
        7 -> ARTS
        8 -> SHOW          // Social/Political -> spojene so Show
        9 -> DOCUMENTARY
        10 -> HOBBY
        1 -> FILM
        else -> null       // 0, 11 = undefined
    }

    // Keyword patterny -> top kategoria. Z _FALLBACK_KEYWORD_TO_TOP.
    // Text sa porovnava bez diakritiky, lowercase.
    private val fallback: List<Pair<Regex, String>> = listOf(
        Regex("""\b(futbal|hokej|tenis|golf|formula|f1|oktagon|liga|majstrov|rally|cyklist|atletik|box|wrestlin|biatlon|lyzovan|sjazd|mma|ufc|pml)""") to SPORT,
        Regex("""\b(spravodajstvo|spravy|spravi|udalosti|aktualn|reporter|noviny tv|tv noviny|pocasi|uvodnik)""") to NEWS,
        Regex("""\b(rozpravk|pohadk|pre deti|pro deti|pre najmens|kreslen|animovan|loutkov|fidlibum|miniatel|trpaslic|labkova patrol)""") to CHILDREN,
        Regex("""\b(koncert|hudba|hudobn|hudebni|spevok|zpevak|spevak|piesn|pisni|klasick)""") to MUSIC,
        Regex("""\b(magazin|talk show|talkshow|show|soutez|sutaz|reality|farmer|farma|zabavn|estrada|kucharsk|masterchef|top gear|recept)""") to SHOW,
        Regex("""\b(byvani|byvanie|zahrad|zahradka|navrhar|dizajn|design interier|remeselni|stolarsk|truhlarsk|rybarsk)""") to HOBBY,
        Regex("""\b(dokument|documentary|prirod|history|vesmir|national geographic|discovery)""") to DOCUMENTARY
    )

    /** Odstrani diakritiku a prevedie na lowercase (ako _strip_accents_lower). */
    private fun stripAccentsLower(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            sb.append(
                when (c) {
                    'á', 'ä', 'à', 'â', 'ã', 'å' -> 'a'
                    'č', 'ç', 'ć' -> 'c'
                    'ď' -> 'd'
                    'é', 'ě', 'è', 'ê', 'ë' -> 'e'
                    'í', 'ì', 'î', 'ï' -> 'i'
                    'ĺ', 'ľ', 'ł' -> 'l'
                    'ň', 'ñ' -> 'n'
                    'ó', 'ô', 'ö', 'ò', 'õ', 'ø' -> 'o'
                    'ŕ', 'ř' -> 'r'
                    'š', 'ś' -> 's'
                    'ť' -> 't'
                    'ú', 'ů', 'ü', 'ù', 'û' -> 'u'
                    'ý', 'ÿ' -> 'y'
                    'ž', 'ź', 'ż' -> 'z'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** Vrati top kategoriu pre DVR nahravku. */
    fun classify(entry: DvrEntry): String {
        val ct = entry.dvbGenreTop
        // Explicitny DVB signal ct=2-10 alebo ct=1 (Film)
        ctToCat(ct)?.let { return it }
        // ct=0/11 -> keyword fallback
        val text = stripAccentsLower(
            listOf(entry.dispTitle, entry.dispSubtitle, entry.dispDescription, entry.channelName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )
        if (text.isBlank()) return OTHER
        for ((pattern, cat) in fallback) {
            if (pattern.containsMatchIn(text)) return cat
        }
        return OTHER
    }
}
