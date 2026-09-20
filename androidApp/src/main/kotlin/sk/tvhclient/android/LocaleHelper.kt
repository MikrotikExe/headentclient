package sk.tvhclient.android

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Choice of the app language independently of the system (System/SK/CZ/EN).
 * It is saved into SharedPreferences and applied via createConfigurationContext
 * in attachBaseContext of every activity. The value "" = system language.
 */
object LocaleHelper {
    private const val PREFS = "app_prefs"
    private const val KEY_LANG = "app_lang"

    /** "" = system, otherwise "sk"/"cs"/"en". */
    fun getLang(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "") ?: ""
    }

    fun setLang(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).apply()
    }

    /** Wraps the context with the chosen language (if it is not the system one). */
    fun wrap(context: Context): Context {
        val lang = getLang(context)
        if (lang.isBlank()) return context
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
