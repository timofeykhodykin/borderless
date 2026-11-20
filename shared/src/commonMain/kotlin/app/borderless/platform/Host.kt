package app.borderless.platform

/**
 * What the platform gives the shared code at start: its bundled files (regions.json, countries.json), the UI language
 * and its strings, and where failures worth reporting go. Android sets them in `Res.init` / `BorderlessApp` (assets,
 * resources, `Errors.report`), the Mac in its app and service (bundle resources, `Shared.strings`).
 */
object Host {
    /** A bundled text file by name, null when missing. Unit tests read the Android source tree. */
    var asset: (String) -> String? = ::sourceAsset

    /** A failure kept for "Errors to report", not shown to the user. */
    var report: (Throwable, String) -> Unit = { _, _ -> }

    /** The UI language as a BCP 47 tag ("en", "ru"). */
    var language: () -> String = { "en" }

    /** A string of the UI language by its Android resource name; null when there is none (tests). */
    var string: (String) -> String? = { null }

    /** A string of the UI language by its Android resource name, formatted with [args]; the name itself when missing. */
    var text: (String, List<Any>) -> String = { name, _ -> name }

    /** A string array of the UI language by its Android resource name. */
    var stringArray: (String) -> List<String> = { emptyList() }

    /** A development build (test files are honoured, e.g. the routing lists' `manifest.test.json`). */
    var debug: Boolean = false

    /** The system and the device, for subscription panels that ask (`x-device-os`, `x-ver-os`, `x-device-model`). */
    var system: String = "Android"
    var systemVersion: String = ""
    var deviceModel: String = ""
}

/** Where tests find the bundled files: the Android module's assets in the source tree (JVM); nothing elsewhere. */
expect fun sourceAsset(name: String): String?
