package sk.tvhclient.shared.model

/**
 * Mapping of the DVB content-type (upper nibble) to a genre name. Taken from
 * the Enigma2 plugin (classifier.py _CT_TO_CAT_BASE). Returns a key which the
 * UI translates through its own strings (SK/CZ/EN), so the genre respects the app's language.
 *
 * DVB EN 300 468 content_descriptor, level 1 nibble:
 *  1 Film/Drama, 2 News, 3 Show, 4 Sport, 5 Children, 6 Music,
 *  7 Arts/Culture, 8 Social/Political, 9 Education/Science, 10 Leisure
 */
object DvbGenre {
    const val FILM = "film"
    const val NEWS = "news"
    const val SHOW = "show"
    const val SPORT = "sport"
    const val CHILDREN = "children"
    const val MUSIC = "music"
    const val ARTS = "arts"
    const val SOCIAL = "social"
    const val EDUCATION = "education"
    const val LEISURE = "leisure"

    /** null if unknown/0. */
    fun keyFor(topNibble: Int): String? = when (topNibble) {
        1 -> FILM
        2 -> NEWS
        3 -> SHOW
        4 -> SPORT
        5 -> CHILDREN
        6 -> MUSIC
        7 -> ARTS
        8 -> SOCIAL
        9 -> EDUCATION
        10 -> LEISURE
        else -> null
    }
}
