package sk.tvhclient.shared.storage

import android.annotation.SuppressLint
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * Holds the application context for the shared module.
 * It is initialised in Application.onCreate() by calling initSecureStorage(this).
 */
@SuppressLint("StaticFieldLeak")
object AppContextHolder {
    lateinit var context: Context
}

fun initSecureStorage(context: Context) {
    AppContextHolder.context = context.applicationContext
}

private const val SECURE_PREFS = "tvh_secure_prefs"
private const val MASTER_KEY_ALIAS = MasterKey.DEFAULT_MASTER_KEY_ALIAS
/** M737: plain prefs counting app starts at which the secure storage could not be opened. */
private const val INIT_GUARD = "tvh_secure_guard"
private const val KEY_FAILS = "fails"

private fun buildEncrypted(ctx: Context): Settings {
    val masterKey = MasterKey.Builder(ctx)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    val prefs = EncryptedSharedPreferences.create(
        ctx,
        SECURE_PREFS,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    return SharedPreferencesSettings(prefs)
}

/**
 * M512: encrypted storage resilient against the loss of the key.
 *
 * The key lives in the Android Keystore and is bound to the installation. When the app
 * is reinstalled (or the encrypted file comes back from Android's automatic
 * backup while the key is already a new one), decryption throws AEADBadTagException —
 * and since the storage is created in Application.onCreate, the app crashed before
 * the first screen and could only be revived by clearing its data.
 *
 * On such a mismatch we therefore throw away both the corrupted file and the key and create a clean
 * storage. The price is the loss of the stored server passwords — but that is the only
 * possible outcome once there is nothing left to decrypt them with, and it is infinitely better
 * than an app that cannot be started.
 */
actual fun createSecureSettings(): Settings {
    val ctx = AppContextHolder.context
    // M737 (audit K12): only a failure that really means "cannot be decrypted any more" wipes the
    // storage. Until now ANY exception did — also a Keystore that is not ready yet right after boot
    // (KeyStoreException / ProviderException / a binder timeout, seen on TV boxes with autostart),
    // and since the file holds the whole server list, every server vanished silently and for good.
    // A transient failure is retried; if it persists, it is thrown (the next start works) instead
    // of destroying the data.
    val guard = ctx.getSharedPreferences(INIT_GUARD, Context.MODE_PRIVATE)
    var last: Throwable? = null
    for (attempt in 0 until 3) {
        try {
            return buildEncrypted(ctx).also {
                if (guard.getInt(KEY_FAILS, 0) != 0) guard.edit().putInt(KEY_FAILS, 0).commit()
            }
        } catch (t: Throwable) {
            last = t
            if (isUndecryptable(t)) break
            runCatching { Thread.sleep(400L * (attempt + 1)) }
        }
    }
    val err = last ?: IllegalStateException("secure storage")
    if (!isUndecryptable(err)) {
        // an unknown failure at three app starts in a row is not transient: wipe as before (M512),
        // so that the app does not stay unable to start
        val fails = guard.getInt(KEY_FAILS, 0) + 1
        guard.edit().putInt(KEY_FAILS, fails).commit()
        if (fails < 3) throw err
    }
    guard.edit().putInt(KEY_FAILS, 0).commit()
    runCatching {
        ctx.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
    runCatching { ctx.deleteSharedPreferences(SECURE_PREFS) }
    runCatching {
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry(MASTER_KEY_ALIAS)
    }
    // the second attempt already with clean state; if even that fails, let it crash loudly
    return buildEncrypted(ctx)
}

/**
 * The stored file can no longer be decrypted with the key we have (reinstall, a file restored
 * from a backup, a corrupted keyset): wiping it is the only way forward. A Keystore that is merely
 * unavailable right now (KeyStoreException, ProviderException) is not such a case.
 */
private fun isUndecryptable(t: Throwable): Boolean {
    var c: Throwable? = t
    var depth = 0
    while (c != null && depth < 8) {
        val msg = c.message.orEmpty().lowercase()
        if (c is javax.crypto.AEADBadTagException ||
            c is javax.crypto.BadPaddingException ||
            c is android.security.keystore.KeyPermanentlyInvalidatedException ||
            c is java.security.UnrecoverableKeyException ||
            c is java.security.InvalidKeyException ||
            c.javaClass.name.endsWith("InvalidProtocolBufferException") ||
            ((c is SecurityException || c is java.security.GeneralSecurityException) && "decrypt" in msg)
        ) return true
        c = c.cause; depth++
    }
    return false
}
