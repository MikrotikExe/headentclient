package sk.tvhclient.shared.api

/**
 * M471: the user's rights with respect to recording — a common shape for both paths
 * (HTSP `accessUpdate`, HTTP `api/access/whoami`).
 *
 * Based on `canRecord` the app shows or hides the recording options. This is not a
 * security mechanism — the server enforces the rights itself (HTSP returns an error,
 * HTTP answers 403) — the point is not to offer the user something
 * that the server will refuse anyway.
 */
data class DvrAccess(
    val canRecord: Boolean = false,
    val canSeeFailed: Boolean = false,
    val isAdmin: Boolean = false,
    /** 0 = no limit; otherwise the max. number of concurrent recordings for this user. */
    val recordingLimit: Int = 0,
    /** false = the server did not report the rights (old server) — we have to try and catch the error. */
    val known: Boolean = false
) {
    companion object {
        /** Old server / unknown state: we offer recording, and report the error only once it comes from the server. */
        val UNKNOWN = DvrAccess(canRecord = true, known = false)
        val DENIED = DvrAccess(canRecord = false, known = true)
    }
}
