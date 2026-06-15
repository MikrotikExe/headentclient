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
        Regex("""\b(koncert|hudba|hudobn|hudebni|spevok|zpevak|spevak|piesn|pisni|klasick|jazzfest|jazz fest)""") to MUSIC,
        Regex("""\b(magazin|talk show|talkshow|show|soutez|sutaz|reality|farmer|farma|zabavn|estrada|kucharsk|masterchef|top gear|recept|policie v akci|particka|zamena manzeliek|vymena manzeliek|ano sefe|prostreno|inkognito|nebezpecne vztahy|moja mama vari|s pravdou von|zachranari|na chalupe)""") to SHOW,
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
    // Sub-zanre (Level 2). Prebrate z classifier.py keyword map pre kazdu
    // kategoriu. Pouzite: Film/Serial (movie), Sport, Spravodajstvo, Sou,
    // Detske, Hudba, Umenie, Dokumenty, Hobby.
    // ----------------------------------------------------------------------

    // -- Film/Serial sub-zanre --
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
    private val movieKeyword: List<Pair<Regex, String>> = listOf(
        Regex("""\b(detektiv|kriminal|krimi|thriller|vraz|policajn|vysetrov)""") to MV_KRIMI,
        Regex("""\b(sci-?fi|sci\.\s?fi|fantasy|vedeckofant|vesmirn|mimozem|robot|kybern)""") to MV_SCIFI,
        Regex("""\b(komedi|veselohra|humor|grotesk|sitcom)""") to MV_KOMEDIA,
        Regex("""\b(romantick|milostn|romant|zamilovan)""") to MV_ROMANTIKA,
        Regex("""\b(akcn|action|honic|prestrelk|katastrof|komiks|superhrdin)""") to MV_AKCNY,
        Regex("""\b(western|kovbo)""") to MV_WESTERN,
        Regex("""\b(historick|valecn|vojensk|vojnov|histori|stredovek)""") to MV_HISTORICKY,
        Regex("""\b(dobrodruz|adventur|exped|cestopis)""") to MV_DOBRODR,
        Regex("""\b(animovan|kreslen|animak|loutkov|cartoon|anime)""") to MV_ANIMAK,
        Regex("""\b(drama|dramati)""") to MV_DRAMA
    )
    private val horrorTitle = Regex("""\b(horor|horror|hruza|strasidel|zombie|upir|krvav)""")

    // ---- Korpus titulov (~1945) + DVB full-byte mapa (port z classifier.py) ----
    // Korpus: kanonicky nazov -> MV_ konstanta. Plni ho platforma zo zdroja
    // (Android asset title_genre_corpus.json) cez setCorpus().
    private var corpus: Map<String, String> = emptyMap()
    fun setCorpus(m: Map<String, String>) { corpus = m }
    fun hasCorpus(): Boolean = corpus.isNotEmpty()

    // Plny DVB genre bajt (0x11-0x18) -> sub-kategoria. Dostupne ak server dava
    // full byte (HTSP s minor nibblom); HTTP dava len major, takze sa nepouzije.
    private val dvbGenreToSubcat: Map<Int, String> = mapOf(
        0x11 to MV_KRIMI, 0x12 to MV_DOBRODR, 0x13 to MV_SCIFI, 0x14 to MV_KOMEDIA,
        0x15 to MV_DRAMA, 0x16 to MV_ROMANTIKA, 0x17 to MV_HISTORICKY, 0x18 to MV_DRAMA
    )

    private val yearSuffixEnd = Regex("""\s*\(\s*(?:19|20)\d{2}\s*\)\s*$""")

    /** Kanonizacia nazvu pre korpus lookup (musi ladit s tvorbou korpus JSON). */
    private fun canonicalTitleForCorpus(title: String): String {
        if (title.isBlank()) return ""
        val noEp = seriesCanonicalTitle(title)        // strip tech markery + epizodny sufix
        val noYear = yearSuffixEnd.replace(noEp, "").trim()
        return stripAccentsLower(noYear)
    }

    // -- Sport sub-zanre --
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

    // -- Spravodajstvo sub-zanre --
    const val NW_HLAVNE = "nw_hlavne"
    const val NW_POLITIKA = "nw_politika"
    const val NW_KRIMI = "nw_krimi"
    const val NW_MAGAZINY = "nw_magaziny"
    const val NW_POCASIE = "nw_pocasie"
    const val NW_INE = "nw_ine"
    val newsSubOrder = listOf(NW_HLAVNE, NW_POLITIKA, NW_KRIMI, NW_MAGAZINY, NW_POCASIE, NW_INE)
    private val newsKeyword: List<Pair<Regex, String>> = listOf(
        Regex("""\b(pocasi|predpoved|predpovid)""") to NW_POCASIE,
        Regex("""\b(krimi noviny|reporter|reportaz|investigativ|tajomstv|kriminal)""") to NW_KRIMI,
        Regex("""\b(politik|diskusia|diskuse|debata|otazky vaclava|studio 6|polemika|interview plus|partia)""") to NW_POLITIKA,
        Regex("""\b(spravodajsky magazin|reflex|7 dni|plus 7|fokus|profil|lifestyle)""") to NW_MAGAZINY,
        Regex("""\b(noviny|spravy|spravi|udalosti|hlavni sprav|hlavne sprav|tv noviny|telerano|spravodajstv)""") to NW_HLAVNE
    )

    // -- Sou sub-zanre --
    const val SH_REALITY = "sh_reality"
    const val SH_TALK = "sh_talk"
    const val SH_SUTAZ = "sh_sutaz"
    const val SH_KUCHARSKE = "sh_kucharske"
    const val SH_ZABAVA = "sh_zabava"
    const val SH_MAGAZINY = "sh_magaziny"
    const val SH_INE = "sh_ine"
    val showSubOrder = listOf(SH_REALITY, SH_TALK, SH_SUTAZ, SH_KUCHARSKE, SH_ZABAVA, SH_MAGAZINY, SH_INE)
    private val showKeyword: List<Pair<Regex, String>> = listOf(
        Regex("""\b(kucharsk|masterchef|hell|ano sefe|jamie oliver|recept|kuchar|gordon ramsay)""") to SH_KUCHARSKE,
        Regex("""\b(reality|farmer|farma|survivor|big brother|rande|love island|prezit|holky z)""") to SH_REALITY,
        Regex("""\b(talent|x factor|got talent|the voice|superstar|tvoja tvar|dancing with|stardance|lets dance)""") to SH_SUTAZ,
        Regex("""\b(talk show|talkshow|jana krausa|late night|kraus|particka|cestou necestou)""") to SH_TALK,
        Regex("""\b(magazin|reflex|zivot v luxuse|5 proti 5|inkognito|klic|lifestyle|polopate)""") to SH_MAGAZINY,
        Regex("""\b(zabavn|humor|estrad|skecz|stand-?up|parodi|sranda|veselohra|kabaret|satira)""") to SH_ZABAVA
    )

    // -- Detske sub-zanre --
    const val CH_ANIMAK = "ch_animak"
    const val CH_ROZPRAVKY = "ch_rozpravky"
    const val CH_VZDELAVAC = "ch_vzdelavac"
    const val CH_FILMY = "ch_filmy"
    const val CH_INE = "ch_ine"
    val childrenSubOrder = listOf(CH_ANIMAK, CH_ROZPRAVKY, CH_VZDELAVAC, CH_FILMY, CH_INE)
    private val childrenKeyword: List<Pair<Regex, String>> = listOf(
        Regex("""\b(rozpravk|pohadk|princ|princezn|kralovstvo|carodej)""") to CH_ROZPRAVKY,
        Regex("""\b(animovan|kreslen|loutkov|cartoon|anime|animak)""") to CH_ANIMAK,
        Regex("""\b(kouzeln. skolk|studio kamar|vzdelavac|vyuka|naucn|edukacn|do skoly)""") to CH_VZDELAVAC,
        Regex("""\b(detsky film|pre deti film|family film|rodinny film)""") to CH_FILMY
    )

    // -- Hudba sub-zanre --
    const val MU_KLASIKA = "mu_klasika"
    const val MU_KONCERT = "mu_koncert"
    const val MU_HITY = "mu_hity"
    const val MU_FOLK = "mu_folk"
    const val MU_MAGAZINY = "mu_magaziny"
    const val MU_INE = "mu_ine"
    val musicSubOrder = listOf(MU_KLASIKA, MU_KONCERT, MU_HITY, MU_FOLK, MU_MAGAZINY, MU_INE)
    private val musicKeyword: List<Pair<Regex, String>> = listOf(
        Regex("""\b(klasick. hudb|opera|symfoni|filharmon|orchester|orchestr|arie|smetanova|ma vlast)""") to MU_KLASIKA,
        Regex("""\b(koncert|live concert|tour|mtv live|unplugged)""") to MU_KONCERT,
        Regex("""\b(folk|country|ludova hudba|lidova hudba|cimbal|ludovk|lidovk|folklor)""") to MU_FOLK,
        Regex("""\b(hitparad|chart|charts|pop|popmusic|videoklip)""") to MU_HITY,
        Regex("""\b(hudobn. magaz|music news|hudobnik)""") to MU_MAGAZINY
    )

    // -- Umenie sub-zanre --
    const val AR_DIVADLO = "ar_divadlo"
    const val AR_VYTVARNE = "ar_vytvarne"
    const val AR_LITERATURA = "ar_literatura"
    const val AR_FILM = "ar_film"
    const val AR_INE = "ar_ine"
    val artsSubOrder = listOf(AR_DIVADLO, AR_VYTVARNE, AR_LITERATURA, AR_FILM, AR_INE)
    private val artsKeyword: List<Pair<Regex, String>> = listOf(
        Regex("""\b(divadl|theater|inscenace|cinohra|opera plus|baletn|cinoherni)""") to AR_DIVADLO,
        Regex("""\b(vytvarn|malba|maliarstv|socharst|galeri|umelci|umelec|art gallery|vystav)""") to AR_VYTVARNE,
        Regex("""\b(literatur|literar|knih|kniha|spisovate|roman|prozaik|poezi|basen|kniznic)""") to AR_LITERATURA,
        Regex("""\b(filmov. umen|filmov. klasik|filmovi tvorco|reziser|kameraman|filmari)""") to AR_FILM
    )

    // -- Dokumenty sub-zanre --
    const val DC_PRIRODA = "dc_priroda"
    const val DC_HISTORIA = "dc_historia"
    const val DC_VEDA = "dc_veda"
    const val DC_CESTOPIS = "dc_cestopis"
    const val DC_OSOBNOSTI = "dc_osobnosti"
    const val DC_SPOLOCNOST = "dc_spolocnost"
    const val DC_INE = "dc_ine"
    val docSubOrder = listOf(DC_PRIRODA, DC_HISTORIA, DC_VEDA, DC_CESTOPIS, DC_OSOBNOSTI, DC_SPOLOCNOST, DC_INE)
    private val docKeyword: List<Pair<Regex, String>> = listOf(
        Regex("""\b(prirod|zviera|zvire|zivocich|fauna|flora|narodny park|narodni park|safari|ocean|dzungla|tiger|delfin|velryba|animal planet)""") to DC_PRIRODA,
        Regex("""\b(histori|dejiny|stredovek|archeo|antick|stara civiliza|imperi|cisar|kral|pyramid|rimsk)""") to DC_HISTORIA,
        Regex("""\b(veda|vedeck|fyzik|chemi|biolog|matematik|technika|technolog|vesmir|kozmos|planeta|nasa|raketa|vynalez|umela inteligenci)""") to DC_VEDA,
        Regex("""\b(cestopis|cesty|krajiny|cestovate|expedici|expedice|geografi|narody sveta)""") to DC_CESTOPIS,
        Regex("""\b(biografi|portret osob|osobnost|zivotopis|zivot a dielo|pamati|memoare|spomienky na)""") to DC_OSOBNOSTI,
        Regex("""\b(spoloc|spolecn|ekonom|politick. dokum|kapitalizm|globali|chudoba|migra)""") to DC_SPOLOCNOST
    )

    // -- Hobby sub-zanre --
    const val HB_ZAHRADA = "hb_zahrada"
    const val HB_BYVANIE = "hb_byvanie"
    const val HB_VARENIE = "hb_varenie"
    const val HB_AUTO = "hb_auto"
    const val HB_CESTOVANIE = "hb_cestovanie"
    const val HB_ZDRAVIE = "hb_zdravie"
    const val HB_DIY = "hb_diy"
    const val HB_INE = "hb_ine"
    val hobbySubOrder = listOf(HB_ZAHRADA, HB_BYVANIE, HB_VARENIE, HB_AUTO, HB_CESTOVANIE, HB_ZDRAVIE, HB_DIY, HB_INE)
    private val hobbyKeyword: List<Pair<Regex, String>> = listOf(
        Regex("""\b(zahrad|kvetin|sklenik|tri v zahrade|okrasn. rastlin)""") to HB_ZAHRADA,
        Regex("""\b(byvan|interier|renovac|architektur|rekonstruk|nabytk|bydleni)""") to HB_BYVANIE,
        Regex("""\b(varen|recept|jedl|kuchar|peciem|babickovy)""") to HB_VARENIE,
        Regex("""\b(auto|moto|automobil|motorka|automotive|autosalon|garaz)""") to HB_AUTO,
        Regex("""\b(cestovan|cestujeme|destinac|vylety|destination|cestopis)""") to HB_CESTOVANIE,
        Regex("""\b(zdrav|fitness|cvicen|wellness)""") to HB_ZDRAVIE,
        Regex("""\b(kutil|diy|hand made|vlastnorucn|svojpomocn)""") to HB_DIY
    )

    // Mapa kategoria -> (poradie sub-zanrov, keyword mapa, "ine" kluc)
    private fun subConfig(topCat: String): Triple<List<String>, List<Pair<Regex, String>>, String>? =
        when (topCat) {
            FILM, SERIAL -> Triple(movieSubOrder, movieKeyword, MV_INE)
            SPORT -> Triple(sportSubOrder, sportKeyword, SP_INE)
            NEWS -> Triple(newsSubOrder, newsKeyword, NW_INE)
            SHOW -> Triple(showSubOrder, showKeyword, SH_INE)
            CHILDREN -> Triple(childrenSubOrder, childrenKeyword, CH_INE)
            MUSIC -> Triple(musicSubOrder, musicKeyword, MU_INE)
            ARTS -> Triple(artsSubOrder, artsKeyword, AR_INE)
            DOCUMENTARY -> Triple(docSubOrder, docKeyword, DC_INE)
            HOBBY -> Triple(hobbySubOrder, hobbyKeyword, HB_INE)
            else -> null
        }

    /** Ma dana top kategoria sub-zanre? (vsetky okrem Nezaradene) */
    fun hasSubgenres(topCat: String): Boolean = subConfig(topCat) != null

    /** Zoskupovat zaznamy pod nazov programu? (opakovane programy: serialy,
     *  spravy, sou, detske, hudba, umenie, dokumenty, hobby). Filmy a sport nie. */
    fun isSeriesLike(topCat: String): Boolean =
        topCat != FILM && topCat != SPORT && topCat != OTHER

    fun subOrderFor(topCat: String): List<String> =
        subConfig(topCat)?.first ?: emptyList()

    /** Sub-zaner pre zaznam v danej top kategorii. */
    fun subgenre(entry: DvrEntry, topCat: String): String {
        val cfg = subConfig(topCat) ?: return ""
        // Film/serial: najprv DVB full byte, potom korpus titulov, az potom keyword
        if (topCat == FILM || topCat == SERIAL) {
            val ct = entry.contentType
            if (ct in 0x11..0x18) dvbGenreToSubcat[ct]?.let { return it }
            if (corpus.isNotEmpty()) {
                val key = canonicalTitleForCorpus(entry.dispTitle)
                if (key.isNotEmpty()) corpus[key]?.let { return it }
            }
        }
        val text = stripAccentsLower(
            listOf(entry.dispTitle, entry.dispSubtitle, entry.dispDescription)
                .filter { it.isNotBlank() }.joinToString(" ")
        )
        if (text.isNotBlank()) {
            for ((p, sub) in cfg.second) if (p.containsMatchIn(text)) return sub
        }
        // film/serial: horor len v nazve
        if (topCat == FILM || topCat == SERIAL) {
            val titleOnly = stripAccentsLower(entry.dispTitle)
            if (titleOnly.isNotBlank() && horrorTitle.containsMatchIn(titleOnly)) return MV_HOROR
        }
        return cfg.third
    }

    /** Kanonicky nazov programu (bez epizodneho sufixu a tech markerov) na
     *  zoskupenie. "Otec Brown IV (1)" -> "Otec Brown IV",
     *  "TV Noviny" zostane "TV Noviny" (vsetky bulletiny pod jednou zlozkou). */
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
