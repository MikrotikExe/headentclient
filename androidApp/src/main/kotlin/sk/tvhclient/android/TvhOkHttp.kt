package sk.tvhclient.android

import android.util.Base64
import okhttp3.OkHttpClient
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.net.DigestAuthenticator

/**
 * M762: the one place that builds an OkHttp client for a Tvheadend server.
 *
 * The same twenty lines used to be copied into HttpTsFeeder (M253), DvrProxy and TeletextSession:
 * a User-Agent, preemptive Basic unless the server is set to digest (M715: only where Basic is
 * allowed), and a Digest authenticator for the 401 challenge. Every client derives from [base], so
 * they all share one dispatcher and one connection pool.
 */
internal object TvhOkHttp {
    /** The base client (default timeouts); use `base.newBuilder()` for a variant. */
    val base: OkHttpClient by lazy { OkHttpClient() }

    /**
     * A builder with the server's authentication. [extraHeaders] are added to every request
     * (name to value). Timeouts and other settings are the caller's to set on the result.
     */
    fun authBuilder(server: TvhServer, extraHeaders: List<Pair<String, String>> = emptyList()): OkHttpClient.Builder {
        val hasCreds = server.username.isNotEmpty()
        val preemptiveBasic: String? = if (hasCreds && server.authMode != "digest") {
            "Basic " + Base64.encodeToString(
                sk.tvhclient.shared.net.TvhCredEscape.basicPair(server.username, server.password)   /* M712 */.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
        } else null
        val builder = base.newBuilder()
            .addInterceptor { chain ->
                val r = chain.request().newBuilder().apply {
                    header("User-Agent", sk.tvhclient.shared.ClientIdent.userAgent)
                    for ((k, v) in extraHeaders) header(k, v)
                    if (preemptiveBasic != null && sk.tvhclient.shared.net.AuthSchemeMemo.basicAllowed(server.authMode, chain.request().url))   // M715
                        header("Authorization", preemptiveBasic)
                }.build()
                chain.proceed(r)
            }
        if (hasCreds && server.authMode != "none") {
            builder.authenticator(DigestAuthenticator(server.username, server.password))
        }
        return builder
    }
}
