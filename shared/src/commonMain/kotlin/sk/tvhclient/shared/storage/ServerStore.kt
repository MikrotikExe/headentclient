package sk.tvhclient.shared.storage

import com.russhwolf.settings.Settings
import kotlinx.serialization.json.Json
import sk.tvhclient.shared.model.TvhServer

/**
 * Management of the server list in secure storage.
 * The list is serialized as JSON under a single key - with just a handful of
 * servers nothing more sophisticated is needed.
 */
class ServerStore(private val settings: Settings = createSecureSettings()) {

    private val json = Json { ignoreUnknownKeys = true }

    // M750 (performance audit U1): every list()/active() used to decrypt the encrypted storage and
    // parse the whole server JSON — and the lists call active() on the main thread for each row that
    // scrolls into view. The decoded list and the active id are kept in memory; every write goes
    // through this class (upsert/delete/activeId), which refreshes them. Nothing else writes the keys.
    @kotlin.concurrent.Volatile private var cachedList: List<TvhServer>? = null
    @kotlin.concurrent.Volatile private var cachedActive: String? = null
    @kotlin.concurrent.Volatile private var activeLoaded = false

    fun list(): List<TvhServer> {
        cachedList?.let { return it }
        val decoded = readList()
        cachedList = decoded
        return decoded
    }

    private fun readList(): List<TvhServer> {
        val raw = settings.getStringOrNull(KEY_SERVERS) ?: return emptyList()
        return try {
            json.decodeFromString<List<TvhServer>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(id: String): TvhServer? = list().firstOrNull { it.id == id }

    @kotlin.jvm.Synchronized
    fun upsert(server: TvhServer) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == server.id }
        if (idx >= 0) current[idx] = server else current.add(server)
        persist(current)
        if (activeId == null) activeId = server.id
    }

    @kotlin.jvm.Synchronized
    fun delete(id: String) {
        persist(list().filterNot { it.id == id })
        if (activeId == id) {
            activeId = list().firstOrNull()?.id
        }
    }

    var activeId: String?
        get() {
            if (activeLoaded) return cachedActive
            val v = settings.getStringOrNull(KEY_ACTIVE)
            cachedActive = v
            activeLoaded = true
            return v
        }
        @kotlin.jvm.Synchronized set(value) {
            if (value == null) settings.remove(KEY_ACTIVE)
            else settings.putString(KEY_ACTIVE, value)
            cachedActive = value
            activeLoaded = true
        }

    fun active(): TvhServer? = activeId?.let { get(it) }

    private fun persist(servers: List<TvhServer>) {
        settings.putString(KEY_SERVERS, json.encodeToString(servers))
        cachedList = servers.toList()
    }

    private companion object {
        const val KEY_SERVERS = "servers_v1"
        const val KEY_ACTIVE = "active_server_id"
    }
}
