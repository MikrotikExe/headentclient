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

    fun list(): List<TvhServer> {
        val raw = settings.getStringOrNull(KEY_SERVERS) ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(id: String): TvhServer? = list().firstOrNull { it.id == id }

    fun upsert(server: TvhServer) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.id == server.id }
        if (idx >= 0) current[idx] = server else current.add(server)
        persist(current)
        if (activeId == null) activeId = server.id
    }

    fun delete(id: String) {
        persist(list().filterNot { it.id == id })
        if (activeId == id) {
            activeId = list().firstOrNull()?.id
        }
    }

    var activeId: String?
        get() = settings.getStringOrNull(KEY_ACTIVE)
        set(value) {
            if (value == null) settings.remove(KEY_ACTIVE)
            else settings.putString(KEY_ACTIVE, value)
        }

    fun active(): TvhServer? = activeId?.let { get(it) }

    private fun persist(servers: List<TvhServer>) {
        settings.putString(KEY_SERVERS, json.encodeToString(servers))
    }

    private companion object {
        const val KEY_SERVERS = "servers_v1"
        const val KEY_ACTIVE = "active_server_id"
    }
}
