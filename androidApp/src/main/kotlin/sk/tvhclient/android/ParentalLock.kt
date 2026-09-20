package sk.tvhclient.android

import android.content.Context

/**
 * Parental lock (PIN). The user turns it on in settings. The PIN is 4 digits
 * (0000-9999). Individual channels are locked (per server) — 18+ ones above all.
 * After the PIN is entered correctly a 5-minute window applies during which the PIN is not asked for;
 * once it expires the PIN is requested again on the next switch to a locked channel / entry into
 * settings. The window's state is shared between the list and the player
 * through SharedPreferences (same process).
 */
object ParentalLock {
    private const val PREFS = "app_prefs"
    private const val KEY_ENABLED = "plock_enabled"
    private const val KEY_PIN = "plock_pin"
    private const val KEY_LOCKED = "plock_channels_" // + serverId -> Set<uuid>
    private const val KEY_UNTIL = "plock_unlocked_until"
    private const val KEY_GRACE_MIN = "plock_grace_min"      // window after unlocking (min); 0 = always require
    private const val KEY_PROTECT_CHANNELS = "plock_protect_channels"
    private const val KEY_PROTECT_SETTINGS = "plock_protect_settings"
    private const val KEY_PIN_INPUT = "plock_pin_input"
    const val DEFAULT_GRACE_MIN = 5

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = p(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean(KEY_ENABLED, on).apply()

    fun hasPin(c: Context) = !p(c).getString(KEY_PIN, "").isNullOrEmpty()
    fun setPin(c: Context, pin: String) = p(c).edit().putString(KEY_PIN, pin).apply()
    fun checkPin(c: Context, pin: String): Boolean = p(c).getString(KEY_PIN, "") == pin

    // Window after unlocking (in minutes). 0 = always require the PIN.
    fun graceMinutes(c: Context): Int = p(c).getInt(KEY_GRACE_MIN, DEFAULT_GRACE_MIN)
    fun setGraceMinutes(c: Context, min: Int) {
        // rule change -> close the active unlocked window so the new setting applies immediately
        // (e.g. switching to "always require" has to take effect at once)
        p(c).edit().putInt(KEY_GRACE_MIN, min).putLong(KEY_UNTIL, 0L).apply()
    }

    // What the PIN protects (both by default).
    fun protectChannels(c: Context) = p(c).getBoolean(KEY_PROTECT_CHANNELS, true)
    fun setProtectChannels(c: Context, on: Boolean) = p(c).edit().putBoolean(KEY_PROTECT_CHANNELS, on).apply()
    fun protectSettings(c: Context) = p(c).getBoolean(KEY_PROTECT_SETTINGS, true)
    fun setProtectSettings(c: Context, on: Boolean) = p(c).edit().putBoolean(KEY_PROTECT_SETTINGS, on).apply()

    // PIN entry method: "picker" (grid) or "keyboard" (the system keyboard)
    fun pinInput(c: Context): String = p(c).getString(KEY_PIN_INPUT, "picker") ?: "picker"
    fun setPinInput(c: Context, mode: String) = p(c).edit().putString(KEY_PIN_INPUT, mode).apply()

    fun lockedSet(c: Context, serverId: String?): Set<String> {
        if (serverId == null) return emptySet()
        return p(c).getStringSet(KEY_LOCKED + serverId, emptySet()) ?: emptySet()
    }

    fun isChannelLocked(c: Context, serverId: String?, uuid: String?): Boolean {
        if (serverId == null || uuid == null) return false
        return lockedSet(c, serverId).contains(uuid)
    }

    fun setChannelLocked(c: Context, serverId: String?, uuid: String, locked: Boolean) {
        if (serverId == null) return
        val cur = HashSet(lockedSet(c, serverId))
        if (locked) cur.add(uuid) else cur.remove(uuid)
        p(c).edit().putStringSet(KEY_LOCKED + serverId, cur).apply()
    }

    fun isUnlocked(c: Context): Boolean =
        System.currentTimeMillis() < p(c).getLong(KEY_UNTIL, 0L)

    fun markUnlocked(c: Context) {
        val min = graceMinutes(c)
        // 0 = always require -> no window
        val until = if (min <= 0) 0L else System.currentTimeMillis() + min * 60_000L
        p(c).edit().putLong(KEY_UNTIL, until).apply()
    }

    /**
     * M263 — close the active grace window. Used when the player is opened on / returned to
     * a locked channel: an old (even cross-session) unlock must not allow watching
     * a locked channel without entering the PIN (e.g. after switching away and back without a PIN).
     */
    fun clearGrace(c: Context) = p(c).edit().putLong(KEY_UNTIL, 0L).apply()


    /** Does the PIN need to be asked for now? (lock on, PIN set and we are not inside the window) */
    fun needsPin(c: Context): Boolean = isEnabled(c) && hasPin(c) && !isUnlocked(c)

    /** Is a PIN needed for the given channel? (+ respects whether channel protection is on) */
    fun channelNeedsPin(c: Context, serverId: String?, uuid: String?): Boolean =
        needsPin(c) && protectChannels(c) && isChannelLocked(c, serverId, uuid)

    /**
     * Is the channel locked and PIN-protected — REGARDLESS of the grace window.
     * Used at player start: every opening of a locked channel must ask for the PIN,
     * the grace window ("do not ask for X min") applies only when switching inside an open player.
     */
    fun channelLockedProtected(c: Context, serverId: String?, uuid: String?): Boolean =
        isEnabled(c) && hasPin(c) && protectChannels(c) && isChannelLocked(c, serverId, uuid)

    /** Is a PIN needed to enter settings? */
    fun settingsNeedsPin(c: Context): Boolean = needsPin(c) && protectSettings(c)
}
