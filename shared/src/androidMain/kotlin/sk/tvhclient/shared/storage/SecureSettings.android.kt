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
    return try {
        buildEncrypted(ctx)
    } catch (t: Throwable) {
        runCatching {
            ctx.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        }
        runCatching { ctx.deleteSharedPreferences(SECURE_PREFS) }
        runCatching {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MASTER_KEY_ALIAS)
        }
        // the second attempt already with clean state; if even that fails, let it crash loudly
        buildEncrypted(ctx)
    }
}
