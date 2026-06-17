package sk.tvhclient.android

import android.content.Context

/**
 * Rodicovsky zamok (PIN). Aktivuje ho uzivatel v nastaveniach. PIN su 4 cislice
 * (0000-9999). Zamykaju sa konkretne kanaly (per server) — najma 18+.
 * Po spravnom zadani PINu plati 5-minutove okno, pocas ktoreho sa PIN nepyta;
 * po jeho uplynuti sa pri dalsom prepnuti na zamknuty kanal / vstupe do
 * nastaveni vypyta znova. Stav okna je zdielany medzi zoznamom a prehravacom
 * cez SharedPreferences (rovnaky proces).
 */
object ParentalLock {
    private const val PREFS = "app_prefs"
    private const val KEY_ENABLED = "plock_enabled"
    private const val KEY_PIN = "plock_pin"
    private const val KEY_LOCKED = "plock_channels_" // + serverId -> Set<uuid>
    private const val KEY_UNTIL = "plock_unlocked_until"
    private const val KEY_GRACE_MIN = "plock_grace_min"      // okno po odomknuti (min); 0 = vzdy vyzadovat
    private const val KEY_PROTECT_CHANNELS = "plock_protect_channels"
    private const val KEY_PROTECT_SETTINGS = "plock_protect_settings"
    const val DEFAULT_GRACE_MIN = 5

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = p(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean(KEY_ENABLED, on).apply()

    fun hasPin(c: Context) = !p(c).getString(KEY_PIN, "").isNullOrEmpty()
    fun setPin(c: Context, pin: String) = p(c).edit().putString(KEY_PIN, pin).apply()
    fun checkPin(c: Context, pin: String): Boolean = p(c).getString(KEY_PIN, "") == pin

    // Okno po odomknuti (v minutach). 0 = vzdy vyzadovat PIN.
    fun graceMinutes(c: Context): Int = p(c).getInt(KEY_GRACE_MIN, DEFAULT_GRACE_MIN)
    fun setGraceMinutes(c: Context, min: Int) = p(c).edit().putInt(KEY_GRACE_MIN, min).apply()

    // Co PIN chrani (predvolene oboje).
    fun protectChannels(c: Context) = p(c).getBoolean(KEY_PROTECT_CHANNELS, true)
    fun setProtectChannels(c: Context, on: Boolean) = p(c).edit().putBoolean(KEY_PROTECT_CHANNELS, on).apply()
    fun protectSettings(c: Context) = p(c).getBoolean(KEY_PROTECT_SETTINGS, true)
    fun setProtectSettings(c: Context, on: Boolean) = p(c).edit().putBoolean(KEY_PROTECT_SETTINGS, on).apply()

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
        // 0 = vzdy vyzadovat -> ziadne okno
        val until = if (min <= 0) 0L else System.currentTimeMillis() + min * 60_000L
        p(c).edit().putLong(KEY_UNTIL, until).apply()
    }

    fun relock(c: Context) = p(c).edit().putLong(KEY_UNTIL, 0L).apply()

    /** Treba teraz vypytat PIN? (zamok zapnuty, PIN nastaveny a nie sme v okne) */
    fun needsPin(c: Context): Boolean = isEnabled(c) && hasPin(c) && !isUnlocked(c)

    /** Treba PIN pre dany kanal? (+ rešpektuje ci je ochrana kanalov zapnuta) */
    fun channelNeedsPin(c: Context, serverId: String?, uuid: String?): Boolean =
        needsPin(c) && protectChannels(c) && isChannelLocked(c, serverId, uuid)

    /** Treba PIN pre vstup do nastaveni? */
    fun settingsNeedsPin(c: Context): Boolean = needsPin(c) && protectSettings(c)
}
