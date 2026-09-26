package sk.tvhclient.android

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.DvrEntry
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer

/**
 * M490 / M669: recording the currently running programme from the player (extracted from PlayerActivity).
 * Both the state (rights, eventId, an existing recording, the profile selection dialog M606/M607) and the actions
 * (record / cancel, from the context menu) are shared by all entry points: the classic bar, the phone
 * "More" panel, the modern TV overlay, the info window. The activity accesses the states via delegates
 * with the original names (dvrCanRecordState…), because composables read them via `dvrActivity`.
 */
internal class DvrRecordController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val live: LiveSession,
    private val epgUpcoming: MutableState<Map<String, List<EpgEvent>>>,
    private val recInProgressByChan: MutableState<Map<String, DvrEntry>>,
    /** M608: the red dot / cassette right after scheduling (PlayerEpgStore.refreshRecordingOnly). */
    private val refreshRecordingOnly: () -> Unit
) {
    val canRecordState = mutableStateOf(false)
    val eventIdState = mutableStateOf<Long?>(null)
    val existingState = mutableStateOf<DvrEntry?>(null)

    // M606: the DVR profile selection dialog (the list of options; empty = closed) + cursor
    val askState = mutableStateOf<List<String>>(emptyList())
    val askSelState = mutableStateOf(0)
    /** M607: when the profile dialog belongs to a channel from the context menu (not the playing one). */
    var askTarget: Pair<LivePlaylist.LiveChannel, EpgEvent>? = null
        private set

    /** Should the recording control be shown at all? */
    fun recordVisible(): Boolean =
        canRecordState.value && (eventIdState.value != null || existingState.value != null)

    private fun currentChannel(): LivePlaylist.LiveChannel? = live.channelsState.value.getOrNull(live.indexState.value)

    fun currentEventId(): Long? {
        val ch = currentChannel() ?: return null
        val nowSec = System.currentTimeMillis() / 1000
        return epgUpcoming.value[ch.uuid]
            ?.firstOrNull { it.start <= nowSec && nowSec < it.stop }
            ?.eventId
    }

    /**
     * M484: the channel and the currently running programme — after scheduling it is sent to
     * DvrController so that the recording is reflected in the list immediately and the button
     * switches to "Cancel" without waiting for the metadata cache to refresh.
     */
    fun currentLiveEvent(): Pair<String, EpgEvent>? {
        val ch = currentChannel() ?: return null
        val nowSec = System.currentTimeMillis() / 1000
        val ev = epgUpcoming.value[ch.uuid]
            ?.firstOrNull { it.start <= nowSec && nowSec < it.stop } ?: return null
        return ch.uuid to ev
    }

    /** M521-fix: the running recording on the currently watched channel from the map of red dots. */
    fun runningRecordingHere(): DvrEntry? {
        val ch = currentChannel() ?: return null
        return recInProgressByChan.value.let { it[ch.uuid] ?: it[ch.name] }
    }

    /** M475: the scheduled/running recording for the currently watched programme (null = none). */
    suspend fun currentEventRecording(server: TvhServer?): DvrEntry? {
        val srv = server ?: return null
        val ch = currentChannel() ?: return null
        val nowSec = System.currentTimeMillis() / 1000
        val ev = epgUpcoming.value[ch.uuid]
            ?.firstOrNull { it.start <= nowSec && nowSec < it.stop } ?: return null
        return DvrController.scheduledFor(srv, ch.uuid, ev.start, ev.stop)
    }

    /**
     * Determines the rights and the recording state for the currently watched programme.
     *
     * Called when the player starts and after a channel switch — not when the
     * controls are opened. The order of the controls is computed from `playerControlOrder()`,
     * so if an item appeared while the list is open, the indices would shift
     * under our hands and the dpad would activate something else.
     */
    fun refreshState() {
        // M521: discard the state of the PREVIOUS channel at once, before the load.
        // Loading the list of recordings takes seconds over HTTP (on large servers
        // it is over a thousand entries) and until then the button showed the state of the channel
        // the user had just left — once "Cancel" where nothing is being recorded,
        // another time "Record" where a recording is running.
        existingState.value = null
        eventIdState.value = currentEventId()   // from the local EPG cache, synchronously
        // M521-fix: take the recording in progress from the same source that
        // draws the red dots in the channel list (fetchDvrInProgress). It is a map
        // of already loaded RUNNING recordings — available immediately and reliable —
        // whereas DvrController.scheduledFor() pulled the whole list of scheduled ones
        // (over a thousand entries on a large server) and while it ran, the button showed
        // the wrong state.
        currentChannel()?.let { ch ->
            recInProgressByChan.value.let { it[ch.uuid] ?: it[ch.name] }
                ?.let { existingState.value = it }
        }
        scope.launch {
            val srv = Tvh.store.active()
            var eid = currentEventId()
            // first the fast and reliable source, only then the slow list of scheduled ones
            var rec = runningRecordingHere() ?: currentEventRecording(srv)
            // M520: if the EPG for this channel has not been loaded yet, the player
            // does not know the running programme — and without it the record button is not
            // shown at all. That is exactly why it appeared once and not another time, depending
            // on whether the EPG had arrived. So we look it up directly from the server.
            if (eid == null && srv != null) {
                val uuid = currentChannel()?.uuid
                if (uuid != null) {
                    val evs = withContext(Dispatchers.IO) {
                        runCatching {
                            val api = Tvh.apiFor(srv)
                            try { Tvh.fetchEpgForChannel(srv, api, uuid) } finally { api.close() }
                        }.getOrDefault(emptyList())
                    }
                    if (evs.isNotEmpty()) {
                        // add it to the cache, so that the next opening does not have to pull it
                        epgUpcoming.value = epgUpcoming.value + (uuid to evs)
                        val nowSec = System.currentTimeMillis() / 1000
                        val cur = evs.firstOrNull { it.start <= nowSec && nowSec < it.stop }
                        eid = cur?.eventId
                        if (rec == null && cur != null) {
                            rec = DvrController.scheduledFor(srv, uuid, cur.start, cur.stop)
                        }
                    }
                }
            }
            eventIdState.value = eid
            existingState.value = rec
            canRecordState.value = srv != null && DvrController.access(srv).canRecord
        }
    }

    /** Record the currently running programme, or cancel an already scheduled recording. */
    fun toggleRecordCurrent() {
        val srv = Tvh.store.active() ?: return
        scope.launch {
            val existing = existingState.value ?: currentEventRecording(srv)
            if (existing == null) {
                // M606: optional profile selection — only then the recording
                val opts = DvrProfileAsk.options(ctx, srv)
                if (opts.isNotEmpty()) {
                    askTarget = null
                    askSelState.value = 0
                    askState.value = opts
                    return@launch
                }
            }
            recordCurrent(null)
        }
    }

    /** M606: the choice in the profile dialog (OK / click) or cancellation (BACK). */
    fun resolveAsk(name: String?) {
        askState.value = emptyList()
        val target = askTarget
        askTarget = null
        if (name == null) return
        Tvh.store.active()?.let { DvrAskPref.setLastUsed(ctx, it.id, name) }
        scope.launch {
            if (target != null) recordEventOf(target.first, target.second, name) else recordCurrent(name)
        }
    }

    private suspend fun recordCurrent(profile: String?) {
        val srv = Tvh.store.active() ?: return
        run {
            val existing = existingState.value ?: currentEventRecording(srv)
            val eid = eventIdState.value ?: currentEventId()
            if (existing == null && eid == null) return
            val hint = currentLiveEvent()
            val r = if (existing != null) DvrController.cancel(srv, existing)
            else DvrController.recordEvent(
                srv, eid!!,
                hint?.first ?: "",
                hint?.second?.start ?: 0L,
                hint?.second?.stop ?: 0L,
                hint?.second?.title ?: "",
                profile
            )
            // M484: on a duplicate, look up where the recording already is
            val dup = if (r.success || existing != null) null
            else DvrController.duplicateOf(srv, hint?.second?.title ?: "")
            existingState.value = currentEventRecording(srv)
            if (r.success) refreshRecordingOnly()   // M608: the red dot / cassette immediately
            Toast.makeText(
                ctx,
                when {
                    r.success && existing != null -> ctx.getString(R.string.dvr_rec_cancelled)
                    r.success -> ctx.getString(R.string.dvr_rec_scheduled)
                    dup != null && dup.channelName.isNotBlank() -> ctx.getString(
                        R.string.dvr_rec_duplicate, dup.channelName,
                        sk.tvhclient.shared.formatDayLabel(dup.start) + " " +
                            sk.tvhclient.shared.formatTimeHm(dup.start)
                    )
                    else -> ConnLimitText.of(ctx, r.error) ?: ctx.getString(   // M692
                        if (r.timeout) R.string.err_timeout else R.string.dvr_rec_failed
                    )
                },
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** M607: recording from the context menu — with an optional profile selection (M606). */
    fun recordFromCtxMenu(ch: LivePlaylist.LiveChannel, ev: EpgEvent) {
        val srv = Tvh.store.active() ?: return
        scope.launch {
            val opts = DvrProfileAsk.options(ctx, srv)
            if (opts.isNotEmpty()) {
                askTarget = ch to ev
                askSelState.value = 0
                askState.value = opts
            } else recordEventOf(ch, ev, null)
        }
    }

    suspend fun recordEventOf(ch: LivePlaylist.LiveChannel, ev: EpgEvent, profile: String?) {
        val srv = Tvh.store.active() ?: return
        val eid = ev.eventId ?: return
        val r = DvrController.recordEvent(srv, eid, ch.uuid, ev.start, ev.stop, ev.title, profile)
        val dup = if (r.success) null else DvrController.duplicateOf(srv, ev.title)
        if (r.success) {
            refreshRecordingOnly()   // the red dot next to the channel
            if (ch.uuid == live.uuidState.value) existingState.value = currentEventRecording(srv)
        }
        Toast.makeText(
            ctx,
            when {
                r.success -> ctx.getString(R.string.dvr_rec_scheduled)
                dup != null && dup.channelName.isNotBlank() -> ctx.getString(
                    R.string.dvr_rec_duplicate, dup.channelName,
                    sk.tvhclient.shared.formatDayLabel(dup.start) + " " + sk.tvhclient.shared.formatTimeHm(dup.start)
                )
                else -> ConnLimitText.of(ctx, r.error) ?: ctx.getString(if (r.timeout) R.string.err_timeout else R.string.dvr_rec_failed)   // M692
            },
            Toast.LENGTH_LONG
        ).show()
    }
}
