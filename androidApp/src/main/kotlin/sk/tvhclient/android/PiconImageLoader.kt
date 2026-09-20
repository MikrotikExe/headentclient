package sk.tvhclient.android

import android.content.Context
import android.util.Base64
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.net.DigestAuthenticator

/**
 * Coil ImageLoader for picons. Auth: Basic pre-emptively (the fast path for
 * basic/auto servers) + DigestAuthenticator on a 401 challenge, so picons
 * come through from a digest-only server as well in the default mode. 50 MB disk cache + memory
 * cache — picons are fetched lazily as you scroll, no upfront burst.
 */
object PiconImageLoader {

    @Volatile private var loader: ImageLoader? = null
    @Volatile private var forServerId: String? = null

    fun get(context: Context, server: TvhServer?): ImageLoader {
        val existing = loader
        if (existing != null && forServerId == server?.id) return existing
        synchronized(this) {
            val again = loader
            if (again != null && forServerId == server?.id) return again
            val built = build(context.applicationContext, server)
            loader = built
            forServerId = server?.id
            return built
        }
    }

    /** M272: manual clearing of the picon cache (memory + disk) — for "Refresh list" in settings. */
    fun clearCache(context: Context, server: TvhServer?) {
        val il = get(context, server)
        runCatching { il.memoryCache?.clear() }
        runCatching { il.diskCache?.clear() }
    }

    private fun build(context: Context, server: TvhServer?): ImageLoader {
        val hasCreds = server != null && server.username.isNotEmpty()
        // We send Basic pre-emptively only when digest is not forced (saves a roundtrip
        // on basic/auto servers); for digest-only the Authenticator below sorts it out.
        val preemptiveBasic: String? = if (hasCreds && server!!.authMode != "digest") {
            val raw = "${server.username}:${server.password}"
            "Basic " + Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } else null

        val builder = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request().newBuilder().apply {
                    if (preemptiveBasic != null) header("Authorization", preemptiveBasic)
                }.build()
                chain.proceed(req)
            })

        // Digest (and the Basic fallback) via the 401 challenge — so picons come through from
        // a digest-only server too, not only when Basic is forced.
        if (hasCreds && server!!.authMode != "none") {
            builder.authenticator(DigestAuthenticator(server.username, server.password))
        }

        val ok = builder.build()

        return ImageLoader.Builder(context)
            .okHttpClient(ok)
            .memoryCache {
                MemoryCache.Builder(context).maxSizePercent(0.15).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("picons"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .build()
    }

}
