package app.borderless

import android.content.Context
import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/** String resources for code outside Compose (engine, service, notifications, log events). */
object Res {
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun s(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)

    fun array(@androidx.annotation.ArrayRes id: Int): Array<String> = app.resources.getStringArray(id)

    /** Stable name of a string resource, used to store text language-neutrally. */
    fun name(@StringRes id: Int): String = app.resources.getResourceEntryName(id)

    /** A string resource by name, in [locale] (or the app's language); null if it no longer exists. */
    @SuppressLint("DiscouragedApi")
    fun byName(name: String, locale: Locale?, vararg args: Any): String? {
        val ctx = if (locale == null) app else localized.getOrPut(locale) {
            app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(locale) })
        }
        val id = ctx.resources.getIdentifier(name, "string", app.packageName).takeIf { it != 0 } ?: return null
        return runCatching { ctx.getString(id, *args) }.getOrNull()
    }

    private val localized = java.util.concurrent.ConcurrentHashMap<Locale, Context>()

    /** Text of a bundled asset; null before [init] (unit tests) or if it is missing. */
    fun asset(name: String): String? =
        if (!::app.isInitialized) null else runCatching { app.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()

    /** Current UI language (follows the per-app language chosen in settings). */
    val locale: Locale
        get() = if (::app.isInitialized) app.resources.configuration.locales[0] else Locale.getDefault()
}
