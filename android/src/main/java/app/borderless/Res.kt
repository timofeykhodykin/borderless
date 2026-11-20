package app.borderless

import android.content.Context
import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.annotation.StringRes
import app.borderless.platform.Host
import java.util.Locale

/** String resources for code outside Compose (engine, service, notifications, log events). */
object Res {
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        // The shared code (parsers, zones, countries) reads assets and strings through Host.
        Host.asset = ::asset
        Host.language = { locale.toLanguageTag() }
        Host.string = { name -> byName(name, null) }
        Host.stringArray = { name -> arrayByName(name) }
        Host.text = { name, args -> byName(name, null, *args.toTypedArray()) ?: name }
        Host.system = "Android"
        Host.debug = BuildConfig.DEBUG
        Host.systemVersion = android.os.Build.VERSION.RELEASE
        Host.deviceModel = android.os.Build.MODEL
    }

    @SuppressLint("DiscouragedApi")
    private fun arrayByName(name: String): List<String> {
        val id = app.resources.getIdentifier(name, "array", app.packageName).takeIf { it != 0 } ?: return emptyList()
        return runCatching { app.resources.getStringArray(id).toList() }.getOrDefault(emptyList())
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

    /** The id of a string resource by its name ([S] in the shared code), 0 when there is none. */
    @SuppressLint("DiscouragedApi")
    fun id(name: String): Int = app.resources.getIdentifier(name, "string", app.packageName)

    /** Text of a bundled asset; null before [init] (unit tests) or if it is missing. */
    fun asset(name: String): String? =
        if (!::app.isInitialized) null else runCatching { app.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()

    /** Current UI language (follows the per-app language chosen in settings). */
    val locale: Locale
        get() = if (::app.isInitialized) app.resources.configuration.locales[0] else Locale.getDefault()
}
