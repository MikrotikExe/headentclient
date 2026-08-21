package sk.tvhclient.shared.storage

import android.annotation.SuppressLint
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * Drzi application context pre shared modul.
 * Inicializuje sa v Application.onCreate() volanim initSecureStorage(this).
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
 * M512: sifrovane ulozisko odolne voci strate kluca.
 *
 * Kluc zije v Android Keystore a je viazany na instalaciu. Ked sa appka
 * preinstaluje (alebo sa zasifrovany subor vrati z automatickej zalohy
 * Androidu, kym kluc uz je novy), desifrovanie vyhodi AEADBadTagException —
 * a kedze sa ulozisko vytvara v Application.onCreate, appka spadla este pred
 * prvou obrazovkou a dala sa ozivit len vymazanim dat.
 *
 * Pri takom nesulade preto poskodeny subor aj kluc zahodime a zalozime cisté
 * ulozisko. Cena je strata ulozenych hesiel k serverom — to je vsak jediny
 * mozny vysledok, ked ich uz nie je cim rozsifrovat, a je to nekonecne lepsie
 * nez appka, ktora sa neda spustit.
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
        // druhy pokus uz s cistym stavom; ak by zlyhal aj ten, nech padne nahlas
        buildEncrypted(ctx)
    }
}
