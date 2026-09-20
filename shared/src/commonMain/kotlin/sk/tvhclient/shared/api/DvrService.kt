package sk.tvhclient.shared.api

/**
 * M472: result of a DVR operation.
 *
 * `error` is the text from the server (both HTSP and HTTP send it in a readable form,
 * e.g. "User does not have access") — the app displays it exactly as it arrived,
 * so the user knows why it did not go through.
 */
data class DvrResult(
    val success: Boolean,
    val error: String? = null,
    /** ID of the created entry, if the server returned one. */
    val id: String? = null,
    /** M491: the server did not answer within the limit — the message text is filled in by the UI (translation). */
    val timeout: Boolean = false
) {
    companion object {
        val OK = DvrResult(true)
        fun fail(msg: String?, timeout: Boolean = false) =
            DvrResult(false, msg, timeout = timeout)
    }
}

/**
 * Common interface for recording — implemented by both the HTSP and the HTTP path,
 * so the UI does not need to know which one the user connects through.
 */
interface DvrService {
    /** The user's rights; the UI shows or hides recording based on them. */
    suspend fun access(): DvrAccess

    /** Schedules a recording for an EPG event. */
    suspend fun recordEvent(eventId: Long, configId: String? = null): DvrResult

    /** Cancels a scheduled/running recording, the entry remains. */
    suspend fun cancel(id: String): DvrResult

    /** Deletes the recording together with its file. */
    suspend fun delete(id: String): DvrResult
}

/** M472: DVR profile (recording configuration) on the server. */
data class DvrConfig(val uuid: String, val name: String)
