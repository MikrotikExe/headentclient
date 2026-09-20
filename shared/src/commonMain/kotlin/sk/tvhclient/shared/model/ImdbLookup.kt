package sk.tvhclient.shared.model

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Online genre lookup by title through the IMDb GraphQL API (a port of imdb_lookup.py).
 * It handles titles that are not in the corpus — above all the Slovak/Czech names of
 * international series (Priatelia=Friends, Carodejky=Charmed...).
 *
 * Non-blocking: the classifier only queries the cache (cachedSub). Uncached
 * titles are downloaded in the background (fetch) with a rate limit; the cache is
 * persisted to disk via export/importJson (done by the Android layer).
 */
object ImdbLookup {
    private const val GRAPHQL_URL = "https://caching.graphql.imdb.com/"
    private const val UA =
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
    private const val QUERY =
        "query Search(\$q: String!) { mainSearch(first: 5, options: { " +
        "searchTerm: \$q type: TITLE titleSearchOptions: { type: [MOVIE, TV] } }) { " +
        "edges { node { entity { ... on Title { titleText { text } titleType { text } " +
        "releaseYear { year } genres { genres { text } } } } } } } }"

    private val json = Json { ignoreUnknownKeys = true }

    data class Res(val top: String?, val sub: String?)

    // canonical -> Res; a null value = a negative cache entry (no match)
    private val cache = HashMap<String, Res?>()

    private val genreToSub: Map<String, String> = mapOf(
        "Action" to DvrClassifier.MV_AKCNY,
        "Adventure" to DvrClassifier.MV_DOBRODR,
        "Animation" to DvrClassifier.MV_ANIMAK,
        "Biography" to DvrClassifier.MV_HISTORICKY,
        "Comedy" to DvrClassifier.MV_KOMEDIA,
        "Crime" to DvrClassifier.MV_KRIMI,
        "Drama" to DvrClassifier.MV_DRAMA,
        "Family" to DvrClassifier.MV_DOBRODR,
        "Fantasy" to DvrClassifier.MV_SCIFI,
        "Film-Noir" to DvrClassifier.MV_KRIMI,
        "History" to DvrClassifier.MV_HISTORICKY,
        "Horror" to DvrClassifier.MV_HOROR,
        "Mystery" to DvrClassifier.MV_KRIMI,
        "Romance" to DvrClassifier.MV_ROMANTIKA,
        "Sci-Fi" to DvrClassifier.MV_SCIFI,
        "Thriller" to DvrClassifier.MV_KRIMI,
        "War" to DvrClassifier.MV_HISTORICKY,
        "Western" to DvrClassifier.MV_WESTERN
    )

    private val genreToTop: List<Pair<String, String>> = listOf(
        "Reality-TV" to DvrClassifier.SHOW,
        "Talk-Show" to DvrClassifier.SHOW,
        "Game-Show" to DvrClassifier.SHOW,
        "Documentary" to DvrClassifier.DOCUMENTARY,
        "News" to DvrClassifier.NEWS,
        "Sport" to DvrClassifier.SPORT,
        "Musical" to DvrClassifier.MUSIC,
        "Music" to DvrClassifier.MUSIC
    )

    private val client by lazy {
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 8000
                connectTimeoutMillis = 5000
            }
        }
    }

    fun cachedSub(title: String): String? {
        val key = DvrClassifier.canonicalForImdb(title)
        if (key.isEmpty()) return null
        return cache[key]?.sub
    }

    fun isCached(title: String): Boolean {
        val key = DvrClassifier.canonicalForImdb(title)
        return key.isEmpty() || cache.containsKey(key)
    }

    fun worthSearching(title: String): Boolean {
        val k = DvrClassifier.canonicalForImdb(title)
        if (k.length < 4) return false
        val words = k.split(" ").filter { w -> w.any { it.isLetter() } }
        if (words.isEmpty()) return false
        if (words.maxOf { it.length } < 3) return false
        return true
    }

    /** Downloads and caches the result for a title. Returns true if a record was added. */
    suspend fun fetch(title: String): Boolean {
        val key = DvrClassifier.canonicalForImdb(title)
        if (key.isEmpty() || cache.containsKey(key)) return false
        val res = try {
            request(key)
        } catch (e: Exception) {
            null
        }
        cache[key] = res  // store the negative result too (null)
        return true
    }

    private suspend fun request(queryTerm: String): Res? {
        val payload = buildJsonObject {
            put("query", QUERY)
            putJsonObject("variables") { put("q", queryTerm) }
        }
        val text = client.post(GRAPHQL_URL) {
            header("User-Agent", UA)
            header("Content-Type", "application/json")
            header("Accept", "application/json")
            header("Accept-Language", "en-US,en;q=0.9")
            header("X-Imdb-User-Language", "en-US")
            header("X-Imdb-User-Country", "US")
            header("Origin", "https://www.imdb.com")
            header("Referer", "https://www.imdb.com/")
            setBody(payload.toString())
        }.bodyAsText()
        return parse(text)
    }

    private fun parse(text: String): Res? {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return null
        }
        val edges = root["data"]?.jsonObject
            ?.get("mainSearch")?.jsonObject
            ?.get("edges")?.jsonArray ?: return null
        for (edge in edges) {
            val ent = edge.jsonObject["node"]?.jsonObject
                ?.get("entity")?.jsonObject ?: continue
            val genres = ent["genres"]?.jsonObject
                ?.get("genres")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?: emptyList()
            if (genres.isEmpty()) continue
            for ((g, top) in genreToTop) if (genres.contains(g)) return Res(top, null)
            for (g in genres) genreToSub[g]?.let { return Res(null, it) }
            return null
        }
        return null
    }

    // ---- persistence (Android reads/writes filesDir/imdb_cache.json) ----
    fun exportJson(): String {
        val o = buildJsonObject {
            for ((k, v) in cache) {
                putJsonObject(k) {
                    put("top", v?.top)
                    put("sub", v?.sub)
                }
            }
        }
        return o.toString()
    }

    fun importJson(text: String) {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            for ((k, v) in root) {
                val o = v as? JsonObject ?: continue
                val top = (o["top"] as? JsonPrimitive)?.contentOrNull
                val sub = (o["sub"] as? JsonPrimitive)?.contentOrNull
                cache[k] = if (top == null && sub == null) null else Res(top, sub)
            }
        } catch (e: Exception) {
        }
    }

    fun size(): Int = cache.size
}
