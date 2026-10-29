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
    private const val FORMAT_KEY = "__format"

    data class Res(val top: String?, val sub: String?)

    // canonical -> Res; a null value = a negative cache entry (no match)
    // M738 (audit B20): copy-on-write — the classifier and exportJson read it while fetch() adds
    // (a plain HashMap could throw ConcurrentModificationException); writes are rare (rate-limited)
    @kotlin.concurrent.Volatile private var cache: Map<String, Res?> = emptyMap()
    /** M738: after repeated failures (offline, rate limited) lookups pause instead of hammering. */
    @kotlin.concurrent.Volatile private var failStreak = 0
    @kotlin.concurrent.Volatile private var pausedUntilSec = 0L

    private val genreToSub: Map<String, String> = mapOf(
        "Action" to DvrClassifier.MV_ACTION,
        "Adventure" to DvrClassifier.MV_ADVENTURE,
        "Animation" to DvrClassifier.MV_ANIMATED,
        "Biography" to DvrClassifier.MV_HISTORICAL,
        "Comedy" to DvrClassifier.MV_COMEDY,
        "Crime" to DvrClassifier.MV_CRIME,
        "Drama" to DvrClassifier.MV_DRAMA,
        "Family" to DvrClassifier.MV_ADVENTURE,
        "Fantasy" to DvrClassifier.MV_SCIFI,
        "Film-Noir" to DvrClassifier.MV_CRIME,
        "History" to DvrClassifier.MV_HISTORICAL,
        "Horror" to DvrClassifier.MV_HORROR,
        "Mystery" to DvrClassifier.MV_CRIME,
        "Romance" to DvrClassifier.MV_ROMANCE,
        "Sci-Fi" to DvrClassifier.MV_SCIFI,
        "Thriller" to DvrClassifier.MV_CRIME,
        "War" to DvrClassifier.MV_HISTORICAL,
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

    /**
     * Downloads and caches the result for a title. Returns true if a record was added.
     * M738 (audit B20): only a real answer is cached — a confirmed "no match" too, but not a failure
     * (offline, timeout, HTTP 429/403, a non-JSON body): until now any of those was stored as "no match"
     * for good and the title never got a category. Cancellation is not swallowed any more.
     */
    suspend fun fetch(title: String): Boolean {
        val key = DvrClassifier.canonicalForImdb(title)
        if (key.isEmpty() || cache.containsKey(key)) return false
        val now = sk.tvhclient.shared.currentTimeSeconds()
        if (now < pausedUntilSec) return false
        val res = try {
            request(key)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (++failStreak >= 3) { pausedUntilSec = now + 600; failStreak = 0 }
            return false
        }
        failStreak = 0
        cache = cache + (key to res)  // a confirmed negative result (null) is stored too
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

    /** The result, null = a confirmed "no match"; throws when the answer is not a search result at all. */
    private fun parse(text: String): Res? {
        val root = json.parseToJsonElement(text).jsonObject   // not JSON (an error page) -> throws
        val edges = root["data"]?.jsonObject
            ?.get("mainSearch")?.jsonObject
            ?.get("edges")?.jsonArray ?: throw IllegalStateException("no search result")
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
            put(FORMAT_KEY, 2)   // M738: negative entries of this format are real "no match" answers
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
            val m = HashMap(cache)
            // M738: a file from before 2 also holds failures stored as "no match" — those are dropped
            // once (looked up again, rate-limited), the real matches stay
            val trustNegatives = (root[FORMAT_KEY] as? JsonPrimitive)?.contentOrNull == "2"
            for ((k, v) in root) {
                if (k == FORMAT_KEY) continue
                val o = v as? JsonObject ?: continue
                val top = (o["top"] as? JsonPrimitive)?.contentOrNull
                val sub = (o["sub"] as? JsonPrimitive)?.contentOrNull
                if (top == null && sub == null) { if (trustNegatives) m[k] = null } else m[k] = Res(top, sub)
            }
            cache = m
        } catch (e: Exception) {
        }
    }

    fun size(): Int = cache.size
}
