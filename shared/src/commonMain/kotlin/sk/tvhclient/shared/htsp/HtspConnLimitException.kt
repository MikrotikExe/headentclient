package sk.tvhclient.shared.htsp

/**
 * M692: Tvheadend refused the connection because of the account's connection limit
 * ("Change connection limits" on the user entry): the authenticate reply carries `noaccess=1`
 * together with `connlimit=1`.
 *
 * The server counts the account's connections in tcp_connection_launch(). While one HTSP connection
 * streams, every further one — even a plain metadata / EPG connection — is held for up to 5 s and
 * then refused. It is NOT a wrong password, so it must not be reported as one, and retrying it
 * immediately only produces more refused connections (and "multiple connections are not allowed"
 * lines in the server log).
 *
 * [skipped] = the app did not even connect, because a previous refusal is still within its backoff
 * window (HtspData) — the result is the same for the caller.
 */
class HtspConnLimitException(val skipped: Boolean = false) :
    IllegalStateException(MESSAGE) {
    companion object {
        /** Stable text — the Android UI recognises it and shows a translated message instead. */
        const val MESSAGE = "HTSP: connection limit reached for this account (connlimit)"
        const val MARKER = "(connlimit)"
    }
}
