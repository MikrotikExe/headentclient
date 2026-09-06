package sk.tvhclient.shared.net

import java.net.Inet4Address
import java.net.InetAddress

actual fun resolveHostBlocking(host: String): String? = try {
    val all = InetAddress.getAllByName(host)
    (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
} catch (_: Throwable) { null }
