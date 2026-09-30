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
        /** M715: the account lacks the right for this action (HTSP noaccess, HTTP 403) — translated by the UI. */
        const val NO_ACCESS = "[noaccess]"
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

    /**
     * M710: stops a RUNNING recording gracefully — the recording is kept as finished. Cancelling a
     * running recording makes Tvheadend end it with SM_CODE_ABORTED ("Aborted by user"), i.e. as a
     * failed recording (dvr_entry_cancel vs dvr_entry_stop in dvr_db.c).
     */
    suspend fun stop(id: String): DvrResult

    /** Deletes the recording together with its file. */
    suspend fun delete(id: String): DvrResult

    // ---- M696: recording rules (Kodi "timer rules") ----

    /** "Record by EPG" rules on the server. */
    suspend fun autorecs(): List<sk.tvhclient.shared.model.DvrAutorec>

    /** "Record by time" rules on the server. */
    suspend fun timerecs(): List<sk.tvhclient.shared.model.DvrTimerec>

    /** Creates a rule; [DvrResult.id] carries the new id when the server returns one. */
    suspend fun addAutorec(rule: sk.tvhclient.shared.model.DvrAutorec): DvrResult

    /** Updates an existing rule (all editable fields are sent; [DvrAutorec.id] picks the entry). */
    suspend fun updateAutorec(rule: sk.tvhclient.shared.model.DvrAutorec): DvrResult

    suspend fun deleteAutorec(id: String): DvrResult

    suspend fun addTimerec(rule: sk.tvhclient.shared.model.DvrTimerec): DvrResult

    suspend fun updateTimerec(rule: sk.tvhclient.shared.model.DvrTimerec): DvrResult

    suspend fun deleteTimerec(id: String): DvrResult
}

/** M472: DVR profile (recording configuration) on the server. */
data class DvrConfig(val uuid: String, val name: String)
