package sk.tvhclient.shared.model

/**
 * Klasifikator DVR nahravok do top kategorii. Prenos jadra z Enigma2 pluginu
 * (classifier.py _determine_top_cat + _is_series_entry +
 * _guess_top_category_from_keywords + _channel_top_hint).
 *
 * Poradie signalov (ako plugin):
 *  1) dokumentarne kanaly override-uju ct=0/1/2/9
 *  2) explicit DVB ct=2-10 -> kategoria
 *  3) channel hint (detske/sport/hudba/spravodajstvo kanal)
 *  4) detekcia serialu -> Serial
 *  5) ct=1 -> Film
 *  6) ct=5 -> Detske
 *  7) keyword fallback pre ct=0/11
 *  8) film heuristika: rok v nazve "(YYYY)" -> Film
 *
 * Vynechane (advanced, dalsia faza): 1945-titulovy corpus, sub-zanre
 * (Akcny/Krimi/Sci-fi...), IMDb lookup.
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

    val order = listOf(
        FILM, SERIAL, SPORT, NEWS, SHOW, CHILDREN,
        MUSIC, ARTS, DOCUMENTARY, HOBBY, OTHER
    )

    private fun ctToCat(ct: Int): String? = when (ct) {
        2 -> NEWS
        3 -> SHOW
        4 -> SPORT
        5 -> CHILDREN
        6 -> MUSIC
        7 -> ARTS
        8 -> SHOW
        9 -> DOCUMENTARY
        10 -> HOBBY
        else -> null
    }

    private val channelTopHints: List<Pair<String, String>> = listOf(
        "ct :d" to CHILDREN, "ct d-art" to CHILDREN, "ct d/art" to CHILDREN,
        "decko" to CHILDREN, "jojko" to CHILDREN, "minimax" to CHILDREN,
        "cartoon" to CHILDREN, "disney" to CHILDREN, "nick" to CHILDREN,
        "boomerang" to CHILDREN, "baby tv" to CHILDREN, "duck tv" to CHILDREN,
        "sport" to SPORT, "eurosport" to SPORT,
        "cnn" to NEWS, "bbc news" to NEWS, "bbc world" to NEWS, "ta3" to NEWS,
        "ct24" to NEWS, "ct 24" to NEWS, "euronews" to NEWS,
        "ocko" to MUSIC, "now 80" to MUSIC, "mtv" to MUSIC, "vh1" to MUSIC,
        "discovery" to DOCUMENTARY, "viasat history" to DOCUMENTARY,
        "viasat explore" to DOCUMENTARY, "viasat nature" to DOCUMENTARY,
        "viasat true crime" to DOCUMENTARY, "national geographic" to DOCUMENTARY,
        "nat geo" to DOCUMENTARY, "spektrum" to DOCUMENTARY,
        "animal planet" to DOCUMENTARY, "history channel" to DOCUMENTARY,
        "history hd" to DOCUMENTARY, "bbc earth" to DOCUMENTARY,
        "bbc knowledge" to DOCUMENTARY, "love nature" to DOCUMENTARY,
        "docubox" to DOCUMENTARY
    )

    // Filmove kanaly -> ak nie je serial, je to film. (plugin to riesi corpusom;
    // toto je lacny nahradny signal kym corpus nie je portovany)
    private val movieChannelHints: List<String> = listOf(
        "hbo", "cinemax", "cinema", "amc", "filmbox", "film europe", "film+",
        "film +", "filmplus", "kviff", "canal+ film", "canal+ action", "warner tv",
        "axn", "paramount", "viasat film", "epic drama", "kino", "nova cinema",
        "prima max", "joj cinema", "markiza klasik"
    )

    private val fallback: List<Pair<Regex, String>> = listOf(
        Regex("""\b(futbal|hokej|tenis|golf|formula|f1|oktagon|liga|majstrov|rally|cyklist|atletik|box|wrestlin|biatlon|lyzovan|sjazd|mma|ufc|pml)""") to SPORT,
        Regex("""\b(spravodajstvo|spravy|spravi|udalosti|aktualn|reporter|noviny tv|tv noviny|pocasi|uvodnik)""") to NEWS,
        Regex("""\b(rozpravk|pohadk|pre deti|pro deti|pre najmens|kreslen|animovan|loutkov|fidlibum|miniatel|trpaslic|labkova patrol)""") to CHILDREN,
        Regex("""\b(koncert|hudba|hudobn|hudebni|spevok|zpevak|spevak|piesn|pisni|klasick)""") to MUSIC,
        Regex("""\b(magazin|talk show|talkshow|show|soutez|sutaz|reality|farmer|farma|zabavn|estrada|kucharsk|masterchef|top gear|recept)""") to SHOW,
        Regex("""\b(byvani|byvanie|zahrad|zahradka|navrhar|dizajn|design interier|remeselni|stolarsk|truhlarsk|rybarsk)""") to HOBBY,
        Regex("""\b(dokument|documentary|prirod|history|vesmir|national geographic|discovery)""") to DOCUMENTARY
    )

    private val seriesKeywords = listOf(
        "serial", "serie", " diel ", " dil ", "epizoda", "season ", "episode "
    )

    private val subtitleSeries = Regex("""^\s*\d+/\d+\b""")

    private val episodeSuffix = Regex("""\((\d{1,4})\)\s*(?:\([A-Za-z]{1,3}\))?\s*$""")

    private val techMarker = Regex(
        """\s*\(\s*(?:DD5\.1|DTS-HD|DTS-MA|UHD|DTS|5\.1|7\.1|ST|HD|AD|SS|3D|DD|TT|P)(?:\s*[,/]\s*(?:DD5\.1|DTS-HD|DTS-MA|UHD|DTS|5\.1|7\.1|ST|HD|AD|SS|3D|DD|TT|P))*\s*\)\s*""",
        RegexOption.IGNORE_CASE
    )

    private val yearSuffix = Regex("""\((19|20)\d{2}\)""")

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

    private fun hasEpisodeSuffix(title: String): Boolean {
        val clean = techMarker.replace(title, " ").trim()
        val m = episodeSuffix.find(clean) ?: return false
        val n = m.groupValues[1].toIntOrNull() ?: return false
        if (n in 1900..2099) return false
        return n in 1..9999
    }

    private fun isSeriesEntry(entry: DvrEntry): Boolean {
        val subtitle = entry.dispSubtitle.trim()
        if (subtitle.isNotEmpty() && subtitleSeries.containsMatchIn(subtitle)) return true
        if (hasEpisodeSuffix(entry.dispTitle.trim())) return true
        val desc = (entry.dispDescription + " " + subtitle).lowercase()
        for (kw in seriesKeywords) if (desc.contains(kw)) return true
        return false
    }

    private fun channelTopHint(entry: DvrEntry): String? {
        val ch = entry.channelName.lowercase()
        if (ch.isBlank()) return null
        for ((sub, cat) in channelTopHints) if (ch.contains(sub)) return cat
        return null
    }

    fun classify(entry: DvrEntry): String {
        val ct = entry.dvbGenreTop
        val channelTop = channelTopHint(entry)

        if (channelTop == DOCUMENTARY && (ct == 0 || ct == 1 || ct == 2 || ct == 9)) return DOCUMENTARY

        if (ct == 2 || ct == 3 || ct == 4 || ct == 6 || ct == 7 || ct == 8 || ct == 9 || ct == 10) {
            ctToCat(ct)?.let { return it }
        }

        if (channelTop == CHILDREN || channelTop == SPORT ||
            channelTop == MUSIC || channelTop == NEWS) {
            return channelTop
        }

        if (isSeriesEntry(entry)) return SERIAL

        if (ct == 1) return FILM

        if (ct == 5) return CHILDREN

        val text = stripAccentsLower(
            listOf(entry.dispTitle, entry.dispSubtitle, entry.dispDescription, entry.channelName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )
        if (text.isNotBlank()) {
            for ((pattern, cat) in fallback) {
                if (pattern.containsMatchIn(text)) return cat
            }
        }

        if (yearSuffix.containsMatchIn(entry.dispTitle)) return FILM

        // Filmovy kanal + nie serial -> Film (nahrada za corpus)
        val ch = entry.channelName.lowercase()
        if (ch.isNotBlank() && movieChannelHints.any { ch.contains(it) }) return FILM

        return OTHER
    }

    // ----------------------------------------------------------------------
    // Sub-zanre (Level 2). Prebrate z classifier.py _KEYWORD_TO_SUBCAT
    // (film) a _SPORT_KEYWORD_TO_SUBCAT (sport). Pouzite pre Film/Serial/
    // Detske (filmova mapa) a Sport (sportova mapa).
    // ----------------------------------------------------------------------

    // Film/serial/detske sub-zanre
    const val MV_AKCNY = "mv_akcny"
    const val MV_KOMEDIA = "mv_komedia"
    const val MV_KRIMI = "mv_krimi"
    const val MV_DRAMA = "mv_drama"
    const val MV_SCIFI = "mv_scifi"
    const val MV_ROMANTIKA = "mv_romantika"
    const val MV_HOROR = "mv_horor"
    const val MV_DOBRODR = "mv_dobrodruzny"
    const val MV_ANIMAK = "mv_animovany"
    const val MV_HISTORICKY = "mv_historicky"
    const val MV_WESTERN = "mv_western"
    const val MV_INE = "mv_ine"

    val movieSubOrder = listOf(
        MV_AKCNY, MV_KOMEDIA, MV_KRIMI, MV_DRAMA, MV_SCIFI, MV_ROMANTIKA,
        MV_HOROR, MV_DOBRODR, MV_ANIMAK, MV_HISTORICKY, MV_WESTERN, MV_INE
    )

    // poradie = specificke najprv (ako plugin)
    private val movieKeyword: List<Pair<Regex, String>> = listOf(
        Regex("""\b(detektiv|kriminal|krimi|thriller|vraz|policajn|vysetrov)""") to MV_KRIMI,
        Regex("""\b(sci-?fi|sci\.\s?fi|fantasy|vedeckofant|vesmirn|mimozem|robot|kybern)""") to MV_SCIFI,
        Regex("""\b(komedi|veselohra|humor|grotesk|sitcom)""") to MV_KOMEDIA,
        Regex("""\b(romantick|milostn|romant)""") to MV_ROMANTIKA,
        Regex("""\b(akcn|action|honic|prestrelk)""") to MV_AKCNY,
        Regex("""\b(western|kovbo)""") to MV_WESTERN,
        Regex("""\b(historick|valecn|vojensk|vojnov|histori)""") to MV_HISTORICKY,
        Regex("""\b(dobrodruz|adventur|exped|cestopis)""") to MV_DOBRODR,
        Regex("""\b(animovan|kreslen|animak|loutkov|cartoon|anime)""") to MV_ANIMAK,
        Regex("""\b(drama|dramati)""") to MV_DRAMA
    )
    private val horrorTitle = Regex("""\b(horor|horror|hruza|strasidel|zombie|upir|krvav)""")

    // Sport sub-zanre
    const val SP_FUTBAL = "sp_futbal"
    const val SP_HOKEJ = "sp_hokej"
    const val SP_BASKETBAL = "sp_basketbal"
    const val SP_TENIS = "sp_tenis"
    const val SP_VOLEJBAL = "sp_volejbal"
    const val SP_HADZANA = "sp_hadzana"
    const val SP_BOJOVE = "sp_bojove"
    const val SP_ATLETIKA = "sp_atletika"
    const val SP_CYKLISTIKA = "sp_cyklistika"
    const val SP_MOTORSPORT = "sp_motorsport"
    const val SP_ZIMNE = "sp_zimne"
    const val SP_VODNE = "sp_vodne"
    const val SP_NEWS = "sp_news"
    const val SP_INE = "sp_ine"

    val sportSubOrder = listOf(
        SP_FUTBAL, SP_HOKEJ, SP_BASKETBAL, SP_TENIS, SP_VOLEJBAL, SP_HADZANA,
        SP_BOJOVE, SP_ATLETIKA, SP_CYKLISTIKA, SP_MOTORSPORT, SP_ZIMNE,
        SP_VODNE, SP_NEWS, SP_INE
    )

    private val sportKeyword: List<Pair<Regex, String>> = listOf(
        Regex("""\b(sportove noviny|sportovni noviny|sport news|spravy zo sportu|sportovni studio)""") to SP_NEWS,
        Regex("""\b(hokej|hockey|nhl|iihf|khl)""") to SP_HOKEJ,
        Regex("""\b(ufc|mma|oktagon|pml|kickbox|judo|karate|wrestl|zapas|sumo)""") to SP_BOJOVE,
        Regex("""\bbox(er|ing|u|y)?\b""") to SP_BOJOVE,
        Regex("""\b(futbal|football|uefa|nike liga|fortuna liga|premier league|bundesliga|la liga|champions league|europa league|ligue 1|serie a)""") to SP_FUTBAL,
        Regex("""\b(basketbal|nba|wnba|sbl)""") to SP_BASKETBAL,
        Regex("""\b(volejbal|volleyball)""") to SP_VOLEJBAL,
        Regex("""\b(hadzana|handball)""") to SP_HADZANA,
        Regex("""\b(tenis|tennis|atp|wta|grand slam|wimbledon|roland garros)""") to SP_TENIS,
        Regex("""\b(atletik|athletics|maraton)""") to SP_ATLETIKA,
        Regex("""\b(cyklist|cycling|tour de france)""") to SP_CYKLISTIKA,
        Regex("""\b(formula|f1|motogp|moto gp|rally|nascar|motorsport)""") to SP_MOTORSPORT,
        Regex("""\b(lyzov|sjazd|biatlon|snowboard|curling|zjazd)""") to SP_ZIMNE,
        Regex("""\b(plavan|vodne|kanoist|veslov|water polo)""") to SP_VODNE
    )

    /** Ma dana top kategoria sub-zanre? */
    fun hasSubgenres(topCat: String): Boolean =
        topCat == FILM || topCat == SERIAL || topCat == CHILDREN || topCat == SPORT

    /** Su zaznamy danej kategorie serialy (zoskupit epizody pod serial)? */
    fun isSeriesLike(topCat: String): Boolean =
        topCat == SERIAL || topCat == CHILDREN

    fun subOrderFor(topCat: String): List<String> =
        if (topCat == SPORT) sportSubOrder else movieSubOrder

    /** Sub-zaner pre zaznam v danej top kategorii. */
    fun subgenre(entry: DvrEntry, topCat: String): String {
        val text = stripAccentsLower(
            listOf(entry.dispTitle, entry.dispSubtitle, entry.dispDescription)
                .filter { it.isNotBlank() }.joinToString(" ")
        )
        if (topCat == SPORT) {
            for ((p, sub) in sportKeyword) if (p.containsMatchIn(text)) return sub
            return SP_INE
        }
        // film/serial/detske
        if (text.isNotBlank()) {
            for ((p, sub) in movieKeyword) if (p.containsMatchIn(text)) return sub
        }
        val titleOnly = stripAccentsLower(entry.dispTitle)
        if (titleOnly.isNotBlank() && horrorTitle.containsMatchIn(titleOnly)) return MV_HOROR
        return MV_INE
    }

    /** Kanonicky nazov serialu (bez epizodneho sufixu a tech markerov) na
     *  zoskupenie epizod. "Otec Brown IV (1)" -> "Otec Brown IV". */
    fun seriesCanonicalTitle(title: String): String {
        if (title.isBlank()) return ""
        var clean = techMarker.replace(title, " ").trim()
        val m = episodeSuffix.find(clean)
        if (m != null) {
            val n = m.groupValues[1].toIntOrNull()
            if (n != null && n !in 1900..2099) {
                clean = clean.substring(0, m.range.first).trim()
            }
        }
        return clean
    }
}
