package sk.tvhclient.shared.net

/**
 * M581-fix: resolves a server name to an IP (preferring IPv4). Returns null when it
 * fails or the platform does not provide resolution. Blocking — call off the main thread.
 * It is used to remember the last working address: on a mobile network DNS sometimes
 * fails for longer than a few seconds, but the server on the old IP keeps running.
 */
expect fun resolveHostBlocking(host: String): String?
