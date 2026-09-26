package sk.tvhclient.android

import android.content.Context
import sk.tvhclient.shared.htsp.HtspConnLimitException

/**
 * M692: the server refused a connection because the account's connection limit is used up
 * (HtspConnLimitException). The shared module only has an English technical text; wherever
 * an error message is shown to the user, it is replaced by a translated explanation — otherwise
 * it looked like a network error or a wrong password.
 */
internal object ConnLimitText {
    fun isConnLimit(msg: String?): Boolean =
        msg != null && msg.contains(HtspConnLimitException.MARKER)

    /** The translated text for a connlimit error, otherwise [msg] unchanged. */
    fun of(ctx: Context, msg: String?): String? =
        if (isConnLimit(msg)) ctx.getString(R.string.err_conn_limit) else msg
}
