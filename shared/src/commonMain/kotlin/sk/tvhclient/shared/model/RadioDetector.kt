package sk.tvhclient.shared.model

/**
 * FALLBACK radio detection based on tag names (taken from the Enigma2 plugin,
 * _bouquet_tags.py _is_radio_by_tags).
 *
 * M504: the main path is now the service type from the DVB tables (Channel.isRadioByService),
 * the same as in Kodi. This heuristic is only used when the server does not provide the types —
 * it is fragile, because it depends on how each person has named their tags (it will not find
 * the German "Hoerfunk" or the Turkish "Radyo" here).
 */
object RadioDetector {
    // M504: more languages added — the fallback should at least work in the common cases
    private val radioTokens = listOf(
        "radio", "radia", "radia fm", "radio fm",
        "radiostanice", "radiostanica", "rozhlas",
        "radyo", "hoerfunk", "horfunk", "rundfunk", "radioem"
    )

    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            sb.append(
                when (c) {
                    'á', 'ä', 'à', 'â' -> 'a'
                    'č', 'ç' -> 'c'
                    'ď' -> 'd'
                    'é', 'ě', 'è', 'ê' -> 'e'
                    'í', 'ì', 'î' -> 'i'
                    'ľ', 'ĺ' -> 'l'
                    'ň' -> 'n'
                    'ó', 'ô', 'ö' -> 'o'
                    'ŕ', 'ř' -> 'r'
                    'š', 'ś' -> 's'
                    'ť' -> 't'
                    'ú', 'ů', 'ü' -> 'u'
                    'ý' -> 'y'
                    'ž', 'ź' -> 'z'
                    else -> c
                }
            )
        }
        return sb.toString().trim()
    }

    /** Is the channel a radio station according to its tag names? */
    fun isRadio(tagNames: List<String>): Boolean {
        for (raw in tagNames) {
            val n = normalize(raw)
            if (n.isEmpty()) continue
            for (tok in radioTokens) {
                val t = normalize(tok)
                if (n == t || n.contains(t)) return true
            }
        }
        return false
    }
}
