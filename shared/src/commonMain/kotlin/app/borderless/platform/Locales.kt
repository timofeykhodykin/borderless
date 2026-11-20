package app.borderless.platform

/** What the system knows about countries and languages (`java.util.Locale` / `NSLocale`). */
expect object Locales {
    /** ISO 3166 two-letter codes of every country the system knows. */
    fun isoCountries(): Set<String>

    /** The country's name in [language] (a BCP 47 tag); empty when the system has none. */
    fun countryName(code: String, language: String): String

    /** [items] in the alphabetical order of [language]. */
    fun sorted(items: List<String>, language: String): List<String>

    /** [n] with [language]'s thousands separators ("2 865" in Russian, "2,865" in English). */
    fun integer(n: Long, language: String): String

    /** [value] with [digits] decimals and [language]'s decimal separator ("0,4" / "0.4"). */
    fun decimal(value: Double, digits: Int, language: String): String
}
